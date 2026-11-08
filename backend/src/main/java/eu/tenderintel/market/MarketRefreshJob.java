package eu.tenderintel.market;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Rebuilds the award views from tender_results. CONCURRENTLY keeps them readable while refreshing; an advisory lock
 * keeps instances from refreshing at the same time. New awards appear in statistics within the interval.
 */
@Component
public class MarketRefreshJob {

    private static final long LOCK_KEY = 0x6d61726b6574L; // "market"
    private static final Logger log = LoggerFactory.getLogger(MarketRefreshJob.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    MarketRefreshJob(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Scheduled(fixedDelayString = "${app.market.refresh-interval:PT30M}", initialDelayString = "PT2M")
    void scheduled() {
        refresh();
    }

    public boolean refresh() {
        long start = System.currentTimeMillis();
        Boolean done = tx.execute(status -> {
            if (!Boolean.TRUE.equals(jdbc.queryForObject("select pg_try_advisory_xact_lock(?)", Boolean.class,
                    LOCK_KEY))) {
                return false;
            }
            jdbc.execute("refresh materialized view concurrently award_facts");
            jdbc.execute("refresh materialized view concurrently tender_award_summary");
            return true;
        });
        if (Boolean.TRUE.equals(done)) {
            log.info("Award statistics refreshed in {} ms", System.currentTimeMillis() - start);
        }
        return Boolean.TRUE.equals(done);
    }
}
