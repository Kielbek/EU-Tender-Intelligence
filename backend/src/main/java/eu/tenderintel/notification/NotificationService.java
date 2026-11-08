package eu.tenderintel.notification;

import eu.tenderintel.audit.AuditAction;
import eu.tenderintel.audit.AuditService;
import eu.tenderintel.billing.Entitlements;
import eu.tenderintel.common.error.ApiException;
import eu.tenderintel.notification.NotificationDtos.NotificationPage;
import eu.tenderintel.notification.NotificationDtos.NotificationView;
import eu.tenderintel.notification.NotificationDtos.PreferenceUpdate;
import eu.tenderintel.notification.NotificationDtos.PreferenceView;
import eu.tenderintel.notification.NotificationDtos.PreferencesView;
import eu.tenderintel.notification.NotificationDtos.SavedSearchRef;
import eu.tenderintel.notification.NotificationDtos.TenderRef;
import eu.tenderintel.organization.OrganizationAccess;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** The person's notification inbox and preferences within one organization. */
@Service
public class NotificationService {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final OrganizationAccess access;
    private final Entitlements entitlements;
    private final UnsubscribeTokens tokens;
    private final AuditService audit;
    private final JsonMapper json;

    NotificationService(JdbcTemplate jdbc, OrganizationAccess access, Entitlements entitlements,
            UnsubscribeTokens tokens, AuditService audit, JsonMapper json) {
        this.jdbc = jdbc;
        this.access = access;
        this.entitlements = entitlements;
        this.tokens = tokens;
        this.audit = audit;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public NotificationPage list(UUID orgId, UUID userId, boolean unreadOnly, int page, int size) {
        access.requireMember(orgId, userId);
        String filter = unreadOnly ? " and n.read_at is null" : "";
        Long total = jdbc.queryForObject("select count(*) from notifications n where n.user_id = ? "
                + "and n.organization_id = ? and n.in_app" + filter, Long.class, userId, orgId);
        List<NotificationView> items = jdbc.query("""
                select n.id, n.type, n.created_at, n.read_at, n.payload::text,
                       t.id, t.title, t.country_code, t.submission_deadline, t.status,
                       s.id, s.name, s.filters::text, o.id, o.name
                from notifications n
                left join tenders t on t.id = n.tender_id
                left join saved_searches s on s.id = n.saved_search_id
                left join economic_operators o on o.id = n.operator_id
                where n.user_id = ? and n.organization_id = ? and n.in_app
                """ + filter + " order by n.created_at desc, n.id limit ? offset ?",
                (rs, i) -> view(rs), userId, orgId, size, (long) page * size);
        return new NotificationPage(items, total == null ? 0 : total, unread(orgId, userId), page, size);
    }

    @Transactional(readOnly = true)
    public long unreadCount(UUID orgId, UUID userId) {
        access.requireMember(orgId, userId);
        return unread(orgId, userId);
    }

    private long unread(UUID orgId, UUID userId) {
        Long n = jdbc.queryForObject("select count(*) from notifications where user_id = ? and organization_id = ? "
                + "and in_app and read_at is null", Long.class, userId, orgId);
        return n == null ? 0 : n;
    }

    @Transactional
    public void markRead(UUID orgId, UUID userId, UUID id) {
        access.requireMember(orgId, userId);
        int n = jdbc.update("update notifications set read_at = coalesce(read_at, now()) where id = ? "
                + "and user_id = ? and organization_id = ?", id, userId, orgId);
        if (n == 0) {
            throw ApiException.notFound("Notification");
        }
    }

    @Transactional
    public int markAllRead(UUID orgId, UUID userId) {
        access.requireMember(orgId, userId);
        return jdbc.update("update notifications set read_at = now() where user_id = ? and organization_id = ? "
                + "and read_at is null", userId, orgId);
    }

    // --- preferences ---------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PreferencesView preferences(UUID orgId, UUID userId) {
        access.requireMember(orgId, userId);
        Map<String, PreferenceView> stored = new HashMap<>();
        jdbc.query("select event_type, in_app, email_frequency, min_score from notification_preferences "
                + "where user_id = ? and organization_id = ?", rs -> {
                    stored.put(rs.getString(1), new PreferenceView(rs.getString(1), rs.getBoolean(2),
                            rs.getString(3), (Integer) rs.getObject(4, Integer.class)));
                }, userId, orgId);
        List<PreferenceView> out = new ArrayList<>();
        for (NotificationType t : NotificationType.values()) {
            if (t.configurable()) {
                out.add(stored.getOrDefault(t.name(),
                        new PreferenceView(t.name(), t.defaultInApp(), t.defaultEmail(), t.defaultMinScore())));
            }
        }
        Boolean verified = jdbc.queryForObject("select email_verified_at is not null from users where id = ?",
                Boolean.class, userId);
        return new PreferencesView(out, entitlements.plan(orgId).instantEmails(), Boolean.TRUE.equals(verified));
    }

    @Transactional
    public PreferencesView updatePreferences(UUID orgId, UUID userId, List<PreferenceUpdate> updates) {
        access.requireMember(orgId, userId);
        boolean instant = entitlements.plan(orgId).instantEmails();
        for (PreferenceUpdate u : updates) {
            if ("INSTANT".equals(u.emailFrequency()) && !instant) {
                throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "instant_alerts_unavailable",
                        "Instant e-mails are not part of the plan", Map.of("plan", entitlements.plan(orgId).name()));
            }
            Integer minScore = NotificationType.NEW_MATCH.name().equals(u.type())
                    ? (u.minScore() == null ? NotificationType.NEW_MATCH.defaultMinScore() : u.minScore()) : null;
            jdbc.update("""
                    insert into notification_preferences (user_id, organization_id, event_type, in_app,
                        email_frequency, min_score)
                    values (?, ?, ?, ?, ?, ?)
                    on conflict (user_id, organization_id, event_type) do update set in_app = excluded.in_app,
                        email_frequency = excluded.email_frequency, min_score = excluded.min_score, updated_at = now()
                    """, userId, orgId, u.type(), u.inApp(), u.emailFrequency(), minScore);
        }
        return preferences(orgId, userId);
    }

    /**
     * From the link in a notification e-mail (no sign-in): switches off all notification e-mails of that person in
     * that organization, including saved-search alert e-mails. In-app notifications stay.
     */
    @Transactional
    public String unsubscribe(String token) {
        UUID[] ids = tokens.verify(token);
        if (ids == null) {
            throw ApiException.badRequest("invalid_token", "The unsubscribe link is invalid");
        }
        UUID userId = ids[0];
        UUID orgId = ids[1];
        List<String> org = jdbc.queryForList("select o.name from organizations o join organization_members m "
                + "on m.organization_id = o.id where o.id = ? and m.user_id = ?", String.class, orgId, userId);
        if (org.isEmpty()) {
            // Left the organization or deleted the account: nothing is sent anyway.
            return null;
        }
        for (NotificationType t : NotificationType.values()) {
            if (t.configurable()) {
                jdbc.update("""
                        insert into notification_preferences (user_id, organization_id, event_type, in_app,
                            email_frequency, min_score)
                        values (?, ?, ?, ?, 'OFF', ?)
                        on conflict (user_id, organization_id, event_type) do update set email_frequency = 'OFF',
                            updated_at = now()
                        """, userId, orgId, t.name(), t.defaultInApp(), t.defaultMinScore());
            }
        }
        jdbc.update("update saved_searches set alert_frequency = 'OFF', updated_at = now() where user_id = ? "
                + "and organization_id = ? and alert_enabled", userId, orgId);
        jdbc.update("update notifications set email_frequency = 'OFF' where user_id = ? and organization_id = ? "
                + "and emailed_at is null", userId, orgId);
        audit.record(AuditAction.NOTIFICATIONS_UNSUBSCRIBED, userId, orgId, "user", userId, Map.of());
        return org.get(0);
    }

    private NotificationView view(ResultSet rs) throws SQLException {
        TenderRef tender = rs.getObject(6) == null ? null : new TenderRef(rs.getObject(6, UUID.class),
                rs.getString(7), rs.getString(8),
                rs.getTimestamp(9) == null ? null : rs.getTimestamp(9).toInstant(), rs.getString(10));
        SavedSearchRef search = rs.getObject(11) == null ? null : new SavedSearchRef(rs.getObject(11, UUID.class),
                rs.getString(12), json.readValue(rs.getString(13), MAP));
        NotificationDtos.CompanyRef company = rs.getObject(14) == null ? null
                : new NotificationDtos.CompanyRef(rs.getObject(14, UUID.class), rs.getString(15));
        return new NotificationView(rs.getObject(1, UUID.class), rs.getString(2), rs.getTimestamp(3).toInstant(),
                rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toInstant(), tender, search, company,
                json.readValue(rs.getString(5), MAP));
    }
}
