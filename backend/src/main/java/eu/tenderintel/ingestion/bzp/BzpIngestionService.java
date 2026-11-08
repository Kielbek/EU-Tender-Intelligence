package eu.tenderintel.ingestion.bzp;

import eu.tenderintel.common.id.Ids;
import eu.tenderintel.common.id.Tokens;
import eu.tenderintel.config.AppProperties;
import eu.tenderintel.ingestion.IngestionJobs;
import eu.tenderintel.ingestion.NormalizedNotice;
import eu.tenderintel.ingestion.TenderUpsertService;
import eu.tenderintel.ingestion.TenderUpsertService.Outcome;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Imports BZP notices of a publication-date window: for each notice type, pages through the sliding window, stores
 * the raw notice and upserts it. One bad notice never stops the run (counted, sampled into the job's errors).
 */
@Service
public class BzpIngestionService {

    public static final String SOURCE_CODE = "PL_EZAMOWIENIA";
    static final int MAX_ERROR_SAMPLES = 20;
    private static final Logger log = LoggerFactory.getLogger(BzpIngestionService.class);

    private final BzpClient client;
    private final TenderUpsertService upserts;
    private final IngestionJobs jobs;
    private final JdbcTemplate jdbc;
    private final AppProperties.Bzp props;

    BzpIngestionService(BzpClient client, TenderUpsertService upserts, IngestionJobs jobs, JdbcTemplate jdbc,
            AppProperties props) {
        this.client = client;
        this.upserts = upserts;
        this.jobs = jobs;
        this.jdbc = jdbc;
        this.props = props.bzp();
    }

    public record Counters(int fetched, int created, int updated, int skipped, int failed, List<String> errors) {
    }

    public Counters ingest(LocalDate from, LocalDate to, UUID jobId) {
        return ingest(from, to, jobId, Map.of());
    }

    /**
     * Imports the window. {@code resumeAt} (per notice type) lets incremental runs start at the last notice already
     * received instead of re-downloading the whole day; a small overlap is re-read and skipped.
     */
    public Counters ingest(LocalDate from, LocalDate to, UUID jobId, Map<String, Instant> resumeAt) {
        UUID sourceId = jdbc.queryForObject("select id from data_sources where code = ?", UUID.class, SOURCE_CODE);
        int fetched = 0;
        int created = 0;
        int updated = 0;
        int skipped = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();
        int pageSize = Math.min(BzpClient.MAX_PAGE, Math.max(1, props.pageSize()));
        Instant windowStart = from.atStartOfDay(BzpNoticeParser.WARSAW).toInstant();
        // Results and changes refer to earlier contract notices: import those first.
        for (String type : BzpNoticeParser.TYPES) {
            Set<String> seen = new HashSet<>();
            Instant resume = resumeAt.get(type);
            Instant cursor = resume != null && resume.minus(RESUME_OVERLAP).isAfter(windowStart)
                    ? resume.minus(RESUME_OVERLAP) : windowStart;
            int size = pageSize;
            while (true) {
                List<JsonNode> page = client.page(type, cursor, to, size);
                int fresh = 0;
                for (JsonNode notice : page) {
                    String id = BzpNoticeParser.text(notice, "objectId");
                    if (id == null || !seen.add(id)) {
                        continue;
                    }
                    fresh++;
                    fetched++;
                    String number = BzpNoticeParser.text(notice, "noticeNumber");
                    try {
                        storeRaw(sourceId, number, notice);
                        NormalizedNotice normalized = BzpNoticeParser.parse(notice);
                        if ("NoticeUpdateNotice".equals(type) && !procedureKnown(normalized.procedureKey())) {
                            // A change to a procedure published before our window: nothing to update. The raw
                            // notice is kept, so a later backfill of the original notice can apply it.
                            skipped++;
                            continue;
                        }
                        Outcome outcome = upserts.upsert(normalized, sourceId);
                        switch (outcome) {
                            case CREATED -> created++;
                            case UPDATED, HISTORY_ONLY -> updated++;
                            case SKIPPED -> skipped++;
                        }
                    } catch (RuntimeException e) {
                        failed++;
                        if (errors.size() < MAX_ERROR_SAMPLES) {
                            errors.add(number + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
                        }
                        log.warn("BZP notice {} failed", number, e);
                    }
                }
                jobs.progress(jobId, fetched, created, updated, skipped, failed, type + "@" + cursor);
                if (page.size() < size) {
                    break; // window exhausted
                }
                Instant last = Instant.parse(BzpNoticeParser.text(page.getLast(), "publicationDate"));
                if (fresh == 0 || last.equals(cursor)) {
                    // A full page that did not move the window: many notices share one timestamp. Read it again
                    // with the largest page the API allows before stepping past that instant.
                    if (size < BzpClient.MAX_PAGE) {
                        size = BzpClient.MAX_PAGE;
                        continue;
                    }
                    log.warn("BZP {}: more than {} notices at {}; stepping past it", type, size, last);
                    last = last.plusNanos(100);
                }
                cursor = last;
                size = pageSize;
                pause();
            }
        }
        return new Counters(fetched, created, updated, skipped, failed, errors);
    }

    /** Overlap re-read before the last received notice (notices can arrive slightly out of order). */
    static final java.time.Duration RESUME_OVERLAP = java.time.Duration.ofMinutes(10);

    private boolean procedureKnown(String procedureKey) {
        Integer n = jdbc.queryForObject("select count(*) from tenders where procedure_key = ?", Integer.class,
                procedureKey);
        return n != null && n > 0;
    }

    /** Publication time of the newest raw notice per type (last two days), for incremental runs. */
    public Map<String, Instant> lastReceived() {
        Map<String, Instant> out = new java.util.HashMap<>();
        jdbc.query("""
                select r.payload->>'noticeType', max((r.payload->>'publicationDate')::timestamptz)
                from raw_notices r join data_sources d on d.id = r.data_source_id
                where d.code = ? and r.fetched_at > now() - interval '2 days'
                group by 1
                """, rs -> {
                    if (rs.getString(1) != null && rs.getTimestamp(2) != null) {
                        out.put(rs.getString(1), rs.getTimestamp(2).toInstant());
                    }
                }, SOURCE_CODE);
        return out;
    }

    /** Raw notice without its embedded CSS (about a tenth of the size), kept for re-parsing and AI analysis. */
    private void storeRaw(UUID sourceId, String externalId, JsonNode notice) {
        JsonNode stored = notice;
        if (notice instanceof ObjectNode o && o.has("htmlBody")) {
            ObjectNode copy = o.deepCopy();
            copy.put("htmlBody", BzpNoticeParser.text(o, "htmlBody") == null ? null
                    : BzpNoticeParser.text(o, "htmlBody").replaceAll("(?is)<style[^>]*>.*?</style>", ""));
            stored = copy;
        }
        String payload = stored.toString();
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
