package eu.tenderintel.tender;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import eu.tenderintel.IntegrationTest;
import eu.tenderintel.TestApi;
import eu.tenderintel.TestApi.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** Synthetic test fixtures (clearly fake identifiers) – never used outside tests. */
@IntegrationTest
class TenderSearchIntegrationTest {

    static final String T1 = "00000000-0000-7000-8000-00000000a001";
    static final String T2 = "00000000-0000-7000-8000-00000000a002";
    static final String T3 = "00000000-0000-7000-8000-00000000a003";

    @Autowired
    MockMvc mvc;
    @Autowired
    TestApi api;
    @Autowired
    JdbcTemplate jdbc;

    Session session;

    @BeforeEach
    void fixtures() throws Exception {
        if (session == null) {
            session = api.registerVerifyLogin("search-" + System.nanoTime() + "@tender.test");
        }
        jdbc.update("delete from tenders where procedure_key like 'test:%'");
        jdbc.update("""
                insert into cpv_codes (code, parent_code, level, labels) values
                  ('45000000', null, 1, '{"en":"Construction work","pl":"Roboty budowlane"}'),
                  ('45200000', '45000000', 2, '{"en":"Works for complete or part construction"}'),
                  ('72000000', null, 1, '{"en":"IT services","pl":"Usługi informatyczne"}')
                on conflict do nothing
                """);
        jdbc.update("""
                insert into contracting_authorities (id, name, normalized_name, country_code, city) values
                  ('00000000-0000-7000-8000-00000000c001', 'Stadt Testhausen', 'stadt testhausen', 'DE', 'Testhausen'),
                  ('00000000-0000-7000-8000-00000000c002', 'Gmina Łódź Testowa', 'gmina lodz testowa', 'PL', 'Łódź')
                on conflict do nothing
                """);
        jdbc.update("""
                insert into tenders (id, procedure_key, title, description, country_code, city, contracting_authority_id,
                    contract_type, procedure_type, main_cpv, estimated_value, currency, estimated_value_eur,
                    published_at, submission_deadline, status, notice_language)
                values
                  (?::uuid, 'test:1', 'Neubau einer Grundschule', 'Bauarbeiten für Schulgebäude', 'DE', 'Testhausen',
                   '00000000-0000-7000-8000-00000000c001', 'WORKS', 'OPEN', '45200000', 2500000, 'EUR', 2500000,
                   '2026-11-01T08:00:00Z', '2026-12-10T11:00:00Z', 'OPEN', 'de'),
                  (?::uuid, 'test:2', 'Przebudowa drogi gminnej', 'Roboty drogowe, nawierzchnia', 'PL', 'Łódź',
                   '00000000-0000-7000-8000-00000000c002', 'WORKS', 'OPEN', '45000000', 1000000, 'PLN', 227583.38,
                   '2026-11-03T08:00:00Z', '2026-11-20T09:00:00Z', 'OPEN', 'pl'),
                  (?::uuid, 'test:3', 'Wdrożenie systemu ERP', 'Usługi informatyczne i licencje', 'PL', 'Łódź',
                   '00000000-0000-7000-8000-00000000c002', 'SERVICES', 'OPEN', '72000000', null, null, null,
                   '2026-11-05T08:00:00Z', '2026-12-01T09:00:00Z', 'CANCELLED', 'pl')
                """, T1, T2, T3);
        jdbc.update("insert into tender_cpv (tender_id, cpv_code, is_main) values (?::uuid, '45200000', true)", T1);
        jdbc.update("""
                insert into tender_versions (id, tender_id, version_no, change_kind, snapshot, diff, published_at)
                values ('00000000-0000-7000-8000-00000000b001', ?::uuid, 1, 'COMPETITION', '{}', '[]', '2026-11-01T08:00:00Z'),
                       ('00000000-0000-7000-8000-00000000b002', ?::uuid, 2, 'CHANGE', '{}',
                        '[{"field":"submissionDeadline","old":"2026-12-03T11:00:00Z","new":"2026-12-10T11:00:00Z"}]',
                        '2026-11-04T08:00:00Z')
                """, T1, T1);
    }

    @Test
    void defaultSortIsNewestFirst() throws Exception {
        mvc.perform(get("/api/v1/tenders").with(session.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.items[*].id", contains(T3, T2, T1)));
    }

    @Test
    void filtersByCountryStatusAndValueInEur() throws Exception {
        mvc.perform(get("/api/v1/tenders").param("country", "PL").param("status", "OPEN").with(session.bearer()))
                .andExpect(jsonPath("$.items[*].id", contains(T2)));
        mvc.perform(get("/api/v1/tenders").param("valueMinEur", "500000").with(session.bearer()))
                .andExpect(jsonPath("$.items[*].id", contains(T1)));
        mvc.perform(get("/api/v1/tenders").param("country", "DE").param("country", "PL")
                        .param("contractType", "WORKS").with(session.bearer()))
                .andExpect(jsonPath("$.items[*].id", containsInAnyOrder(T1, T2)));
    }

    @Test
    void cpvDivisionMatchesChildCodes() throws Exception {
        mvc.perform(get("/api/v1/tenders").param("cpv", "45000000").with(session.bearer()))
                .andExpect(jsonPath("$.items[*].id", containsInAnyOrder(T1, T2)));
        mvc.perform(get("/api/v1/tenders").param("cpv", "72").with(session.bearer()))
                .andExpect(jsonPath("$.items[*].id", contains(T3)));
    }

    @Test
    void fullTextSearchIsAccentInsensitive() throws Exception {
        mvc.perform(get("/api/v1/tenders").param("q", "wdrozenie").with(session.bearer()))
                .andExpect(jsonPath("$.items[*].id", contains(T3)));
        mvc.perform(get("/api/v1/tenders").param("q", "Grundschule").with(session.bearer()))
                .andExpect(jsonPath("$.items[*].id", contains(T1)));
        mvc.perform(get("/api/v1/tenders").param("authority", "Łódź").with(session.bearer()))
                .andExpect(jsonPath("$.items[*].id", containsInAnyOrder(T2, T3)));
    }

    @Test
    void sortsByDeadlineAndPaginates() throws Exception {
        mvc.perform(get("/api/v1/tenders").param("sort", "DEADLINE").param("size", "2").with(session.bearer()))
                .andExpect(jsonPath("$.items[*].id", contains(T2, T3)))
                .andExpect(jsonPath("$.totalPages").value(2));
        mvc.perform(get("/api/v1/tenders").param("sort", "DEADLINE").param("size", "2").param("page", "1")
                        .with(session.bearer()))
                .andExpect(jsonPath("$.items[*].id", contains(T1)));
    }

    @Test
    void validatesParameters() throws Exception {
        mvc.perform(get("/api/v1/tenders").param("country", "Germany").with(session.bearer()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/tenders").param("size", "1000").with(session.bearer()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/tenders").param("q", "'; drop table tenders; --").with(session.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test
    void detailAndVersionHistory() throws Exception {
        mvc.perform(get("/api/v1/tenders/" + T1).with(session.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Neubau einer Grundschule"))
                .andExpect(jsonPath("$.authority.name").value("Stadt Testhausen"))
                .andExpect(jsonPath("$.cpvCodes", contains("45200000")));
        mvc.perform(get("/api/v1/tenders/" + T1 + "/versions").with(session.bearer()))
                .andExpect(jsonPath("$[0].changeKind").value("CHANGE"))
                .andExpect(jsonPath("$[0].diff[0].field").value("submissionDeadline"));
        mvc.perform(get("/api/v1/tenders/00000000-0000-7000-8000-0000000fffff").with(session.bearer()))
                .andExpect(status().isNotFound());
    }
}
