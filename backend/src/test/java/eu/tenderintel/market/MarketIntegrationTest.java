package eu.tenderintel.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import eu.tenderintel.IntegrationTest;
import eu.tenderintel.TestApi;
import eu.tenderintel.TestApi.Session;
import eu.tenderintel.notification.NotificationScanner;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/** Synthetic awards (clearly fake names) in a CPV class of their own: 99xxx-free codes under 45 are not used elsewhere. */
@IntegrationTest
class MarketIntegrationTest {

    static final UUID AUTHORITY = UUID.fromString("00000000-0000-7000-8000-0000000ca001");
    static final UUID ALFA = UUID.fromString("00000000-0000-7000-8000-0000000e0a01");
    static final UUID BETA = UUID.fromString("00000000-0000-7000-8000-0000000e0b01");
    static final UUID GAMMA = UUID.fromString("00000000-0000-7000-8000-0000000e0c01");

    @Autowired
    MockMvc mvc;
    @Autowired
    TestApi api;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MarketRefreshJob refresh;
    @Autowired
    NotificationScanner scanner;

    UUID open;

    @BeforeEach
    void data() {
        jdbc.update("delete from tenders where procedure_key like 'test:market:%'");
        jdbc.update("delete from tender_sources where external_id like 'test-vat-%'");
        jdbc.update("""
                insert into cpv_codes (code, parent_code, level, labels) values
                  ('45000000', null, 1, '{"en":"Construction work","pl":"Roboty budowlane"}'),
                  ('45233000', '45000000', 3, '{"en":"Road works","pl":"Roboty drogowe"}')
                on conflict do nothing
                """);
        jdbc.update("""
                insert into contracting_authorities (id, name, normalized_name, national_id, country_code, city)
                values (?, 'Gmina Testowo', 'gmina testowo', '1234567890', 'SK', 'Testowo') on conflict do nothing
                """, AUTHORITY);
        jdbc.update("""
                insert into economic_operators (id, name, normalized_name, national_id, country_code) values
                  (?, 'Alfa Drogi Test sp. z o.o.', 'alfa drogi test sp. z o.o.', '111-222-33-44', 'SK'),
                  (?, 'Beta Budowa Test S.A.', 'beta budowa test s.a.', null, 'SK'),
                  (?, 'Gamma Test', 'gamma test', null, 'SK')
                on conflict do nothing
                """, ALFA, BETA, GAMMA);
        // Ten awarded road-works tenders in Slovakia (SK keeps them apart from other tests' data).
        for (int i = 0; i < 10; i++) {
            UUID t = tender("Remont drogi nr " + i, 100_000, "AWARDED", i);
            UUID winner = i < 6 ? ALFA : i < 9 ? BETA : GAMMA;
            // Prices 80 %, 85 %, … of the estimate; offers 1..5.
            award(t, winner, 80_000 + i * 5_000, (i % 5) + 1, LocalDate.now().minusDays(10 + i));
        }
        // The same award published twice (TED + national bulletin) counts once.
        UUID dup = tender("Most testowy", 200_000, "AWARDED", 20);
        award(dup, ALFA, 150_000, 3, LocalDate.now().minusDays(5));
        award(dup, ALFA, 150_000, 3, LocalDate.now().minusDays(5));
        // An award notice repeating the final value as the estimate is left out of the price ratio.
        UUID same = tender("Chodnik testowy", 50_000, "AWARDED", 21);
        award(same, BETA, 50_000, 2, LocalDate.now().minusDays(3));
        // Older than the FREE plan's 3 months.
        UUID old = tender("Stara droga", 100_000, "AWARDED", 22);
        award(old, GAMMA, 90_000, 4, LocalDate.now().minusMonths(6));
        open = tender("Przebudowa drogi powiatowej", 300_000, "OPEN", 30);
        refresh.refresh();
    }

    private UUID tender(String title, long estimate, String status, int n) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into tenders (id, procedure_key, title, country_code, contracting_authority_id, contract_type,
                    main_cpv, estimated_value, currency, estimated_value_eur, published_at, status, last_changed_at)
                values (?, ?, ?, 'SK', ?, 'WORKS', '45233000', ?, 'EUR', ?, now() - interval '60 days', ?, now())
                """, id, "test:market:" + n + ":" + id, title, AUTHORITY, estimate, estimate, status);
        return id;
    }

    private void award(UUID tender, UUID winner, long value, int offers, LocalDate date) {
        jdbc.update("""
                insert into tender_results (id, tender_id, winner_id, awarded_value, currency, awarded_value_eur,
                    tenders_received, award_date)
                values (?, ?, ?, ?, 'EUR', ?, ?, ?)
                """, UUID.randomUUID(), tender, winner, value, value, offers, date);
    }

    private String org(Session s) throws Exception {
        return api.readJson(mvc.perform(get("/api/v1/organizations").with(s.bearer())).andReturn().getResponse())
                .get(0).get("id").asString();
    }

    @Test
    void similarAwardsShowWinnersCompetitionAndPrices() throws Exception {
        Session ola = api.registerVerifyLogin("market-" + System.nanoTime() + "@market.test");
        String org = org(ola);
        JsonNode m = api.readJson(mvc.perform(get("/api/v1/organizations/" + org + "/market/tenders/" + open)
                .param("lang", "pl").with(ola.bearer())).andExpect(status().isOk()).andReturn().getResponse());

        // FREE: 3 months – the old award is outside; the duplicate counts once.
        assertThat(m.get("scope").get("months").asInt()).isEqualTo(3);
        assertThat(m.get("scope").get("cpvPrefix").asString()).isEqualTo("45");
        JsonNode s = m.get("stats");
        assertThat(s.get("awardedTenders").asInt()).isEqualTo(12);
        // Offers: 1,2,3,4,5 twice + 3 + 2 → median 3; single-bid share 2/12.
        assertThat(s.get("medianOffers").asDouble()).isEqualTo(3.0);
        assertThat(s.get("singleBidShare").asDouble()).isEqualTo(0.167);
        // Ratios 0.80…1.25 and 0.75; awards equal to the estimate (1.00 – "Chodnik" and i = 4) are not counted.
        assertThat(s.get("priceRatioSample").asInt()).isEqualTo(10);
        assertThat(s.get("priceRatioMedian").asDouble()).isEqualTo(1.0);

        JsonNode top = m.get("topWinners");
        assertThat(top.get(0).get("name").asString()).isEqualTo("Alfa Drogi Test sp. z o.o.");
        assertThat(top.get(0).get("wins").asInt()).isEqualTo(7);
        assertThat(top.get(0).get("totalEur").asLong()).isEqualTo(80_000 + 85_000 + 90_000 + 95_000 + 100_000 + 105_000
                + 150_000);
        assertThat(m.get("recentAwards").get(0).get("title").asString()).isEqualTo("Chodnik testowy");
        assertThat(m.get("authority").get("name").asString()).isEqualTo("Gmina Testowo");

        // A paid plan sees 24 months, including the older award.
        jdbc.update("update organizations set plan_code = 'STARTER' where id = ?::uuid", org);
        mvc.perform(get("/api/v1/organizations/" + org + "/market/tenders/" + open).with(ola.bearer()))
                .andExpect(jsonPath("$.stats.awardedTenders").value(13))
                .andExpect(jsonPath("$.scope.months").value(24));

        // Other organizations' members cannot use this organization's context.
        Session eve = api.registerVerifyLogin("eve-" + System.nanoTime() + "@market.test");
        mvc.perform(get("/api/v1/organizations/" + org + "/market/tenders/" + open).with(eve.bearer()))
                .andExpect(status().isNotFound());
    }

    @Test
    void authorityAndCompanyProfilesAndSearch() throws Exception {
        Session jan = api.registerVerifyLogin("profiles-" + System.nanoTime() + "@market.test");
        String org = org(jan);
        String base = "/api/v1/organizations/" + org + "/market";
        mvc.perform(get(base + "/authorities/" + AUTHORITY).param("lang", "pl").with(jan.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Gmina Testowo"))
                .andExpect(jsonPath("$.openNow").value(1))
                .andExpect(jsonPath("$.stats.awardedTenders").value(12))
                .andExpect(jsonPath("$.topWinners[0].name").value("Alfa Drogi Test sp. z o.o."))
                .andExpect(jsonPath("$.topCpv[0].key").value("45"))
                .andExpect(jsonPath("$.openTenders[0].title").value("Przebudowa drogi powiatowej"));
        mvc.perform(get(base + "/companies/" + ALFA).with(jan.bearer()))
                .andExpect(jsonPath("$.wins").value(7))
                .andExpect(jsonPath("$.topAuthorities[0].name").value("Gmina Testowo"))
                .andExpect(jsonPath("$.countries[0].key").value("SK"))
                .andExpect(jsonPath("$.watched").value(false));
        // By name without diacritics/case, and by tax id written differently.
        mvc.perform(get(base + "/companies").param("q", "ALFA drogi").with(jan.bearer()))
                .andExpect(jsonPath("$[0].id").value(ALFA.toString()))
                .andExpect(jsonPath("$[0].wins").value(7));
        mvc.perform(get(base + "/companies").param("q", "1112223344").with(jan.bearer()))
                .andExpect(jsonPath("$[0].id").value(ALFA.toString()));
        mvc.perform(get(base + "/authorities").param("q", "testowo").with(jan.bearer()))
                .andExpect(jsonPath("$[0].id").value(AUTHORITY.toString()));
        mvc.perform(get(base + "/companies").param("q", "a").with(jan.bearer()))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void followedCompetitorsAreLimitedByPlanAndNotifyAboutNewWins() throws Exception {
        Session ewa = api.registerVerifyLogin("competitors-" + System.nanoTime() + "@market.test");
        String org = org(ewa);
        String base = "/api/v1/organizations/" + org + "/competitors";
        mvc.perform(put(base + "/" + ALFA).with(ewa.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"główny konkurent w regionie\"}")).andExpect(status().isNoContent());
        // FREE follows one competitor.
        mvc.perform(put(base + "/" + BETA).with(ewa.bearer()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("competitor_limit_reached"));
        mvc.perform(get(base).with(ewa.bearer()))
                .andExpect(jsonPath("$.limit").value(1))
                .andExpect(jsonPath("$.items[0].name").value("Alfa Drogi Test sp. z o.o."))
                .andExpect(jsonPath("$.items[0].winsInWindow").value(7))
                .andExpect(jsonPath("$.items[0].note").value("główny konkurent w regionie"));
        mvc.perform(get("/api/v1/organizations/" + org + "/market/companies/" + ALFA).with(ewa.bearer()))
                .andExpect(jsonPath("$.watched").value(true));

        // A new award for the competitor → notification (older history is not reported).
        jdbc.update("delete from notification_cursors");
        jdbc.update("insert into notification_cursors (name, position) values ('scanner', now() - interval '10 minutes')");
        jdbc.update("update watched_companies set created_at = now() - interval '1 hour' where organization_id = ?::uuid",
                org);
        UUID fresh = tender("Nowa obwodnica testowa", 400_000, "AWARDED", 40);
        jdbc.update("""
                insert into tender_results (id, tender_id, winner_id, awarded_value, currency, awarded_value_eur,
                    tenders_received, award_date, created_at)
                values (?, ?, ?, 360000, 'EUR', 360000, 4, current_date, now() - interval '3 minutes')
                """, UUID.randomUUID(), fresh, ALFA);
        scanner.run();
        JsonNode items = api.readJson(mvc.perform(get("/api/v1/organizations/" + org + "/notifications")
                .with(ewa.bearer())).andReturn().getResponse()).get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("type").asString()).isEqualTo("COMPETITOR_WON");
        assertThat(items.get(0).get("company").get("name").asString()).isEqualTo("Alfa Drogi Test sp. z o.o.");
        assertThat(items.get(0).get("tender").get("title").asString()).isEqualTo("Nowa obwodnica testowa");
        assertThat(items.get(0).get("payload").get("valueEur").asInt()).isEqualTo(360000);

        mvc.perform(delete(base + "/" + ALFA).with(ewa.bearer())).andExpect(status().isNoContent());
        mvc.perform(put(base + "/" + BETA).with(ewa.bearer()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void grossBzpPricesAreLeftOutOfThePriceRatio() throws Exception {
        UUID bzp = jdbc.queryForObject("select id from data_sources where code = 'PL_EZAMOWIENIA'", UUID.class);
        UUID target = null;
        for (int i = 0; i < 12; i++) {
            UUID t = UUID.randomUUID();
            jdbc.update("""
                    insert into tenders (id, procedure_key, title, country_code, contract_type, main_cpv, estimated_value,
                        currency, estimated_value_eur, published_at, status, last_changed_at)
                    values (?, ?, 'Dostawa testowa VAT', 'SI', 'SUPPLIES', '45233000', 100000, 'EUR', 100000,
                            now() - interval '20 days', 'AWARDED', now())
                    """, t, "test:market:vat:" + t);
            UUID source = null;
            if (i >= 6) {
                // Half from the Polish bulletin: gross prices 123 % of the net estimate.
                source = UUID.randomUUID();
                jdbc.update("insert into tender_sources (id, tender_id, data_source_id, external_id, notice_type) "
                        + "values (?, ?, ?, ?, 'TenderResultNotice')", source, t, bzp, "test-vat-" + t);
            }
            jdbc.update("""
                    insert into tender_results (id, tender_id, winner_id, awarded_value, currency, awarded_value_eur,
                        tenders_received, award_date, tender_source_id)
                    values (?, ?, ?, ?, 'EUR', ?, 2, current_date - 5, ?)
                    """, UUID.randomUUID(), t, BETA, i < 6 ? 80_000 : 123_000, i < 6 ? 80_000 : 123_000, source);
            target = t;
        }
        refresh.refresh();
        Session vat = api.registerVerifyLogin("vat-" + System.nanoTime() + "@market.test");
        JsonNode s = api.readJson(mvc.perform(get("/api/v1/organizations/" + org(vat) + "/market/tenders/" + target)
                .with(vat.bearer())).andReturn().getResponse()).get("stats");
        assertThat(s.get("awardedTenders").asInt()).isEqualTo(11);
        // Only the six TED awards (0.80) count; the gross BZP prices would have pushed the median up.
        assertThat(s.get("priceRatioSample").asInt()).isEqualTo(6);
        assertThat(s.get("priceRatioMedian").asDouble()).isEqualTo(0.8);
    }
}
