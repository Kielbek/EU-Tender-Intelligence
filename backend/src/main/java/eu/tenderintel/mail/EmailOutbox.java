package eu.tenderintel.mail;

import eu.tenderintel.common.id.Ids;
import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional outbox: an e-mail is stored in the same transaction as the business change and delivered later by
 * {@link EmailDispatcher}. If SMTP is not configured the messages stay PENDING (visible to admins) instead of being
 * silently dropped.
 */
@Service
public class EmailOutbox {

    private final JdbcTemplate jdbc;
    private final MessageSource messages;

    EmailOutbox(JdbcTemplate jdbc, MessageSource messages) {
        this.jdbc = jdbc;
        this.messages = messages;
    }

    /** {@code args} are substituted into the localized body ({0}, {1}, ...). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String to, EmailTemplate template, String locale, Object... args) {
        Locale loc = Locale.forLanguageTag(locale);
        String subject = messages.getMessage("mail." + template.key() + ".subject", null, loc);
        String body = messages.getMessage("mail." + template.key() + ".body", args, loc)
                + "\n\n--\n" + messages.getMessage("mail.footer", null, loc);
        jdbc.update("""
                insert into email_outbox (id, to_address, template, locale, subject, body_text)
                values (?, ?, ?, ?, ?, ?)
                """, Ids.newId(), to, template.name(), locale, subject, body);
    }

    /**
     * A message composed by the caller (e.g. a notification digest). {@code listUnsubscribe} – URL for one-click
     * unsubscribe (RFC 8058), sent as {@code List-Unsubscribe} headers; {@code footerKey} replaces the default footer.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueComposed(String to, EmailTemplate template, String locale, String subject, String body,
            String footerKey, String listUnsubscribe) {
        Locale loc = Locale.forLanguageTag(locale);
        String full = body + "\n\n--\n" + messages.getMessage(footerKey == null ? "mail.footer" : footerKey, null, loc);
        jdbc.update("""
                insert into email_outbox (id, to_address, template, locale, subject, body_text, list_unsubscribe)
                values (?, ?, ?, ?, ?, ?, ?)
                """, Ids.newId(), to, template.name(), locale, subject, full, listUnsubscribe);
    }
}
