package eu.tenderintel.ingestion.ted;

import eu.tenderintel.common.error.ApiException;
import eu.tenderintel.config.AppProperties;
import eu.tenderintel.ingestion.IngestionJobs;
import eu.tenderintel.ingestion.TenderStatusMaintenance;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Scheduling of TED ingestion: incremental runs during TED publication hours, an initial backfill on first start and
 * admin-triggered backfills. Only one TED job can be active at a time (enforced by the database).
 */
@Service
public class TedIngestionJobs {

    /** TED publication dates follow Brussels time. */
    static final ZoneId TED_ZONE = ZoneId.of("Europe/Brussels");
    private static final Logger log = LoggerFactory.getLogger(TedIngestionJobs.class);
    private static final int ERROR_THRESHOLD = 3;

    private final TedIngestionService ingestion;
    private final IngestionJobs jobs;
    private final TenderStatusMaintenance maintenance;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final Executor executor;
    private final AppProperties.Ted props;
    private final Clock clock;

    TedIngestionJobs(TedIngestionService ingestion, IngestionJobs jobs, TenderStatusMaintenance maintenance,
            JdbcTemplate jdbc, JsonMapper json, @Qualifier("applicationTaskExecutor") Executor executor,
            AppProperties props, Clock clock) {
        this.ingestion = ingestion;
        this.jobs = jobs;
        this.maintenance = maintenance;
        this.jdbc = jdbc;
        this.json = json;
        this.executor = executor;
        this.props = props.ted();
        this.clock = clock;
    }

    /** Admin-triggered (or initial) backfill of an explicit publication-date window. */
    public UUID startWindow(LocalDate from, LocalDate to, String triggeredBy) {
        LocalDate today = LocalDate.now(clock.withZone(TED_ZONE));
        if (from.isAfter(to) || to.isAfter(today)) {
            throw ApiException.badRequest("invalid_window", "Expected from <= to <= today");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > props.maxBackfillDays()) {
            throw ApiException.badRequest("window_too_large",
                    "At most " + props.maxBackfillDays() + " days per job; split longer backfills");
        }
        UUID jobId = jobs.create(TedIngestionService.SOURCE_CODE, IngestionJobs.Type.BACKFILL, triggeredBy);
        executor.execute(() -> run(jobId, from, to));
        return jobId;
    }

    /**
     * Incremental run: from the last successful window end (minus one day, because TED publishes in batches) up to
     * today. Re-processing is cheap – already ingested notices are skipped.
     */
    @Scheduled(cron = "${app.ted.cron:0 20 6-21 * * MON-SAT}", zone = "Europe/Brussels")
    public void scheduledIncremental() {
        if (!props.scheduleEnabled()) {
            return;
        }
        LocalDate today = LocalDate.now(clock.withZone(TED_ZONE));
        LocalDate lastEnd = jdbc.query("""
                select max(j.window_to) from ingestion_jobs j join data_sources d on d.id = j.data_source_id
                where d.code = ? and j.status in ('SUCCEEDED', 'PARTIAL')
                """, rs -> rs.next() && rs.getTimestamp(1) != null
                ? rs.getTimestamp(1).toInstant().atZone(TED_ZONE).toLocalDate() : null,
                TedIngestionService.SOURCE_CODE);
        LocalDate from = lastEnd == null ? today.minusDays(Math.max(0, props.initialBackfillDays() - 1))
                : lastEnd.minusDays(1);
        if (ChronoUnit.DAYS.between(from, today) + 1 > props.maxBackfillDays()) {
            from = today.minusDays(props.maxBackfillDays() - 1L);
        }
        try {
            UUID jobId = jobs.create(TedIngestionService.SOURCE_CODE, IngestionJobs.Type.INCREMENTAL, "schedule");
            LocalDate windowFrom = from;
            // Off the scheduler thread, so other schedules (e-mail outbox) are not delayed by a long run.
            executor.execute(() -> run(jobId, windowFrom, today));
        } catch (ApiException e) {
            log.info("Skipping scheduled TED run: {}", e.getMessage());
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    void initialBackfill() {
        if (!props.scheduleEnabled() || props.initialBackfillDays() <= 0) {
            return;
        }
        Integer done = jdbc.queryForObject("""
                select count(*) from ingestion_jobs j join data_sources d on d.id = j.data_source_id
                where d.code = ? and j.status in ('SUCCEEDED', 'PARTIAL', 'RUNNING', 'QUEUED')
                """, Integer.class, TedIngestionService.SOURCE_CODE);
        if (done != null && done == 0) {
            LocalDate today = LocalDate.now(clock.withZone(TED_ZONE));
            try {
                startWindow(today.minusDays(props.initialBackfillDays() - 1L), today, "startup");
            } catch (ApiException e) {
                log.info("Initial TED backfill not started: {}", e.getMessage());
            }
        }
    }

    /** Re-runs the publication-date window of a finished TED job. */
    public UUID retry(UUID previousJobId) {
        var window = jdbc.query("""
                select j.window_from, j.window_to, d.code, j.status from ingestion_jobs j
                join data_sources d on d.id = j.data_source_id where j.id = ?
                """, rs -> rs.next() ? new Object[] {rs.getTimestamp(1), rs.getTimestamp(2), rs.getString(3),
                rs.getString(4)} : null, previousJobId);
        if (window == null) {
            throw ApiException.notFound("Ingestion job");
        }
        if (!TedIngestionService.SOURCE_CODE.equals(window[2]) || window[0] == null || window[1] == null) {
            throw ApiException.badRequest("not_retryable", "Only TED jobs with a recorded window can be retried");
        }
        if ("RUNNING".equals(window[3]) || "QUEUED".equals(window[3])) {
            throw ApiException.conflict("job_already_running", "The job is still running");
        }
        LocalDate from = ((java.sql.Timestamp) window[0]).toInstant().atZone(TED_ZONE).toLocalDate();
        LocalDate to = ((java.sql.Timestamp) window[1]).toInstant().atZone(TED_ZONE).toLocalDate();
        return startWindow(from, to, "retry:" + previousJobId);
    }

    void run(UUID jobId, LocalDate from, LocalDate to) {
        jobs.markRunning(jobId);
        jobs.markWindow(jobId, from.atStartOfDay(TED_ZONE).toInstant(),
                to.plusDays(1).atStartOfDay(TED_ZONE).toInstant().minusSeconds(1));
        try {
            TedIngestionService.Counters c = ingestion.ingest(from, to, jobId);
            jobs.markFinished(jobId, c.fetched(), c.created(), c.updated(), c.skipped(), c.failed(),
                    json.writeValueAsString(c.errors()));
            maintenance.closeExpired();
            log.info("TED ingestion {}..{}: fetched {}, created {}, updated {}, skipped {}, failed {}", from, to,
                    c.fetched(), c.created(), c.updated(), c.skipped(), c.failed());
        } catch (RuntimeException e) {
            log.error("TED ingestion {}..{} failed", from, to, e);
            jobs.markFailed(jobId, e.getClass().getSimpleName() + ": " + e.getMessage());
            if (jobs.consecutiveFailures(TedIngestionService.SOURCE_CODE) >= ERROR_THRESHOLD) {
                jobs.markSourceStatus(TedIngestionService.SOURCE_CODE, "ERROR");
            }
        }
    }
}
