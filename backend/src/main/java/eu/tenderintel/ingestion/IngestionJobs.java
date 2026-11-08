package eu.tenderintel.ingestion;

import eu.tenderintel.common.error.ApiException;
import eu.tenderintel.common.id.Ids;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Bookkeeping of background import jobs ({@code ingestion_jobs}); visible in the admin panel. */
@Service
public class IngestionJobs {

    public enum Type { INCREMENTAL, BACKFILL, DOCUMENTS, REFERENCE }

    private final JdbcTemplate jdbc;

    IngestionJobs(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID create(String sourceCode, Type type, String triggeredBy) {
        UUID sourceId = jdbc.query("select id from data_sources where code = ?",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null, sourceCode);
        if (sourceId == null) {
            throw ApiException.notFound("Data source " + sourceCode);
        }
        // Jobs left RUNNING by a crashed instance must not block the source forever.
        jdbc.update("""
                update ingestion_jobs set status = 'FAILED', finished_at = now(),
                       errors = errors || '["abandoned: no progress for 6 hours"]'::jsonb
                where data_source_id = ? and status in ('QUEUED', 'RUNNING') and created_at < now() - interval '6 hours'
                """, sourceId);
        UUID id = Ids.newId();
        try {
            jdbc.update("insert into ingestion_jobs (id, data_source_id, type, status, triggered_by) "
                    + "values (?, ?, ?, 'QUEUED', ?)", id, sourceId, type.name(), triggeredBy);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw ApiException.conflict("job_already_running", "A job for " + sourceCode + " is already running");
        }
        return id;
    }

    /** Code of the data source of a job, or null if the job does not exist. */
    public String sourceOf(UUID jobId) {
        return jdbc.query("""
                select d.code from ingestion_jobs j join data_sources d on d.id = j.data_source_id where j.id = ?
                """, rs -> rs.next() ? rs.getString(1) : null, jobId);
    }

    /** Publication window [from, to] of a finished job in the given zone. */
    public java.time.LocalDate[] retryWindow(UUID jobId, java.time.ZoneId zone) {
        Object[] w = jdbc.query("""
                select j.window_from, j.window_to, j.status from ingestion_jobs j where j.id = ?
                """, rs -> rs.next() ? new Object[] {rs.getTimestamp(1), rs.getTimestamp(2), rs.getString(3)} : null,
                jobId);
        if (w == null) {
            throw ApiException.notFound("Ingestion job");
        }
        if (w[0] == null || w[1] == null) {
            throw ApiException.badRequest("not_retryable", "Only jobs with a recorded window can be retried");
        }
        if ("RUNNING".equals(w[2]) || "QUEUED".equals(w[2])) {
            throw ApiException.conflict("job_already_running", "The job is still running");
        }
        return new java.time.LocalDate[] {((java.sql.Timestamp) w[0]).toInstant().atZone(zone).toLocalDate(),
                ((java.sql.Timestamp) w[1]).toInstant().atZone(zone).toLocalDate()};
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markRunning(UUID jobId) {
        jdbc.update("update ingestion_jobs set status = 'RUNNING', started_at = now() where id = ?", jobId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSucceeded(UUID jobId, int fetched, int created, int updated, int skipped) {
        jdbc.update("""
                update ingestion_jobs set status = 'SUCCEEDED', finished_at = now(), fetched_count = ?,
                       created_count = ?, updated_count = ?, skipped_count = ?
                where id = ?
                """, fetched, created, updated, skipped, jobId);
        jdbc.update("""
                update data_sources set last_success_at = now(), updated_at = now()
                where id = (select data_source_id from ingestion_jobs where id = ?)
                """, jobId);
    }

    /** Live progress, so admins can follow long backfills; the cursor allows resuming. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void progress(UUID jobId, int fetched, int created, int updated, int skipped, int failed, String cursor) {
        jdbc.update("""
                update ingestion_jobs set fetched_count = ?, created_count = ?, updated_count = ?, skipped_count = ?,
                       error_count = ?, cursor = ?
                where id = ?
                """, fetched, created, updated, skipped, failed, cursor, jobId);
    }

    /** Finished, but some records failed: errors keeps a sample for diagnosis. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFinished(UUID jobId, int fetched, int created, int updated, int skipped, int failed,
            String errorsJson) {
        jdbc.update("""
                update ingestion_jobs set status = ?, finished_at = now(), fetched_count = ?, created_count = ?,
                       updated_count = ?, skipped_count = ?, error_count = ?, errors = ?::jsonb, cursor = null
                where id = ?
                """, failed == 0 ? "SUCCEEDED" : "PARTIAL", fetched, created, updated, skipped, failed, errorsJson,
                jobId);
        jdbc.update("""
                update data_sources set last_success_at = now(), status = 'ACTIVE', updated_at = now()
                where id = (select data_source_id from ingestion_jobs where id = ?)
                """, jobId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markWindow(UUID jobId, java.time.Instant from, java.time.Instant to) {
        jdbc.update("update ingestion_jobs set window_from = ?, window_to = ? where id = ?",
                java.sql.Timestamp.from(from), java.sql.Timestamp.from(to), jobId);
    }

    /** Number of consecutive failed jobs of a source, newest first (used to flag the source as ERROR). */
    public int consecutiveFailures(String sourceCode) {
        java.util.List<String> statuses = jdbc.queryForList("""
                select j.status from ingestion_jobs j join data_sources d on d.id = j.data_source_id
                where d.code = ? and j.status in ('SUCCEEDED', 'PARTIAL', 'FAILED')
                order by j.created_at desc limit 10
                """, String.class, sourceCode);
        int n = 0;
        for (String s : statuses) {
            if (!"FAILED".equals(s)) {
                break;
            }
            n++;
        }
        return n;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSourceStatus(String sourceCode, String status) {
        jdbc.update("update data_sources set status = ?, updated_at = now() where code = ?", status, sourceCode);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID jobId, String error) {
        String message = error == null ? "unknown error" : error.substring(0, Math.min(error.length(), 1000));
        jdbc.update("""
                update ingestion_jobs set status = 'FAILED', finished_at = now(), error_count = error_count + 1,
                       errors = errors || jsonb_build_array(?::text)
                where id = ?
                """, message, jobId);
        jdbc.update("""
                update data_sources set last_error_at = now(), last_error = ?, updated_at = now()
                where id = (select data_source_id from ingestion_jobs where id = ?)
                """, message, jobId);
    }
}
