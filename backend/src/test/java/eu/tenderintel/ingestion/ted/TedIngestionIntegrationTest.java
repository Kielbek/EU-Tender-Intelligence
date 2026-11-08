package eu.tenderintel.ingestion.ted;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.sun.net.httpserver.HttpServer;
import eu.tenderintel.IntegrationTest;
import eu.tenderintel.TestApi;
import eu.tenderintel.common.error.ApiException;
import eu.tenderintel.config.AppProperties;
import eu.tenderintel.ingestion.ChangeKind;
import eu.tenderintel.ingestion.IngestionJobs;
import eu.tenderintel.ingestion.TenderIngestedEvent;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * End-to-end ingestion against a stub of the TED Search API serving real notices (and derived variants for change,
 * out-of-order, award and cancellation scenarios).
 */
@IntegrationTest
@RecordApplicationEvents
class TedIngestionIntegrationTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final LocalDate DAY = LocalDate.of(2026, 11, 6);
    static final String CKE = "eforms:3cd92e09-232d-4bfb-bd55-08c952779087";

    /** Pages the stub returns, in order; each request returns the next page. */
    static final List<List<JsonNode>> PAGES = new CopyOnWriteArrayList<>();
    static final List<String> REQUESTS = new CopyOnWriteArrayList<>();
    static final AtomicInteger PAGE = new AtomicInteger();
    static final AtomicInteger FAIL_FIRST = new AtomicInteger();
    static HttpServer server;
    static JsonNode sample;

    @DynamicPropertySource
    static void stubTed(DynamicPropertyRegistry registry) throws Exception {
        try (InputStream in = TedIngestionIntegrationTest.class.getResourceAsStream("/ted/sample-2026-11-06.json")) {
            sample = JSON.readTree(in);
        }
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v3/notices/search", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            REQUESTS.add(body);
            byte[] bytes;
            int status = 200;
            if (FAIL_FIRST.getAndDecrement() > 0) {
                status = 429;
                bytes = "{\"message\":\"Too many requests\"}".getBytes(StandardCharsets.UTF_8);
            } else {
                int i = PAGE.getAndIncrement();
                List<JsonNode> page = i < PAGES.size() ? PAGES.get(i) : List.of();
                ObjectNode res = JSON.createObjectNode();
                ArrayNode notices = res.putArray("notices");
                page.forEach(notices::add);
                res.put("totalNoticeCount", PAGES.stream().mapToInt(List::size).sum());
                if (i + 1 < PAGES.size()) {
                    res.put("iterationNextToken", "token-" + (i + 1));
                }
                res.put("timedOut", false);
                bytes = JSON.writeValueAsString(res).getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        registry.add("app.ted.search-url",
                () -> "http://127.0.0.1:" + server.getAddress().getPort() + "/v3/notices/search");
    }

    @Autowired
    TedIngestionJobs tedJobs;
    @Autowired
    IngestionJobs jobs;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ApplicationEvents events;
    @Autowired
    MockMvc mvc;
    @Autowired
    TestApi api;

    @BeforeEach
    void reset() {
        PAGES.clear();
        REQUESTS.clear();
        PAGE.set(0);
        FAIL_FIRST.set(0);
        jdbc.update("delete from tenders");
        jdbc.update("delete from raw_notices");
        jdbc.update("delete from ingestion_jobs");
        jdbc.update("""
                insert into exchange_rates (currency, rate_date, rate_per_eur) values ('PLN', '2026-11-06', 4.3925)
                on conflict (currency, rate_date) do update set rate_per_eur = excluded.rate_per_eur
                """);
    }

    private Map<String, Object> runDay() {
        UUID job = jobs.create(TedIngestionService.SOURCE_CODE, IngestionJobs.Type.BACKFILL, "test");
        tedJobs.run(job, DAY, DAY);
        return jdbc.queryForMap("select * from ingestion_jobs where id = ?", job);
    }

    private static ObjectNode copy(String key) {
        return (ObjectNode) sample.get(key).deepCopy();
    }

    private static List<JsonNode> allSamples() {
        List<JsonNode> list = new ArrayList<>();
        sample.properties().forEach(e -> list.add(e.getValue()));
        return list;
    }

    @Test
    void ingestsRealNoticesAcrossPagesIdempotently() throws Exception {
        List<JsonNode> all = allSamples(); // 14 entries, one notice appears twice (norway / utc_deadline)
        PAGES.add(all.subList(0, 7));
        PAGES.add(all.subList(7, 14));

        Map<String, Object> job = runDay();
        assertThat(job.get("status")).isEqualTo("SUCCEEDED");
        assertThat(job.get("fetched_count")).isEqualTo(14);
        assertThat(job.get("created_count")).isEqualTo(13);
        assertThat(job.get("skipped_count")).isEqualTo(1);
        assertThat(job.get("error_count")).isEqualTo(0);

        // Request contract: date window, ITERATION paging with the token of the previous page, field budget.
        JsonNode firstRequest = JSON.readTree(REQUESTS.getFirst());
        assertThat(firstRequest.get("query").asString())
                .isEqualTo("publication-date>=20261106 AND publication-date<=20261106");
        assertThat(firstRequest.get("paginationMode").asString()).isEqualTo("ITERATION");
        assertThat(firstRequest.get("fields").size() * firstRequest.get("limit").asInt()).isLessThanOrEqualTo(10_000);
        assertThat(JSON.readTree(REQUESTS.get(1)).get("iterationNextToken").asString()).isEqualTo("token-1");

        Map<String, Object> cke = jdbc.queryForMap("select * from tenders where procedure_key = ?", CKE);
        assertThat(cke.get("country_code")).isEqualTo("PL");
        assertThat(cke.get("currency")).isEqualTo("PLN");
        assertThat((BigDecimal) cke.get("estimated_value_eur")).isEqualByComparingTo(
                new BigDecimal("367994.72").divide(new BigDecimal("4.3925"), 2, RoundingMode.HALF_UP));
        String expectedStatus = Instant.parse("2026-12-08T08:00:00Z").isAfter(Instant.now()) ? "OPEN" : "CLOSED";
        assertThat(cke.get("status")).isEqualTo(expectedStatus);
        assertThat(jdbc.queryForObject("select name from contracting_authorities where id = ?", String.class,
                cke.get("contracting_authority_id"))).isEqualTo("Centralna Komisja Egzaminacyjna");

        // Non-EU buyer country registered on demand; currency without ECB rate keeps the value but no EUR amount.
        assertThat(jdbc.queryForObject("select name_en from countries where code = 'NO'", String.class))
                .isEqualTo("Norway");
        assertThat(jdbc.queryForObject("select is_eu from countries where code = 'NO'", Boolean.class)).isFalse();
        Map<String, Object> cop = jdbc.queryForMap("select currency, estimated_value, estimated_value_eur from tenders "
                + "where title like 'Servicios de agencia de viajes%'");
        assertThat(cop.get("currency")).isEqualTo("COP");
        assertThat(cop.get("estimated_value_eur")).isNull();

        // Winners and result data.
        assertThat(jdbc.queryForObject("""
                select count(*) from tender_results r join economic_operators o on o.id = r.winner_id
                where o.name = 'WSP SPAIN-APIA S.A.U.' and r.awarded_value = 201070.03 and r.tenders_received = 3
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from tender_lots", Integer.class)).isEqualTo(29);
        assertThat(jdbc.queryForObject("select count(*) from raw_notices", Integer.class)).isEqualTo(13);

        // Second run of the same window: nothing new.
        PAGE.set(0);
        Map<String, Object> rerun = runDay();
        assertThat(rerun.get("created_count")).isEqualTo(0);
        assertThat(rerun.get("skipped_count")).isEqualTo(14);
        assertThat(jdbc.queryForObject("select count(*) from tenders", Integer.class)).isEqualTo(13);

        // Data reaches the search API.
        TestApi.Session session = api.registerVerifyLogin("ted-" + System.nanoTime() + "@ingest.test");
        mvc.perform(get("/api/v1/tenders").param("country", "PL").param("q", "matematycznych").with(session.bearer()))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].authorityName").value("Centralna Komisja Egzaminacyjna"));
        mvc.perform(get("/api/v1/tenders").param("country", "NO").with(session.bearer()))
                .andExpect(jsonPath("$.totalItems").value(2));
    }

    @Test
    void changeNoticeUpdatesTenderRecordsDiffAndPublishesEvent() {
        PAGES.add(List.of(sample.get("cn_pl_single")));
        runDay();

        ObjectNode change = copy("cn_pl_single");
        change.put("publication-number", "780001-2026");
        change.put("change-reason-code", "cor-buy");
        change.put("change-notice-version-identifier", "00000000-test-change-01");
        change.set("deadline-receipt-tender-date-lot", JSON.createArrayNode().add("2027-01-15+01:00"));
        change.set("deadline-receipt-tender-time-lot", JSON.createArrayNode().add("10:00:00+01:00"));
        change.put("estimated-value-proc", "400000.00");
        PAGES.clear();
        PAGES.add(List.of(change));
        PAGE.set(0);
        events.clear();
        Map<String, Object> job = runDay();
        assertThat(job.get("updated_count")).isEqualTo(1);

        Map<String, Object> t = jdbc.queryForMap("select * from tenders where procedure_key = ?", CKE);
        assertThat(t.get("current_version_no")).isEqualTo(2);
        assertThat(((java.sql.Timestamp) t.get("submission_deadline")).toInstant())
                .isEqualTo(Instant.parse("2027-01-15T09:00:00Z"));
        assertThat((BigDecimal) t.get("estimated_value")).isEqualByComparingTo("400000.00");

        Map<String, Object> v2 = jdbc.queryForMap(
                "select change_kind, diff::text as diff from tender_versions where tender_id = ? and version_no = 2",
                t.get("id"));
        assertThat(v2.get("change_kind")).isEqualTo("CHANGE");
        assertThat((String) v2.get("diff")).contains("\"field\": \"submissionDeadline\"")
                .contains("\"old\": \"2026-12-08T08:00:00Z\"").contains("\"new\": \"2027-01-15T09:00:00Z\"")
                .contains("\"field\": \"estimatedValue\"").doesNotContain("\"field\": \"title\"")
                .doesNotContain("\"field\": \"cpvCodes\"");

        assertThat(events.stream(TenderIngestedEvent.class)).singleElement().satisfies(e -> {
            assertThat(e.created()).isFalse();
            assertThat(e.changeKind()).isEqualTo(ChangeKind.CHANGE);
            assertThat(e.changedFields()).contains("submissionDeadline", "estimatedValue");
        });

        // An older notice of the same procedure arriving late is kept in history but does not override newer data.
        ObjectNode older = copy("cn_pl_single");
        older.put("publication-number", "760000-2026");
        older.put("estimated-value-proc", "1.00");
        PAGES.clear();
        PAGES.add(List.of(older));
        PAGE.set(0);
        runDay();
        Map<String, Object> after = jdbc.queryForMap("select * from tenders where procedure_key = ?", CKE);
        assertThat((BigDecimal) after.get("estimated_value")).isEqualByComparingTo("400000.00");
        assertThat(after.get("current_version_no")).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from tender_versions where tender_id = ?", Integer.class,
                after.get("id"))).isEqualTo(3);

        // Award for the same procedure: status AWARDED, winner stored, deadline kept from competition notice.
        ObjectNode award = copy("can_aligned");
        award.put("publication-number", "790001-2026");
        award.put("procedure-identifier", "3cd92e09-232d-4bfb-bd55-08c952779087");
        PAGES.clear();
        PAGES.add(List.of(award));
        PAGE.set(0);
        runDay();
        Map<String, Object> awarded = jdbc.queryForMap("select * from tenders where procedure_key = ?", CKE);
        assertThat(awarded.get("status")).isEqualTo("AWARDED");
        assertThat(((java.sql.Timestamp) awarded.get("submission_deadline")).toInstant())
                .isEqualTo(Instant.parse("2027-01-15T09:00:00Z"));
        assertThat(jdbc.queryForObject("select count(*) from tender_results where tender_id = ?", Integer.class,
                awarded.get("id"))).isEqualTo(1);
    }

    @Test
    void cancellationNoticeCancelsTender() {
        ObjectNode original = copy("cn_cancel");
        original.remove("change-reason-code");
        original.remove("change-notice-version-identifier");
        original.put("publication-number", "760002-2026");
        PAGES.add(List.of(original, sample.get("cn_cancel")));
        runDay();
        Map<String, Object> t = jdbc.queryForMap(
                "select status, current_version_no from tenders where procedure_key = 'eforms:ad29682e-3f0e-4ffd-a627-6c1c1cb24a39'");
        assertThat(t.get("status")).isEqualTo("CANCELLED");
        assertThat(t.get("current_version_no")).isEqualTo(2);
    }

    @Test
    void badNoticeIsIsolatedAndJobEndsPartial() {
        ObjectNode broken = copy("cn_pl_single");
        broken.put("publication-number", "not-a-number");
        PAGES.add(List.of(broken, sample.get("pin")));
        Map<String, Object> job = runDay();
        assertThat(job.get("status")).isEqualTo("PARTIAL");
        assertThat(job.get("created_count")).isEqualTo(1);
        assertThat(job.get("error_count")).isEqualTo(1);
        assertThat(job.get("errors").toString()).contains("not-a-number");
    }

    @Test
    void retriesRateLimitingWithBackoffAndFailsFastOnBadRequests() {
        AppProperties.Ted props = new AppProperties.Ted(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v3/notices/search", 200, 0, 31, false,
                Duration.ZERO);
        List<Duration> sleeps = new ArrayList<>();
        TedSearchClient client = new TedSearchClient(RestClient.builder(), JSON, props, sleeps::add);
        PAGES.add(List.of(sample.get("pin")));
        FAIL_FIRST.set(2);
        TedSearchClient.Page page = client.search("publication-date=20261106", null);
        assertThat(page.notices().size()).isEqualTo(1);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(4));

        TedSearchClient badUrl = new TedSearchClient(RestClient.builder(), JSON, new AppProperties.Ted(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/does-not-exist", 200, 0, 31, false,
                Duration.ZERO), sleeps::add);
        sleeps.clear();
        assertThatThrownBy(() -> badUrl.search("x", null)).isInstanceOf(TedSearchClient.TedApiException.class);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void onlyOneActiveJobPerSourceAndWindowValidation() {
        UUID first = jobs.create(TedIngestionService.SOURCE_CODE, IngestionJobs.Type.INCREMENTAL, "test");
        assertThatThrownBy(() -> jobs.create(TedIngestionService.SOURCE_CODE, IngestionJobs.Type.BACKFILL, "test"))
                .isInstanceOf(ApiException.class).hasMessageContaining("already running");
        jobs.markFailed(first, "test");
        assertThatThrownBy(() -> tedJobs.startWindow(DAY, DAY.minusDays(1), "test")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> tedJobs.startWindow(DAY.minusDays(40), DAY, "test"))
                .hasMessageContaining("At most 31 days");
    }
}
