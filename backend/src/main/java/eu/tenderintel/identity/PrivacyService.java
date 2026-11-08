package eu.tenderintel.identity;

import eu.tenderintel.audit.AuditAction;
import eu.tenderintel.audit.AuditService;
import eu.tenderintel.common.error.ApiException;
import eu.tenderintel.security.PasswordHasher;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GDPR rights of a signed-in user: access/portability (art. 15 and 20 – machine-readable export of everything stored
 * about the person) and erasure (art. 17 – account deletion). Company data that belongs to an organization stays
 * with the organization while other members remain; an organization the user was the only member of is deleted.
 */
@Service
public class PrivacyService {

    private final JdbcTemplate jdbc;
    private final PasswordHasher hasher;
    private final AuditService audit;
    private final Clock clock;

    PrivacyService(JdbcTemplate jdbc, PasswordHasher hasher, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.hasher = hasher;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional // not read-only: the export itself is recorded in the audit log
    public Map<String, Object> export(UUID userId) {
        Map<String, Object> user = one("""
                select id, email::text as email, full_name, locale, timezone, platform_role, status, email_verified_at,
                       last_login_at, terms_accepted_at, terms_version, created_at, updated_at
                from users where id = ?
                """, userId);
        if (user == null) {
            throw ApiException.notFound("User");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exportedAt", Instant.now(clock).toString());
        out.put("format", "EU Tender Intelligence personal data export v1");
        out.put("account", user);
        out.put("organizations", jdbc.queryForList("""
                select o.id, o.name, o.country_code, m.role, m.joined_at
                from organization_members m join organizations o on o.id = m.organization_id
                where m.user_id = ? order by m.joined_at
                """, userId));
        out.put("sessions", jdbc.queryForList("""
                select created_at, expires_at, revoked_at, user_agent, host(ip) as ip
                from refresh_tokens where user_id = ? order by created_at desc
                """, userId));
        out.put("savedSearches", jdbc.queryForList("""
                select id, organization_id, name, filters::text as filters, alert_enabled, alert_frequency,
                       created_at, updated_at
                from saved_searches where user_id = ? order by created_at
                """, userId));
        out.put("watchedTendersAdded", jdbc.queryForList("""
                select s.tender_id, t.title, s.status, s.notes, s.created_at
                from saved_tenders s join tenders t on t.id = s.tender_id
                where s.created_by = ? order by s.created_at
                """, userId));
        out.put("notificationPreferences", jdbc.queryForList("""
                select organization_id, event_type, in_app, email_frequency, min_score, updated_at
                from notification_preferences where user_id = ? order by organization_id, event_type
                """, userId));
        out.put("notifications", jdbc.queryForList("""
                select organization_id, type, tender_id, payload::text as payload, created_at, read_at, emailed_at
                from notifications where user_id = ? order by created_at desc
                """, userId));
        out.put("uploadedDocuments", jdbc.queryForList("""
                select id, tender_id, organization_id, file_name, mime_type, size_bytes, created_at
                from tender_documents where uploaded_by = ? order by created_at
                """, userId));
        out.put("aiUsage", jdbc.queryForList("""
                select organization_id, feature, model, input_tokens, output_tokens, created_at
                from ai_usage where user_id = ? order by created_at
                """, userId));
        out.put("invitationsSent", jdbc.queryForList("""
                select organization_id, email::text as email, role, created_at, accepted_at, revoked_at
                from organization_invitations where invited_by = ? order by created_at
                """, userId));
        out.put("securityLog", jdbc.queryForList("""
                select occurred_at, action, target_type, host(ip) as ip, user_agent
                from audit_logs where actor_user_id = ? order by occurred_at desc limit 1000
                """, userId));
        out.put("emails", jdbc.queryForList("""
                select template, subject, status, created_at, sent_at from email_outbox
                where to_address = ? order by created_at desc limit 200
                """, (String) user.get("email")));
        audit.record(AuditAction.PERSONAL_DATA_EXPORTED, userId);
        return out;
    }

    /**
     * Deletes the account after password confirmation. Refused while the user is the only administrator of an
     * organization that has other members (they must hand over the role first).
     */
    @Transactional
    public void delete(UUID userId, String password) {
        Map<String, Object> user = one("select email::text as email, password_hash from users where id = ?", userId);
        if (user == null) {
            throw ApiException.notFound("User");
        }
        if (password == null || !hasher.matches(password, (String) user.get("password_hash"))) {
            throw ApiException.badRequest("invalid_current_password", "Password is incorrect");
        }
        List<Map<String, Object>> blocking = jdbc.queryForList("""
                select o.id, o.name from organization_members m join organizations o on o.id = m.organization_id
                where m.user_id = ? and m.role = 'COMPANY_ADMIN'
                  and not exists (select 1 from organization_members a where a.organization_id = m.organization_id
                                  and a.user_id <> m.user_id and a.role = 'COMPANY_ADMIN')
                  and exists (select 1 from organization_members x where x.organization_id = m.organization_id
                              and x.user_id <> m.user_id)
                """, userId);
        if (!blocking.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "transfer_admin_first",
                    "Hand over the administrator role before deleting the account",
                    Map.of("organizations", blocking.stream().map(r -> r.get("name")).toList()));
        }
        // A paid subscription of an organization that would be deleted keeps charging the card in Stripe.
        List<String> paying = jdbc.queryForList("""
                select o.name from organization_members m join organizations o on o.id = m.organization_id
                where m.user_id = ?
                  and not exists (select 1 from organization_members x where x.organization_id = m.organization_id
                                  and x.user_id <> m.user_id)
                  and exists (select 1 from subscriptions s where s.organization_id = o.id
                              and s.status in ('active', 'trialing', 'past_due', 'unpaid', 'paused')
                              and not s.cancel_at_period_end)
                """, String.class, userId);
        if (!paying.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "cancel_subscription_first",
                    "Cancel the organization's subscription before deleting the account",
                    Map.of("organizations", paying));
        }
        // Organizations where the user is the only member go with the account (profile, documents, analyses…).
        List<UUID> solo = jdbc.queryForList("""
                select m.organization_id from organization_members m where m.user_id = ?
                  and not exists (select 1 from organization_members x where x.organization_id = m.organization_id
                                  and x.user_id <> m.user_id)
                """, UUID.class, userId);
        for (UUID orgId : solo) {
            jdbc.update("delete from organizations where id = ?", orgId);
        }
        String email = (String) user.get("email");
        jdbc.update("delete from email_outbox where to_address = ?", email);
        jdbc.update("delete from organization_invitations where email = ?", email);
        // Security log entries stay (accountability) but lose what identifies the person's device.
        jdbc.update("update audit_logs set ip = null, user_agent = null where actor_user_id = ?", userId);
        jdbc.update("delete from users where id = ?", userId);
        audit.record(AuditAction.ACCOUNT_DELETED, null, null, "user", userId,
                Map.of("organizationsDeleted", solo.size()));
    }

    private Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = new LinkedHashMap<>(rows.getFirst());
        row.replaceAll((k, v) -> v instanceof java.sql.Timestamp ts ? ts.toInstant().toString() : v);
        return row;
    }
}
