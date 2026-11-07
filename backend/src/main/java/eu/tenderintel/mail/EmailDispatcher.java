package eu.tenderintel.mail;

import eu.tenderintel.config.AppProperties;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * Delivers PENDING outbox messages over SMTP. Rows are claimed with {@code FOR UPDATE SKIP LOCKED}, so several
 * instances can run concurrently. After delivery the body (which may contain single-use links) is erased.
 */
@Component
public class EmailDispatcher {

    static final int MAX_ATTEMPTS = 8;
    private static final Logger log = LoggerFactory.getLogger(EmailDispatcher.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectProvider<JavaMailSender> mailSender;
    private final AppProperties props;

    EmailDispatcher(JdbcTemplate jdbc, TransactionTemplate tx, ObjectProvider<JavaMailSender> mailSender,
            AppProperties props) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.mailSender = mailSender;
        this.props = props;
    }

    public boolean isConfigured() {
        return StringUtils.hasText(props.integrations().smtpHost()) && mailSender.getIfAvailable() != null;
    }

    @Scheduled(fixedDelayString = "${app.mail.dispatch-interval:PT10S}", initialDelayString = "PT5S")
    public void dispatchPending() {
        if (!isConfigured()) {
            return;
        }
        int sent;
        do {
            sent = dispatchBatch(20);
        } while (sent == 20);
    }

    int dispatchBatch(int limit) {
        Integer processed = tx.execute(status -> {
            List<Pending> batch = jdbc.query("""
                    select id, to_address, subject, body_text, attempts from email_outbox
                    where status = 'PENDING' and next_attempt_at <= now()
                    order by created_at
                    limit ? for update skip locked
                    """, (rs, i) -> new Pending(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                    rs.getString(4), rs.getInt(5)), limit);
            for (Pending p : batch) {
                deliver(p);
            }
            return batch.size();
        });
        return processed == null ? 0 : processed;
    }

    private void deliver(Pending p) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(props.mail().from());
            message.setTo(p.to());
            message.setSubject(p.subject());
            message.setText(p.body());
            mailSender.getObject().send(message);
            jdbc.update("update email_outbox set status = 'SENT', sent_at = now(), body_text = null, "
                    + "attempts = attempts + 1, last_error = null where id = ?", p.id());
        } catch (RuntimeException ex) {
            int attempts = p.attempts() + 1;
            boolean giveUp = attempts >= MAX_ATTEMPTS;
            long backoffSeconds = Duration.ofSeconds(30).multipliedBy(1L << Math.min(attempts, 10)).toSeconds();
            log.warn("E-mail {} delivery failed (attempt {})", p.id(), attempts, ex);
            jdbc.update("update email_outbox set attempts = ?, last_error = ?, status = ?, "
                    + "next_attempt_at = now() + make_interval(secs => ?) where id = ?",
                    attempts, truncate(ex.getMessage()), giveUp ? "FAILED" : "PENDING", backoffSeconds, p.id());
        }
    }

    private static String truncate(String s) {
        return s == null ? null : s.substring(0, Math.min(s.length(), 500));
    }

    private record Pending(UUID id, String to, String subject, String body, int attempts) {
    }
}
