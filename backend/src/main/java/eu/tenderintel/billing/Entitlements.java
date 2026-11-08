package eu.tenderintel.billing;

import eu.tenderintel.common.error.ApiException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Enforces the plan limits from {@link Plan}. Every check reads the plan inside the caller's transaction and locks the
 * organization row, so two concurrent requests cannot both take the last free slot. Errors are {@code 402} with a
 * stable code, the limit and the current usage, so the UI can explain them and link to the billing page.
 */
@Service
public class Entitlements {

    private final JdbcTemplate jdbc;

    Entitlements(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Plan plan(UUID orgId) {
        String code = jdbc.query("select plan_code from organizations where id = ?",
                rs -> rs.next() ? rs.getString(1) : null, orgId);
        return Plan.of(code);
    }

    /** Members plus open invitations: an invitation reserves a seat until it is accepted, revoked or expires. */
    public long seatsUsed(UUID orgId) {
        Long n = jdbc.queryForObject("""
                select (select count(*) from organization_members where organization_id = ?)
                     + (select count(*) from organization_invitations where organization_id = ?
                        and accepted_at is null and revoked_at is null and expires_at > now())
                """, Long.class, orgId, orgId);
        return n == null ? 0 : n;
    }

    /**
     * Before inviting someone. {@code replacingInvitation} – a re-sent invitation to the same address replaces the
     * open one, so it does not need another seat.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireSeatForInvitation(UUID orgId, boolean replacingInvitation) {
        Plan plan = lockedPlan(orgId);
        if (plan.seats() == null) {
            return;
        }
        long used = seatsUsed(orgId) - (replacingInvitation ? 1 : 0);
        if (used >= plan.seats()) {
            throw limit("seat_limit_reached", "The plan's user limit is reached", plan, plan.seats(), used);
        }
    }

    /** When an invitation is accepted: its own reservation counts, so only members are compared with the limit. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireSeatForMember(UUID orgId) {
        Plan plan = lockedPlan(orgId);
        if (plan.seats() == null) {
            return;
        }
        Long members = jdbc.queryForObject("select count(*) from organization_members where organization_id = ?",
                Long.class, orgId);
        long used = members == null ? 0 : members;
        if (used >= plan.seats()) {
            throw limit("seat_limit_reached", "The plan's user limit is reached", plan, plan.seats(), used);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void requireSavedSearchSlot(UUID orgId, UUID userId) {
        Plan plan = lockedPlan(orgId);
        long used = savedSearchesUsed(orgId, userId);
        if (used >= plan.savedSearchesPerUser()) {
            throw limit("saved_search_limit_reached", "The plan's saved search limit is reached", plan,
                    plan.savedSearchesPerUser(), used);
        }
    }

    public long savedSearchesUsed(UUID orgId, UUID userId) {
        Long n = jdbc.queryForObject("select count(*) from saved_searches where organization_id = ? and user_id = ?",
                Long.class, orgId, userId);
        return n == null ? 0 : n;
    }

    /**
     * Counts one export against the monthly limit. The increment and the check are one statement, so the limit holds
     * under concurrency; in an unlimited plan exports are still counted (shown on the billing page).
     */
    @Transactional
    public void consumeExport(UUID orgId) {
        Plan plan = lockedPlan(orgId);
        LocalDate period = currentPeriod();
        jdbc.update("""
                insert into usage_counters (organization_id, metric, period_start, value) values (?, 'EXPORTS', ?, 0)
                on conflict do nothing
                """, orgId, period);
        int updated = plan.exportsPerMonth() == null
                ? jdbc.update("update usage_counters set value = value + 1 where organization_id = ? "
                        + "and metric = 'EXPORTS' and period_start = ?", orgId, period)
                : jdbc.update("update usage_counters set value = value + 1 where organization_id = ? "
                        + "and metric = 'EXPORTS' and period_start = ? and value < ?", orgId, period,
                        plan.exportsPerMonth());
        if (updated == 0) {
            throw limit("export_limit_reached", "The plan's monthly export limit is reached", plan,
                    plan.exportsPerMonth(), exportsUsed(orgId));
        }
    }

    public long exportsUsed(UUID orgId) {
        Integer n = jdbc.query("select value from usage_counters where organization_id = ? and metric = 'EXPORTS' "
                + "and period_start = ?", rs -> rs.next() ? rs.getInt(1) : 0, orgId, currentPeriod());
        return n == null ? 0 : n;
    }

    public static LocalDate currentPeriod() {
        return LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1);
    }

    private Plan lockedPlan(UUID orgId) {
        String code = jdbc.query("select plan_code from organizations where id = ? for update",
                rs -> rs.next() ? rs.getString(1) : null, orgId);
        if (code == null) {
            throw ApiException.notFound("Organization");
        }
        return Plan.of(code);
    }

    static ApiException limit(String code, String detail, Plan plan, Integer limit, long used) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("plan", plan.name());
        props.put("limit", limit);
        props.put("used", used);
        return new ApiException(HttpStatus.PAYMENT_REQUIRED, code, detail, props);
    }
}
