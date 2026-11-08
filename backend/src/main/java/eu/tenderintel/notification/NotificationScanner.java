package eu.tenderintel.notification;

import eu.tenderintel.config.AppProperties;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Periodic job: creates notifications for what happened since the previous scan, then sends the e-mails that are
 * due. A PostgreSQL advisory lock makes sure only one instance scans at a time; the cursor is stored in the database,
 * so a restart continues where it stopped (and the first run starts from "now", without notifying history).
 */
@Component
public class NotificationScanner {

    static final String CURSOR = "scanner";
    /** Upper bound of a scan stays this far behind "now", so in-flight ingestion transactions have committed. */
    static final Duration SETTLE = Duration.ofMinutes(1);
    private static final long LOCK_KEY = 0x6e6f7469666cL; // "notifl"
    private static final Logger log = LoggerFactory.getLogger(NotificationScanner.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final NotificationGenerator generator;
    private final NotificationMailer mailer;
    private final AppProperties props;

    NotificationScanner(JdbcTemplate jdbc, TransactionTemplate tx, NotificationGenerator generator,
            NotificationMailer mailer, AppProperties props) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.generator = generator;
        this.mailer = mailer;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${app.notifications.scan-interval:PT5M}", initialDelayString = "PT1M")
    void scheduled() {
        if (props.notifications().enabled()) {
            run();
        }
    }

    /** One scan; returns false when another instance holds the lock. */
    public boolean run() {
        Boolean done = tx.execute(status -> {
            Boolean locked = jdbc.queryForObject("select pg_try_advisory_xact_lock(?)", Boolean.class, LOCK_KEY);
            if (!Boolean.TRUE.equals(locked)) {
                return false;
            }
            Instant now = jdbc.queryForObject("select now()", Timestamp.class).toInstant();
            Instant to = now.minus(SETTLE);
            Instant from = jdbc.query("select position from notification_cursors where name = ?",
                    rs -> rs.next() ? rs.getTimestamp(1).toInstant() : null, CURSOR);
            if (from == null) {
                jdbc.update("insert into notification_cursors (name, position) values (?, ?)", CURSOR,
                        Timestamp.from(to));
                return true;
            }
            if (to.isAfter(from)) {
                NotificationGenerator.Counts c = generator.generate(from, to,
                        props.notifications().deadlineReminderDays());
                jdbc.update("update notification_cursors set position = ? where name = ?", Timestamp.from(to), CURSOR);
                if (c.total() > 0) {
                    log.info("Notifications created: {} new matches, {} watch-list changes, {} deadlines, "
                            + "{} saved-search alerts, {} competitor wins", c.newMatches(), c.watchedChanges(),
                            c.deadlines(), c.savedSearches(), c.competitorWins());
                }
            }
            int deleted = jdbc.update("delete from notifications where created_at < now() - make_interval(days => ?)",
                    props.notifications().retentionDays());
            if (deleted > 0) {
                log.info("Deleted {} notifications past retention", deleted);
            }
            return true;
        });
        if (Boolean.TRUE.equals(done)) {
            int mails = mailer.sendDue(Instant.now());
            if (mails > 0) {
                log.info("Queued {} notification e-mails", mails);
            }
        }
        return Boolean.TRUE.equals(done);
    }
}
