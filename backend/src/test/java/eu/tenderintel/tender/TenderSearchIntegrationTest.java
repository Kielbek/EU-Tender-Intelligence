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

    static final String T1 = TenderFixtures.T1;
    static final String T2 = TenderFixtures.T2;
    static final String T3 = TenderFixtures.T3;

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
        TenderFixtures.insert(jdbc);
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
