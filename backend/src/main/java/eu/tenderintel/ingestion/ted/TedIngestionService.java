package eu.tenderintel.ingestion.ted;

import eu.tenderintel.common.id.Ids;
import eu.tenderintel.common.id.Tokens;
import eu.tenderintel.config.AppProperties;
import eu.tenderintel.ingestion.IngestionJobs;
import eu.tenderintel.ingestion.NormalizedNotice;
import eu.tenderintel.ingestion.TenderUpsertService;
import eu.tenderintel.ingestion.TenderUpsertService.Outcome;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * Fetches TED notices for a publication-date window and feeds them through parsing and {@link TenderUpsertService}.
 * One bad notice never stops the run: it is counted, a sample of errors is kept in the job, and the run ends as
 * PARTIAL. Re-running the same window is cheap and idempotent (already ingested notices are skipped).
 */
@Service
public class TedIngestionService {

    public static final String SOURCE_CODE = "TED";
    static final int MAX_ERROR_SAMPLES = 20;
    private static final Logger log = LoggerFactory.getLogger(TedIngestionService.class);
    private static final DateTimeFormatter TED_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final TedSearchClient client;
    private final TenderUpsertService upserts;
    private final IngestionJobs jobs;
    private final JdbcTemplate jdbc;
    private final AppProperties.Ted props;

    TedIngestionService(TedSearchClient client, TenderUpsertService upserts, IngestionJobs jobs, JdbcTemplate jdbc,
            AppProperties props) {
        this.client = client;
        this.upserts = upserts;
        this.jobs = jobs;
        this.jdbc = jdbc;
        this.props = props.ted();
    }

    public record Counters(int fetched, int created, int updated, int skipped, int failed, List<String> errors) {
    }

    /** Ingests notices published between {@code from} and {@code to} (inclusive), tracked as job {@code jobId}. */
    public Counters ingest(LocalDate from, LocalDate to, UUID jobId) {
        UUID sourceId = jdbc.queryForObject("select id from data_sources where code = ?", UUID.class, SOURCE_CODE);
        String query = "publication-date>=" + from.format(TED_DATE) + " AND publication-date<=" + to.format(TED_DATE);
        int fetched = 0;
        int created = 0;
        int updated = 0;
        int skipped = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();
        String token = null;
        do {
            TedSearchClient.Page page = client.search(query, token);
            for (JsonNode notice : page.notices()) {
                fetched++;
                String id = notice.path("publication-number").asString("?");
                try {
                    storeRaw(sourceId, id, notice);
                    NormalizedNotice normalized = TedNoticeParser.parse(notice);
                    Outcome outcome = upserts.upsert(normalized, sourceId);
                    switch (outcome) {
                        case CREATED -> created++;
                        case UPDATED, HISTORY_ONLY -> updated++;
                        case SKIPPED -> skipped++;
                    }
                } catch (RuntimeException e) {
                    failed++;
                    if (errors.size() < MAX_ERROR_SAMPLES) {
                        errors.add(id + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                    log.warn("TED notice {} failed", id, e);
                }
            }
            token = page.notices().isEmpty() ? null : page.nextToken();
            jobs.progress(jobId, fetched, created, updated, skipped, failed, token);
            pause();
        } while (token != null);
        return new Counters(fetched, created, updated, skipped, failed, errors);
    }

    /** Raw payload kept for re-normalisation after parser fixes; identical payloads are stored once. */
    private void storeRaw(UUID sourceId, String externalId, JsonNode notice) {
        String payload = notice.toString();
        jdbc.update("""
                insert into raw_notices (id, data_source_id, external_id, payload_hash, payload)
                values (?, ?, ?, ?, ?::jsonb)
                on conflict (data_source_id, external_id, payload_hash) do nothing
                """, Ids.newId(), sourceId, externalId, Tokens.sha256(payload), payload);
    }

    private void pause() {
        try {
            Thread.sleep(props.pauseBetweenPages().toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
