package eu.tenderintel.ingestion.bzp;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import eu.tenderintel.IntegrationTest;
import eu.tenderintel.ingestion.IngestionJobs;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * BZP import end to end against a stub of the notice API that behaves like the real one: filtered by notice type,
 * sorted by publication time, PublicationDateFrom inclusive, PageSize limit (2 here, to exercise the sliding window).
 */
@IntegrationTest
class BzpIngestionIntegrationTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final List<JsonNode> NOTICES = new CopyOnWriteArrayList<>();
    static final List<String> REQUESTS = new CopyOnWriteArrayList<>();
    static JsonNode samples;
    static HttpServer server;

    @DynamicPropertySource
    static void stub(DynamicPropertyRegistry registry) throws Exception {
        try (InputStream in = BzpIngestionIntegrationTest.class.getResourceAsStream("/bzp/notices.json")) {
            samples = JSON.readTree(in);
        }
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mo-board/api/v1/notice", exchange -> {
            Map<String, String> q = new HashMap<>();
            for (String kv : exchange.getRequestURI().getRawQuery().split("&")) {
                String[] p = kv.split("=", 2);
                q.put(p[0], URLDecoder.decode(p[1], StandardCharsets.UTF_8));
            }
            REQUESTS.add(exchange.getRequestURI().getRawQuery());
            Instant from = q.get("PublicationDateFrom").contains("T") ? Instant.parse(q.get("PublicationDateFrom"))
                    : LocalDate.parse(q.get("PublicationDateFrom")).atStartOfDay(BzpNoticeParser.WARSAW).toInstant();
            int size = Integer.parseInt(q.get("PageSize"));
            List<JsonNode> page = NOTICES.stream()
                    .filter(n -> n.get("noticeType").asString().equals(q.get("NoticeType")))
                    .filter(n -> !Instant.parse(n.get("publicationDate").asString()).isBefore(from))
                    .sorted((a, b) -> a.get("publicationDate").asString().compareTo(b.get("publicationDate").asString()))
                    .limit(size).toList();
            byte[] bytes = JSON.writeValueAsString(page).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        registry.add("app.bzp.search-url",
                () -> "http://127.0.0.1:" + server.getAddress().getPort() + "/mo-board/api/v1/notice");
        registry.add("app.bzp.page-size", () -> "2");
    }

    @Autowired
    BzpIngestionService ingestion;
    @Autowired
    IngestionJobs jobs;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        NOTICES.clear();
        REQUESTS.clear();
        jdbc.update("delete from tenders where procedure_key like 'bzp:%'");
    }

    private ObjectNode sample(String name) {
        return (ObjectNode) samples.get(name).deepCopy();
    }

    private ObjectNode copy(String name, String number, String objectId, String published) {
        ObjectNode n = sample(name);
        n.put("noticeNumber", number);
        n.put("objectId", objectId);
        n.put("publicationDate", published);
        return n;
    }

    private BzpIngestionService.Counters run() {
        UUID job = jobs.create(BzpIngestionService.SOURCE_CODE, IngestionJobs.Type.BACKFILL, "test");
        BzpIngestionService.Counters c = ingestion.ingest(LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 5), job);
        jobs.markFinished(job, c.fetched(), c.created(), c.updated(), c.skipped(), c.failed(), "[]");
        return c;
    }

    @Test
    void importsMergesChangesAndIsIdempotent() {
        ObjectNode contract = sample("contractSingle");
        String procedure = "bzp:" + contract.get("tenderId").asString();
        NOTICES.add(contract);
        // Two more contract notices at the same second as each other – the window must still page past them.
        NOTICES.add(copy("contractMultiPart", "2026/BZP 00600001/01", "obj-1", "2026-11-05T10:00:00Z"));
        NOTICES.add(copy("contractMultiPart", "2026/BZP 00600002/01", "obj-2", "2026-11-05T10:00:00Z"));
        ((ObjectNode) NOTICES.get(2)).put("tenderId", "ocds-148610-test-second");
        // A change notice for the single-part procedure, moving its deadline.
        ObjectNode change = sample("updateDeadline");
        change.put("tenderId", contract.get("tenderId").asString());
        NOTICES.add(change);

        BzpIngestionService.Counters c = run();
        assertThat(c.failed()).as(String.join("; ", c.errors())).isZero();
        assertThat(c.fetched()).isEqualTo(4);
        assertThat(c.created()).isEqualTo(3);
        assertThat(c.updated()).isEqualTo(1);

        Map<String, Object> t = jdbc.queryForMap("""
                select id, title, status, submission_deadline, country_code, contract_type, procedure_type,
                       current_version_no, documents_url, original_url
                from tenders where procedure_key = ?
                """, procedure);
        assertThat(t.get("country_code")).isEqualTo("PL");
        assertThat(t.get("procedure_type")).isEqualTo("pl-basic");
        assertThat(t.get("current_version_no")).isEqualTo(2);
        assertThat(((java.sql.Timestamp) t.get("submission_deadline")).toInstant())
                .isEqualTo(Instant.parse("2026-11-10T08:30:00Z"));
        assertThat((String) t.get("original_url")).startsWith(BzpNoticeParser.NOTICE_URL);
        assertThat(jdbc.queryForObject("select count(*) from tender_versions where tender_id = ?", Integer.class,
                t.get("id"))).isEqualTo(2);
        // The full notice text is kept (without CSS) for AI analysis.
        String raw = jdbc.queryForObject("""
                select r.payload->>'htmlBody' from raw_notices r join data_sources d on d.id = r.data_source_id
                where d.code = 'PL_EZAMOWIENIA' and r.external_id = ?
                """, String.class, contract.get("noticeNumber").asString());
        assertThat(raw).contains("SEKCJA").doesNotContain("<style");
        // Sliding window: more than one page was requested for contract notices.
        assertThat(REQUESTS.stream().filter(r -> r.contains("NoticeType=ContractNotice")).count()).isGreaterThan(1);

        // Second run: nothing new.
        BzpIngestionService.Counters again = run();
        assertThat(again.created()).isZero();
        assertThat(again.skipped()).isEqualTo(4);
        assertThat(jdbc.queryForObject("select status from data_sources where code = 'PL_EZAMOWIENIA'",
                String.class)).isEqualTo("ACTIVE");
    }

    @Test
    void resultsAwardAndCancellationsClose() {
        ObjectNode award = sample("resultAward");
        ObjectNode cancel = sample("resultCancelled");
        NOTICES.add(award);
        NOTICES.add(cancel);
        BzpIngestionService.Counters c = run();
        assertThat(c.failed()).as(String.join("; ", c.errors())).isZero();
        assertThat(jdbc.queryForObject("select status from tenders where procedure_key = ?", String.class,
                "bzp:" + award.get("tenderId").asString())).isEqualTo("AWARDED");
        assertThat(jdbc.queryForObject("select status from tenders where procedure_key = ?", String.class,
                "bzp:" + cancel.get("tenderId").asString())).isEqualTo("CANCELLED");
        List<String> winners = new ArrayList<>(jdbc.queryForList("""
                select o.name from tender_results r join economic_operators o on o.id = r.winner_id
                join tenders t on t.id = r.tender_id where t.procedure_key = ? order by o.name
                """, String.class, "bzp:" + award.get("tenderId").asString()));
        assertThat(winners).contains("Asclepios S.A.", "Janssen-Cilag Polska Sp. z o.o.");
    }
}
