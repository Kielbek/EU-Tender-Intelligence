package eu.tenderintel.reference;

import eu.tenderintel.audit.AuditAction;
import eu.tenderintel.audit.AuditService;
import eu.tenderintel.ingestion.IngestionJobs;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Runs reference-data imports as tracked background jobs (visible in {@code ingestion_jobs}). Triggered by a platform
 * admin, by a schedule (ECB rates on working days after publication) or on first start when tables are empty.
 */
@Service
public class ReferenceImportJobs {

    private static final Logger log = LoggerFactory.getLogger(ReferenceImportJobs.class);

    private final CpvImportService cpv;
    private final EcbRatesImportService ecb;
    private final IngestionJobs jobs;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final Executor executor;
    private final boolean importOnStartup;

    ReferenceImportJobs(CpvImportService cpv, EcbRatesImportService ecb, IngestionJobs jobs, AuditService audit,
            JdbcTemplate jdbc, @Qualifier("applicationTaskExecutor") Executor executor,
            eu.tenderintel.config.AppProperties props) {
        this.cpv = cpv;
        this.ecb = ecb;
        this.jobs = jobs;
        this.audit = audit;
        this.jdbc = jdbc;
        this.executor = executor;
        this.importOnStartup = props.reference().importOnStartup();
    }

    public UUID startCpvImport(String triggeredBy, UUID actorId) {
        return start("EU_PUBLICATIONS_CPV", triggeredBy, actorId, () -> {
            CpvImportService.Result r = cpv.importAll();
            return new int[] {r.codes(), r.codes()};
        });
    }

    public UUID startExchangeRatesImport(String triggeredBy, UUID actorId, boolean last90Days) {
        return start("ECB_FX", triggeredBy, actorId, () -> {
            EcbRatesImportService.Result r = last90Days ? ecb.importLast90Days() : ecb.importDaily();
            return new int[] {r.fetched(), r.stored()};
        });
    }

    /** ECB publishes reference rates around 16:00 CET on TARGET working days. */
    @Scheduled(cron = "0 30 16 * * MON-FRI", zone = "Europe/Berlin")
    void scheduledDailyRates() {
        startExchangeRatesImport("schedule", null, false);
    }

    @EventListener(ApplicationReadyEvent.class)
    void importMissingReferenceData() {
        if (!importOnStartup) {
            return;
        }
        Integer cpvCount = jdbc.queryForObject("select count(*) from cpv_codes", Integer.class);
        if (cpvCount != null && cpvCount == 0) {
            startCpvImport("startup", null);
        }
        Integer rateCount = jdbc.queryForObject("select count(*) from exchange_rates", Integer.class);
        if (rateCount != null && rateCount == 0) {
            startExchangeRatesImport("startup", null, true);
        }
    }

    private UUID start(String source, String triggeredBy, UUID actorId, Supplier<int[]> work) {
        UUID jobId = jobs.create(source, IngestionJobs.Type.REFERENCE, triggeredBy);
        executor.execute(() -> {
            jobs.markRunning(jobId);
            try {
                int[] counts = work.get();
                jobs.markSucceeded(jobId, counts[0], counts[1], 0, 0);
                audit.record(AuditAction.REFERENCE_DATA_IMPORTED, actorId, null, "data_source", source,
                        Map.of("jobId", jobId.toString(), "stored", counts[1]));
            } catch (RuntimeException ex) {
                log.error("Reference import {} failed", source, ex);
                jobs.markFailed(jobId, ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }
        });
        return jobId;
    }
}
