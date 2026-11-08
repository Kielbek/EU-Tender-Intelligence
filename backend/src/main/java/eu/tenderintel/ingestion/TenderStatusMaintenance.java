package eu.tenderintel.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Time-driven status changes that no notice announces: an OPEN tender whose deadline has passed moves to the next
 * open lot deadline, or to CLOSED. Competition notices without any deadline are closed 90 days after publication.
 */
@Service
public class TenderStatusMaintenance {

    private static final Logger log = LoggerFactory.getLogger(TenderStatusMaintenance.class);

    private final JdbcTemplate jdbc;

    TenderStatusMaintenance(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(cron = "0 5 * * * *")
    @Transactional
    public int closeExpired() {
        int advanced = jdbc.update("""
                update tenders t set submission_deadline = l.next_deadline, updated_at = now()
                from (select tender_id, min(submission_deadline) as next_deadline from tender_lots
                      where submission_deadline > now() group by tender_id) l
                where t.id = l.tender_id and t.status = 'OPEN' and t.submission_deadline <= now()
                """);
        int closed = jdbc.update("""
                update tenders set status = 'CLOSED', updated_at = now()
                where status = 'OPEN'
                  and ((submission_deadline is not null and submission_deadline <= now())
                       or (submission_deadline is null and published_at < now() - interval '90 days'))
                """);
        if (advanced + closed > 0) {
            log.info("Tender status maintenance: {} moved to next lot deadline, {} closed", advanced, closed);
        }
        return closed;
    }
}
