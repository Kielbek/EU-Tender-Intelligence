package eu.tenderintel.ingestion.bzp;

import eu.tenderintel.common.error.ApiException;
import eu.tenderintel.config.AppProperties;
import eu.tenderintel.ingestion.IngestionJobs;
import eu.tenderintel.ingestion.TenderStatusMaintenance;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.time.LocalDate;
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
 * Scheduling of BZP imports: hourly incremental runs (BZP publishes around the clock on working days), an initial
 * backfill on first start and admin-triggered backfills. One active job per source (enforced by the database).
 */
@Service
public class BzpIngestionJobs {

    private static final Logger log = LoggerFactory.getLogger(BzpIngestionJobs.class);
    private static final int ERROR_THRESHOLD = 3;

    private final BzpIngestionService ingestion;
    private final IngestionJobs jobs;
    private final TenderStatusMaintenance maintenance;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final Executor executor;
    private final AppProperties.Bzp props;
    private final Clock clock;

    BzpIngestionJobs(BzpIngestionService ingestion, IngestionJobs jobs, TenderStatusMaintenance maintenance,
            JdbcTemplate jdbc, JsonMapper json, @Qualifier("applicationTaskExecutor") Executor executor,
            AppProperties props, Clock clock) {
        this.ingestion = ingestion;
        this.jobs = jobs;
        this.maintenance = maintenance;
        this.jdbc = jdbc;
        this.json = json;
        this.executor = executor;
        this.props = props.bzp();
        this.clock = clock;
    }

    public UUID startWindow(LocalDate from, LocalDate to, String triggeredBy) {
        LocalDate today = LocalDate.now(clock.withZone(BzpNoticeParser.WARSAW));
        if (from.isAfter(to) || to.isAfter(today)) {
            throw ApiException.badRequest("invalid_window", "Expected from <= to <= today");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > props.maxBackfillDays()) {
            throw ApiException.badRequest("window_too_large",
                    "At most " + props.maxBackfillDays() + " days per job; split longer backfills");
        }
        UUID jobId = jobs.create(BzpIngestionService.SOURCE_CODE, IngestionJobs.Type.BACKFILL, triggeredBy);
        executor.execute(() -> run(jobId, from, to));
        return jobId;
    }

    public UUID retry(UUID previousJobId) {
        LocalDate[] w = jobs.retryWindow(previousJobId, BzpNoticeParser.WARSAW);
        return startWindow(w[0], w[1], "retry:" + previousJobId);
    }

    /** Incremental: from the day of the last successful window end up to today (re-processing is skipped cheaply). */
    @Scheduled(cron = "${app.bzp.cron:0 35 * * * *}", zone = "Europe/Warsaw")
    public void scheduledIncremental() {
        if (!props.scheduleEnabled()) {
            return;
        }
        LocalDate today = LocalDate.now(clock.withZone(BzpNoticeParser.WARSAW));
        LocalDate lastEnd = jdbc.query("""
                select max(j.window_to) from ingestion_jobs j join data_sources d on d.id = j.data_source_id
                where d.code = ? and j.status in ('SUCCEEDED', 'PARTIAL')
                """, rs -> rs.next() && rs.getTimestamp(1) != null
                ? rs.getTimestamp(1).toInstant().atZone(BzpNoticeParser.WARSAW).toLocalDate() : null,
                BzpIngestionService.SOURCE_CODE);
        LocalDate from = lastEnd == null ? today.minusDays(Math.max(0, props.initialBackfillDays() - 1)) : lastEnd;
        if (ChronoUnit.DAYS.between(from, today) + 1 > props.maxBackfillDays()) {
            from = today.minusDays(props.maxBackfillDays() - 1L);
        }
        try {
            UUID jobId = jobs.create(BzpIngestionService.SOURCE_CODE, IngestionJobs.Type.INCREMENTAL, "schedule");
            LocalDate windowFrom = from;
            Map<String, Instant> resumeAt = ingestion.lastReceived();
            executor.execute(() -> run(jobId, windowFrom, today, resumeAt));
        } catch (ApiException e) {
            log.info("Skipping scheduled BZP run: {}", e.getMessage());
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
                """, Integer.class, BzpIngestionService.SOURCE_CODE);
        if (done != null && done == 0) {
            LocalDate today = LocalDate.now(clock.withZone(BzpNoticeParser.WARSAW));
            try {
                startWindow(today.minusDays(props.initialBackfillDays() - 1L), today, "startup");
            } catch (ApiException e) {
                log.info("Initial BZP backfill not started: {}", e.getMessage());
            }
        }
    }

    void run(UUID jobId, LocalDate from, LocalDate to) {
        run(jobId, from, to, Map.of());
    }

    void run(UUID jobId, LocalDate from, LocalDate to, Map<String, Instant> resumeAt) {
        jobs.markRunning(jobId);
        jobs.markWindow(jobId, from.atStartOfDay(BzpNoticeParser.WARSAW).toInstant(),
                to.plusDays(1).atStartOfDay(BzpNoticeParser.WARSAW).toInstant().minusSeconds(1));
        try {
            BzpIngestionService.Counters c = ingestion.ingest(from, to, jobId, resumeAt);
            jobs.markFinished(jobId, c.fetched(), c.created(), c.updated(), c.skipped(), c.failed(),
                    json.writeValueAsString(c.errors()));
            maintenance.closeExpired();
            log.info("BZP ingestion {}..{}: fetched {}, created {}, updated {}, skipped {}, failed {}", from, to,
                    c.fetched(), c.created(), c.updated(), c.skipped(), c.failed());
        } catch (RuntimeException e) {
            log.error("BZP ingestion {}..{} failed", from, to, e);
            jobs.markFailed(jobId, e.getClass().getSimpleName() + ": " + e.getMessage());
            if (jobs.consecutiveFailures(BzpIngestionService.SOURCE_CODE) >= ERROR_THRESHOLD) {
                jobs.markSourceStatus(BzpIngestionService.SOURCE_CODE, "ERROR");
            }
        }
    }
}
