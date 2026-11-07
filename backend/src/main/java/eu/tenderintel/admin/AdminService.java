package eu.tenderintel.admin;

import eu.tenderintel.admin.AdminDtos.AdminUserView;
import eu.tenderintel.admin.AdminDtos.AuditLogView;
import eu.tenderintel.admin.AdminDtos.DataSourceView;
import eu.tenderintel.admin.AdminDtos.EmailOutboxStats;
import eu.tenderintel.admin.AdminDtos.IngestionJobView;
import eu.tenderintel.audit.AuditAction;
import eu.tenderintel.audit.AuditService;
import eu.tenderintel.common.api.PageResponse;
import eu.tenderintel.common.error.ApiException;
import eu.tenderintel.identity.AuthService;
import eu.tenderintel.identity.User;
import eu.tenderintel.identity.UserRepository;
import eu.tenderintel.mail.EmailDispatcher;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
class AdminService {

    private final UserRepository users;
    private final AuthService auth;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final EmailDispatcher emailDispatcher;
    private final Clock clock;

    AdminService(UserRepository users, AuthService auth, AuditService audit, JdbcTemplate jdbc, JsonMapper json,
            EmailDispatcher emailDispatcher, Clock clock) {
        this.users = users;
        this.auth = auth;
        this.audit = audit;
        this.jdbc = jdbc;
        this.json = json;
        this.emailDispatcher = emailDispatcher;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    PageResponse<AdminUserView> users(String q, int page, int size) {
        String query = q == null || q.isBlank() ? null : q.trim();
        return PageResponse.of(users.search(query, PageRequest.of(page, size, Sort.by("createdAt").descending())),
                u -> new AdminUserView(u.getId(), u.getEmail(), u.getFullName(), u.getPlatformRole().name(),
                        u.getStatus().name(), u.isEmailVerified(), u.getLockedUntil(), u.getLastLoginAt(),
                        u.getCreatedAt()));
    }

    @Transactional
    void lock(UUID userId, UUID actorId) {
        if (userId.equals(actorId)) {
            throw ApiException.badRequest("cannot_lock_self", "You cannot lock your own account");
        }
        User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
        user.setStatus(User.Status.LOCKED, Instant.now(clock));
        auth.revokeAllSessions(userId);
        audit.record(AuditAction.ADMIN_USER_LOCKED, actorId, null, "user", userId, Map.of());
    }

    @Transactional
    void unlock(UUID userId, UUID actorId) {
        User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
        user.setStatus(User.Status.ACTIVE, Instant.now(clock));
        audit.record(AuditAction.ADMIN_USER_UNLOCKED, actorId, null, "user", userId, Map.of());
    }

    PageResponse<AuditLogView> auditLogs(String action, UUID actor, UUID org, int page, int size) {
        List<String> conditions = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (action != null && !action.isBlank()) {
            conditions.add("action = ?");
            args.add(action);
        }
        if (actor != null) {
            conditions.add("actor_user_id = ?");
            args.add(actor);
        }
        if (org != null) {
            conditions.add("organization_id = ?");
            args.add(org);
        }
        String where = conditions.isEmpty() ? "" : " where " + String.join(" and ", conditions);
        Long total = jdbc.queryForObject("select count(*) from audit_logs" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(page * size);
        List<AuditLogView> items = jdbc.query("""
                select id, occurred_at, actor_user_id, organization_id, action, target_type, target_id, host(ip),
                       correlation_id, details::text
                from audit_logs""" + where + " order by occurred_at desc limit ? offset ?",
                (rs, i) -> new AuditLogView(rs.getObject(1, UUID.class), instant(rs, 2), rs.getObject(3, UUID.class),
                        rs.getObject(4, UUID.class), rs.getString(5), rs.getString(6), rs.getString(7),
                        rs.getString(8), rs.getString(9), json.readValue(rs.getString(10), Object.class)),
                pageArgs.toArray());
        return PageResponse.of(items, page, size, total == null ? 0 : total);
    }

    List<DataSourceView> dataSources() {
        return jdbc.query("""
                select id, code, name, country_code, adapter_type, status, homepage_url, terms_url, terms_verified_at,
                       schedule_cron, notes, last_success_at, last_error_at, last_error
                from data_sources order by country_code nulls first, code
                """, (rs, i) -> new DataSourceView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), instant(rs, 9),
                rs.getString(10), rs.getString(11), instant(rs, 12), instant(rs, 13), rs.getString(14)));
    }

    PageResponse<IngestionJobView> jobs(String source, int page, int size) {
        String where = source == null || source.isBlank() ? "" : " where ds.code = ?";
        Object[] args = where.isEmpty() ? new Object[0] : new Object[] {source};
        Long total = jdbc.queryForObject("select count(*) from ingestion_jobs j join data_sources ds "
                + "on ds.id = j.data_source_id" + where, Long.class, args);
        List<Object> pageArgs = new ArrayList<>(List.of(args));
        pageArgs.add(size);
        pageArgs.add(page * size);
        List<IngestionJobView> items = jdbc.query("""
                select j.id, ds.code, j.type, j.status, j.fetched_count, j.created_count, j.updated_count,
                       j.skipped_count, j.error_count, j.errors::text, j.triggered_by, j.started_at, j.finished_at,
                       j.created_at
                from ingestion_jobs j join data_sources ds on ds.id = j.data_source_id""" + where
                + " order by j.created_at desc limit ? offset ?",
                (rs, i) -> new IngestionJobView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getInt(8), rs.getInt(9),
                        json.readValue(rs.getString(10), Object.class), rs.getString(11), instant(rs, 12),
                        instant(rs, 13), instant(rs, 14)),
                pageArgs.toArray());
        return PageResponse.of(items, page, size, total == null ? 0 : total);
    }

    EmailOutboxStats emailOutboxStats() {
        Map<String, Object> row = jdbc.queryForMap("""
                select count(*) filter (where status = 'PENDING') as pending,
                       count(*) filter (where status = 'SENT') as sent,
                       count(*) filter (where status = 'FAILED') as failed
                from email_outbox
                """);
        return new EmailOutboxStats(((Number) row.get("pending")).longValue(), ((Number) row.get("sent")).longValue(),
                ((Number) row.get("failed")).longValue(), emailDispatcher.isConfigured());
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
