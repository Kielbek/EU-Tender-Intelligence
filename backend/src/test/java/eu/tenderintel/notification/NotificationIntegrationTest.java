package eu.tenderintel.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import eu.tenderintel.IntegrationTest;
import eu.tenderintel.TestApi;
import eu.tenderintel.TestApi.Session;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class NotificationIntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    TestApi api;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    NotificationScanner scanner;

    @BeforeEach
    void startFromAKnownCursor() {
        jdbc.update("delete from notification_cursors");
        jdbc.update("insert into notification_cursors (name, position) values ('scanner', now() - interval '10 minutes')");
    }

    private String org(Session s) throws Exception {
        return api.readJson(mvc.perform(get("/api/v1/organizations").with(s.bearer())).andReturn().getResponse())
                .get(0).get("id").asString();
    }

    /** A tender as ingestion would store it: first seen a few minutes ago, published recently. */
    private UUID tender(String title, String country, String cpv, String deadline) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into cpv_codes (code, parent_code, level, labels) values ('45000000', null, 1,
                  '{"en":"Construction work"}') on conflict do nothing
                """);
        jdbc.update("""
                insert into tenders (id, procedure_key, title, country_code, contract_type, main_cpv, published_at,
                    submission_deadline, status, created_at)
                values (?, ?, ?, ?, 'WORKS', ?, now() - interval '1 day', ?::timestamptz, 'OPEN',
                        now() - interval '3 minutes')
                """, id, "test:notif:" + id, title, country, cpv, deadline);
        return id;
    }

    private void match(String org, UUID tender, int score) {
        jdbc.update("""
                insert into tender_matches (organization_id, tender_id, score, breakdown, engine_version, computed_at)
                values (?::uuid, ?, ?, '{}', 1, now() - interval '2 minutes')
                on conflict (organization_id, tender_id) do update set score = excluded.score,
                    computed_at = excluded.computed_at
                """, org, tender, score);
    }

    private JsonNode inbox(Session s, String org) throws Exception {
        return api.readJson(mvc.perform(get("/api/v1/organizations/" + org + "/notifications").with(s.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse());
    }

    @Test
    void newMatchesNotifyOnceAboveTheThresholdAndAreEmailed() throws Exception {
        String email = "match-" + System.nanoTime() + "@notif.test";
        Session ann = api.registerVerifyLogin(email);
        String org = org(ann);
        UUID good = tender("Budowa hali sportowej", "PL", "45000000", "2026-12-20T10:00:00Z");
        UUID weak = tender("Remont chodnika", "PL", "45000000", "2026-12-21T10:00:00Z");
        match(org, good, 86);
        match(org, weak, 55);

        assertThat(scanner.run()).isTrue();
        JsonNode page = inbox(ann, org);
        assertThat(page.get("totalItems").asInt()).isEqualTo(1);
        assertThat(page.get("unread").asInt()).isEqualTo(1);
        JsonNode n = page.get("items").get(0);
        assertThat(n.get("type").asString()).isEqualTo("NEW_MATCH");
        assertThat(n.get("payload").get("score").asInt()).isEqualTo(86);
        assertThat(n.get("tender").get("title").asString()).isEqualTo("Budowa hali sportowej");

        // The first digest goes out (FREE plan: daily digest; nothing sent before).
        Map<String, Object> mail = jdbc.queryForMap("""
                select subject, body_text, list_unsubscribe from email_outbox
                where to_address = ? and template = 'NOTIFICATION_DIGEST'
                """, email);
        assertThat((String) mail.get("subject")).isEqualTo("Dopasowanie 86%: Budowa hali sportowej");
        assertThat((String) mail.get("body_text")).contains("http://localhost:4200/tenders/" + good)
                .contains("Termin składania ofert: 20.12.2026 11:00").contains("/unsubscribe?token=");
        assertThat((String) mail.get("list_unsubscribe"))
                .startsWith("http://localhost:4200/api/v1/notifications/unsubscribe?token=");

        // Scanning again (with the look-back overlap) does not duplicate anything.
        scanner.run();
        assertThat(inbox(ann, org).get("totalItems").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from email_outbox where to_address = ? "
                + "and template = 'NOTIFICATION_DIGEST'", Integer.class, email)).isEqualTo(1);

        // A lower threshold picks up the weaker match on the next scan.
        mvc.perform(put("/api/v1/organizations/" + org + "/notification-preferences").with(ann.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"preferences":[{"type":"NEW_MATCH","inApp":true,"emailFrequency":"WEEKLY",
                                                 "minScore":50}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferences[0].minScore").value(50))
                .andExpect(jsonPath("$.preferences[0].emailFrequency").value("WEEKLY"))
                .andExpect(jsonPath("$.instantAvailable").value(false));
        scanner.run();
        assertThat(inbox(ann, org).get("totalItems").asInt()).isEqualTo(2);

        // A backfill imports older notices now: published 10 days ago is not news either.
        UUID backfilled = tender("Przebudowa mostu z archiwum", "PL", "45000000", "2026-12-22T10:00:00Z");
        jdbc.update("update tenders set published_at = now() - interval '10 days' where id = ?", backfilled);
        match(org, backfilled, 99);
        scanner.run();
        assertThat(inbox(ann, org).get("totalItems").asInt()).isEqualTo(2);

        // A profile change re-scores old tenders: that is not news (they are on the matches page).
        UUID old = tender("Remont starej szkoły", "PL", "45000000", "2026-12-22T10:00:00Z");
        jdbc.update("update tenders set created_at = now() - interval '2 days' where id = ?", old);
        match(org, old, 99);
        scanner.run();
        assertThat(inbox(ann, org).get("totalItems").asInt()).isEqualTo(2);

        // Instant e-mails are a paid feature.
        mvc.perform(put("/api/v1/organizations/" + org + "/notification-preferences").with(ann.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"preferences":[{"type":"NEW_MATCH","inApp":true,"emailFrequency":"INSTANT"}]}"""))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("instant_alerts_unavailable"));
    }

    @Test
    void watchedTendersReportRelevantChangesAndDeadlines() throws Exception {
        String email = "watch-" + System.nanoTime() + "@notif.test";
        Session ola = api.registerVerifyLogin(email);
        String org = org(ola);
        jdbc.update("update organizations set plan_code = 'STARTER' where id = ?::uuid", org);
        UUID changed = tender("Dostawa mebli szkolnych", "PL", "45000000", "2026-12-15T13:00:00Z");
        UUID soon = tender("Odśnieżanie dróg", "PL", "45000000", null);
        jdbc.update("update tenders set submission_deadline = now() + interval '2 days' where id = ?", soon);
        for (UUID t : new UUID[] {changed, soon}) {
            jdbc.update("insert into saved_tenders (id, organization_id, tender_id, status, created_at) "
                    + "values (?, ?::uuid, ?, 'WATCHING', now() - interval '1 hour')", UUID.randomUUID(), org, t);
        }
        jdbc.update("""
                insert into tender_versions (id, tender_id, version_no, change_kind, snapshot, diff, published_at,
                    created_at)
                values (?, ?, 2, 'CHANGE', '{}',
                        '[{"field":"submissionDeadline","old":"2026-12-08T13:00:00Z","new":"2026-12-15T13:00:00Z"},
                          {"field":"title","old":"a","new":"b"}]', now(), now() - interval '2 minutes'),
                       (?, ?, 3, 'CHANGE', '{}', '[{"field":"description","old":"a","new":"b"}]', now(),
                        now() - interval '2 minutes')
                """, UUID.randomUUID(), changed, UUID.randomUUID(), changed);

        scanner.run();
        JsonNode items = inbox(ola, org).get("items");
        assertThat(items).hasSize(2);
        JsonNode change = java.util.stream.StreamSupport.stream(items.spliterator(), false)
                .filter(i -> i.get("type").asString().equals("WATCHED_CHANGED")).findFirst().orElseThrow();
        // Only the deadline is reported (title edits are noise); the description-only version is skipped.
        assertThat(change.get("payload").get("changes")).hasSize(1);
        assertThat(change.get("payload").get("changes").get(0).get("field").asString())
                .isEqualTo("submissionDeadline");

        // STARTER: instant e-mail with both items.
        String body = jdbc.queryForObject("select body_text from email_outbox where to_address = ? "
                + "and template = 'NOTIFICATION_DIGEST'", String.class, email);
        assertThat(body).contains("ZMIANY W OBSERWOWANYCH PRZETARGACH")
                .contains("termin składania ofert: 08.12.2026 14:00 → 15.12.2026 14:00")
                .contains("ZBLIŻAJĄCE SIĘ TERMINY").contains("Odśnieżanie dróg");
    }

    @Test
    void savedSearchAlertsReportNewTendersOnly() throws Exception {
        Session kasia = api.registerVerifyLogin("search-" + System.nanoTime() + "@notif.test");
        String org = org(kasia);
        String base = "/api/v1/organizations/" + org + "/saved-searches";
        String id = api.readJson(mvc.perform(post(base).with(kasia.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Czechy budowlane\",\"filters\":{\"country\":[\"CZ\"]}}"))
                .andExpect(status().isCreated()).andReturn().getResponse()).get("id").asString();
        mvc.perform(put(base + "/" + id + "/alert").with(kasia.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"frequency\":\"INSTANT\"}"))
                .andExpect(status().isPaymentRequired());
        mvc.perform(put(base + "/" + id + "/alert").with(kasia.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"frequency\":\"DAILY\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alertEnabled").value(true))
                .andExpect(jsonPath("$.alertFrequency").value("DAILY"));
        // Only tenders added after the alert was switched on count.
        jdbc.update("update saved_searches set alert_checked_at = now() - interval '5 minutes' where id = ?::uuid",
                id);
        tender("Most w Brnie", "CZ", "45000000", "2026-12-30T10:00:00Z");
        tender("Szkoła w Opolu", "PL", "45000000", "2026-12-30T10:00:00Z");

        scanner.run();
        JsonNode n = inbox(kasia, org).get("items").get(0);
        assertThat(n.get("type").asString()).isEqualTo("SAVED_SEARCH");
        assertThat(n.get("payload").get("count").asInt()).isEqualTo(1);
        assertThat(n.get("savedSearch").get("name").asString()).isEqualTo("Czechy budowlane");
        scanner.run();
        assertThat(inbox(kasia, org).get("totalItems").asInt()).isEqualTo(1);
    }

    @Test
    void inboxIsPrivateAndCanBeMarkedRead() throws Exception {
        Session ewa = api.registerVerifyLogin("ewa-" + System.nanoTime() + "@notif.test");
        Session eve = api.registerVerifyLogin("eve-" + System.nanoTime() + "@notif.test");
        String org = org(ewa);
        match(org, tender("Termomodernizacja szkoły", "PL", "45000000", "2026-12-20T10:00:00Z"), 90);
        match(org, tender("Budowa przedszkola", "PL", "45000000", "2026-12-20T10:00:00Z"), 80);
        scanner.run();
        JsonNode page = inbox(ewa, org);
        assertThat(page.get("unread").asInt()).isEqualTo(2);
        String first = page.get("items").get(0).get("id").asString();

        mvc.perform(get("/api/v1/organizations/" + org + "/notifications").with(eve.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/organizations/" + org + "/notifications/" + first + "/read").with(eve.bearer()))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/v1/organizations/" + org + "/notifications/" + first + "/read").with(ewa.bearer()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/organizations/" + org + "/notifications/unread-count").with(ewa.bearer()))
                .andExpect(jsonPath("$.unread").value(1));
        mvc.perform(get("/api/v1/organizations/" + org + "/notifications").param("unread", "true")
                .with(ewa.bearer())).andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(post("/api/v1/organizations/" + org + "/notifications/read-all").with(ewa.bearer()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/organizations/" + org + "/notifications/unread-count").with(ewa.bearer()))
                .andExpect(jsonPath("$.unread").value(0));
    }

    @Test
    void unsubscribeLinkSwitchesOffEmailsWithoutSigningIn() throws Exception {
        String email = "unsub-" + System.nanoTime() + "@notif.test";
        Session jan = api.registerVerifyLogin(email);
        String org = org(jan);
        match(org, tender("Budowa wiaduktu", "PL", "45000000", "2026-12-20T10:00:00Z"), 95);
        scanner.run();
        String header = jdbc.queryForObject("select list_unsubscribe from email_outbox where to_address = ? "
                + "and template = 'NOTIFICATION_DIGEST'", String.class, email);
        String token = header.substring(header.indexOf("token=") + 6);

        mvc.perform(post("/api/v1/notifications/unsubscribe").param("token", token + "x")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED).content("List-Unsubscribe=One-Click"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_token"));
        // RFC 8058 one-click POST from the mail client.
        mvc.perform(post("/api/v1/notifications/unsubscribe").param("token", token)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED).content("List-Unsubscribe=One-Click"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizationName").value("Org of " + email));
        // The app's unsubscribe page (JSON).
        mvc.perform(post("/api/v1/notifications/unsubscribe").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/organizations/" + org + "/notification-preferences").with(jan.bearer()))
                .andExpect(jsonPath("$.preferences[0].emailFrequency").value("OFF"))
                .andExpect(jsonPath("$.preferences[1].emailFrequency").value("OFF"))
                .andExpect(jsonPath("$.preferences[2].emailFrequency").value("OFF"))
                .andExpect(jsonPath("$.preferences[0].inApp").value(true));
        // New notifications still appear in the app, but no e-mail is sent.
        match(org, tender("Budowa ronda", "PL", "45000000", "2026-12-20T10:00:00Z"), 95);
        jdbc.update("delete from notification_digests where user_id = ?::uuid", jan.userId());
        scanner.run();
        assertThat(inbox(jan, org).get("totalItems").asInt()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from email_outbox where to_address = ? "
                + "and template = 'NOTIFICATION_DIGEST'", Integer.class, email)).isEqualTo(1);
    }

    @Test
    void unconfirmedAddressesAreNeverEmailed() throws Exception {
        String email = "unverified-" + System.nanoTime() + "@notif.test";
        Session piotr = api.registerVerifyLogin(email);
        String org = org(piotr);
        jdbc.update("update users set email_verified_at = null where email = ?", email);
        match(org, tender("Budowa parkingu", "PL", "45000000", "2026-12-20T10:00:00Z"), 92);
        scanner.run();
        assertThat(inbox(piotr, org).get("totalItems").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from email_outbox where to_address = ? "
                + "and template = 'NOTIFICATION_DIGEST'", Integer.class, email)).isZero();
    }
}
