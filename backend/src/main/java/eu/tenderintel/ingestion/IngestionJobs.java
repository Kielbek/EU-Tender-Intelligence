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
        UUID id = Ids.newId();
        jdbc.update("insert into ingestion_jobs (id, data_source_id, type, status, triggered_by) "
                + "values (?, ?, ?, 'QUEUED', ?)", id, sourceId, type.name(), triggeredBy);
        return id;
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
