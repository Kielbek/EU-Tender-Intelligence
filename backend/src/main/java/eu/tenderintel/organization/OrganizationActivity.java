package eu.tenderintel.organization;

import eu.tenderintel.common.api.PageResponse;
import eu.tenderintel.organization.OrganizationDtos.ActivityView;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The team's activity log for organization admins: entries of the audit log that belong to the organization, with
 * the actor's and the target's names. IP addresses and user agents stay in the audit log only (platform admins).
 */
@Service
class OrganizationActivity {

    static final int MAX_SIZE = 50;

    private final JdbcTemplate jdbc;
    private final OrganizationAccess access;
    private final JsonMapper json;

    OrganizationActivity(JdbcTemplate jdbc, OrganizationAccess access, JsonMapper json) {
        this.jdbc = jdbc;
        this.access = access;
        this.json = json;
    }

    PageResponse<ActivityView> list(UUID orgId, UUID userId, int page, int size) {
        access.requireAdmin(orgId, userId);
        int p = Math.max(0, page);
        int s = Math.max(1, Math.min(size, MAX_SIZE));
        Long total = jdbc.queryForObject("select count(*) from audit_logs where organization_id = ?", Long.class, orgId);
        List<ActivityView> items = jdbc.query("""
                select a.occurred_at, a.action, u.full_name, u.email,
                       coalesce(tu.full_name, inv.email, null), a.details::text
                from audit_logs a
                left join users u on u.id = a.actor_user_id
                left join users tu on a.target_type = 'user' and tu.id::text = a.target_id
                left join organization_invitations inv on a.target_type = 'invitation' and inv.id::text = a.target_id
                where a.organization_id = ?
                order by a.occurred_at desc, a.id
                limit ? offset ?
                """, (rs, i) -> new ActivityView(rs.getObject(1, java.time.OffsetDateTime.class).toInstant(),
                rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), details(rs.getString(6))),
                orgId, s, (long) p * s);
        return PageResponse.of(items, p, s, total == null ? 0 : total);
    }

    private Map<String, Object> details(String text) {
        if (text == null || text.isBlank()) {
            return Map.of();
        }
        return json.readValue(text, new TypeReference<Map<String, Object>>() { });
    }
}
