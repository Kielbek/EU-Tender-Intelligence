package eu.tenderintel.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import eu.tenderintel.IntegrationTest;
import eu.tenderintel.TestApi;
import eu.tenderintel.TestApi.Session;
import eu.tenderintel.tender.TenderFixtures;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class WorkspaceIntegrationTest {

    static final String T1 = TenderFixtures.T1;
    static final String T2 = TenderFixtures.T2;

    @Autowired
    MockMvc mvc;
    @Autowired
    TestApi api;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void fixtures() {
        jdbc.update("delete from saved_tenders");
        TenderFixtures.insert(jdbc);
    }

    private String org(Session s) throws Exception {
        return api.readJson(mvc.perform(get("/api/v1/organizations").with(s.bearer())).andReturn().getResponse())
                .get(0).get("id").asString();
    }

    @Test
    void watchListIsSharedInsideTheOrganizationOnly() throws Exception {
        Session alice = api.registerVerifyLogin("alice-" + System.nanoTime() + "@ws.test");
        Session mallory = api.registerVerifyLogin("mallory-" + System.nanoTime() + "@ws.test");
        String org = org(alice);
        String base = "/api/v1/organizations/" + org + "/saved-tenders/";

        mvc.perform(put(base + T1).with(alice.bearer()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WATCHING"))
                .andExpect(jsonPath("$.tender.title").value("Neubau einer Grundschule"));
        mvc.perform(put(base + T1).with(alice.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PREPARING\",\"notes\":\"Referencje z 2024 r.\"}"))
                .andExpect(jsonPath("$.status").value("PREPARING"))
                .andExpect(jsonPath("$.notes").value("Referencje z 2024 r."));
        // Status-only update keeps the notes.
        mvc.perform(put(base + T1).with(alice.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUBMITTED\"}"))
                .andExpect(jsonPath("$.notes").value("Referencje z 2024 r."));
        mvc.perform(put(base + T1).with(alice.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"BOGUS\"}")).andExpect(status().isBadRequest());
        mvc.perform(put(base + "00000000-0000-7000-8000-0000000fffff").with(alice.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNotFound());

        mvc.perform(get("/api/v1/organizations/" + org + "/saved-tenders").param("lang", "pl").with(alice.bearer()))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].tender.mainCpvLabel").value("Works for complete or part construction"));

        // Another tenant can neither read nor write this watch-list.
        mvc.perform(get("/api/v1/organizations/" + org + "/saved-tenders").with(mallory.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(put(base + T2).with(mallory.bearer()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());

        mvc.perform(delete(base + T1).with(alice.bearer())).andExpect(status().isNoContent());
        mvc.perform(get(base + T1).with(alice.bearer())).andExpect(status().isNotFound());
    }

    @Test
    void savedSearchesArePersonalAndValidated() throws Exception {
        Session bob = api.registerVerifyLogin("bob-" + System.nanoTime() + "@ws.test");
        String org = org(bob);
        String base = "/api/v1/organizations/" + org + "/saved-searches";
        String body = """
                {"name":"Budowlane DE > 500k","filters":{"country":["DE"],"cpv":["45000000"],
                 "valueMinEur":500000,"status":["OPEN"],"sort":"DEADLINE"}}
                """;
        String id = api.readJson(mvc.perform(post(base).with(bob.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isCreated()).andReturn().getResponse()).get("id").asString();
        mvc.perform(post(base).with(bob.bearer()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("duplicate_name"));
        mvc.perform(post(base).with(bob.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"filters\":{\"page\":3}}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_filters"));
        mvc.perform(post(base).with(bob.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"y\",\"filters\":{\"country\":[\"Germany\"]}}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_filters"));

        mvc.perform(get(base).with(bob.bearer()))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].filters.country[0]").value("DE"))
                .andExpect(jsonPath("$[0].filters.valueMinEur").value(500000));
        mvc.perform(patch(base + "/" + id).with(bob.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Roboty budowlane – Niemcy\"}")).andExpect(jsonPath("$.name")
                .value("Roboty budowlane – Niemcy"));
        mvc.perform(post(base + "/" + id + "/run").with(bob.bearer())).andExpect(jsonPath("$.lastRunAt").exists());

        // A colleague in the same organization does not see Bob's personal searches.
        String token = invite(bob, org, "colleague-" + System.nanoTime() + "@ws.test");
        Session colleague = api.login(lastInvitee, TestApi.PASSWORD);
        mvc.perform(post("/api/v1/invitations/accept").with(colleague.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}")).andExpect(status().isOk());
        mvc.perform(get(base).with(colleague.bearer())).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(delete(base + "/" + id).with(colleague.bearer())).andExpect(status().isNoContent());
        mvc.perform(get(base).with(bob.bearer())).andExpect(jsonPath("$", hasSize(1)));

        mvc.perform(delete(base + "/" + id).with(bob.bearer())).andExpect(status().isNoContent());
        mvc.perform(get(base).with(bob.bearer())).andExpect(jsonPath("$", hasSize(0)));
    }

    String lastInvitee;

    private String invite(Session admin, String org, String email) throws Exception {
        api.registerVerifyLogin(email);
        lastInvitee = email;
        mvc.perform(post("/api/v1/organizations/" + org + "/invitations").with(admin.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"role\":\"TEAM_MEMBER\"}")).andExpect(status().isCreated());
        return api.tokenFromEmail(email, "ORGANIZATION_INVITATION");
    }

    @Test
    void dashboardReflectsRealDataAndWatchList() throws Exception {
        Session carol = api.registerVerifyLogin("carol-" + System.nanoTime() + "@ws.test");
        String org = org(carol);
        mvc.perform(put("/api/v1/organizations/" + org + "/saved-tenders/" + T1).with(carol.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{}"));
        Long open = jdbc.queryForObject("select count(*) from tenders where status = 'OPEN'", Long.class);
        mvc.perform(get("/api/v1/organizations/" + org + "/dashboard").param("lang", "pl").with(carol.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openTenders").value(open.intValue()))
                .andExpect(jsonPath("$.watchedActive").value(1))
                .andExpect(jsonPath("$.watchedValueEur").value(2500000.0))
                .andExpect(jsonPath("$.noticesPerDay", hasSize(30)))
                .andExpect(jsonPath("$.updatesScope").value("WATCHED"))
                .andExpect(jsonPath("$.recentUpdates[0].changeKind").value("CHANGE"))
                .andExpect(jsonPath("$.recentUpdates[0].changedFields[0]").value("submissionDeadline"))
                .andExpect(jsonPath("$.topCpvDivisions[?(@.key=='45000000')].label").value("Roboty budowlane"));
        Session stranger = api.registerVerifyLogin("stranger-" + System.nanoTime() + "@ws.test");
        mvc.perform(get("/api/v1/organizations/" + org + "/dashboard").with(stranger.bearer()))
                .andExpect(status().isNotFound());
    }

    @Test
    void tenderDetailIncludesLabelsLotsAndResults() throws Exception {
        Session dan = api.registerVerifyLogin("dan-" + System.nanoTime() + "@ws.test");
        mvc.perform(get("/api/v1/tenders/" + T1).param("lang", "pl").with(dan.bearer()))
                .andExpect(jsonPath("$.cpvLabels['45200000']").value("Works for complete or part construction"))
                .andExpect(jsonPath("$.lots[0].lotNumber").value("LOT-0001"))
                .andExpect(jsonPath("$.results[0].winnerName").value("Testbau GmbH"))
                .andExpect(jsonPath("$.results[0].tendersReceived").value(4));
    }

    @Test
    void exportsCsvAndXlsxSafely() throws Exception {
        jdbc.update("""
                insert into tenders (id, procedure_key, title, country_code, published_at, status)
                values ('00000000-0000-7000-8000-00000000a009', 'test:9', '=HYPERLINK("http://evil","x"), "quoted"',
                        'PL', '2026-11-06T08:00:00Z', 'OPEN')
                """);
        Session erin = api.registerVerifyLogin("erin-" + System.nanoTime() + "@ws.test");
        MockHttpServletResponse csv = mvc.perform(get("/api/v1/tenders/export").param("country", "PL")
                .param("format", "csv").param("lang", "pl").with(erin.bearer())).andReturn().getResponse();
        assertThat(csv.getStatus()).isEqualTo(200);
        assertThat(csv.getHeader("Content-Disposition")).contains("attachment").contains(".csv");
        assertThat(csv.getHeader("X-Total-Count")).isEqualTo("3");
        String text = new String(csv.getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(text).startsWith("﻿Tytuł,Kraj,");
        assertThat(text).contains("\"'=HYPERLINK(\"\"http://evil\"\",\"\"x\"\"), \"\"quoted\"\"\"");
        assertThat(text.lines().count()).isEqualTo(4);

        MockHttpServletResponse xlsx = mvc.perform(get("/api/v1/tenders/export").param("country", "DE")
                .param("format", "xlsx").with(erin.bearer())).andReturn().getResponse();
        assertThat(xlsx.getContentType()).contains("spreadsheetml");
        try (ReadableWorkbook wb = new ReadableWorkbook(new ByteArrayInputStream(xlsx.getContentAsByteArray()))) {
            List<Row> rows = wb.getFirstSheet().read();
            assertThat(rows).hasSize(2);
            assertThat(rows.get(0).getCellText(0)).isEqualTo("Title");
            assertThat(rows.get(1).getCellText(0)).isEqualTo("Neubau einer Grundschule");
            assertThat(rows.get(1).getCellAsNumber(8)).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("2500000"));
        }
        mvc.perform(get("/api/v1/tenders/export").param("format", "pdf").with(erin.bearer()))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'DATA_EXPORTED' "
                + "and actor_user_id = ?::uuid", Integer.class, erin.userId())).isEqualTo(2);
    }
}
