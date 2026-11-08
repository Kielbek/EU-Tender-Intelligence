package eu.tenderintel.market;

import eu.tenderintel.billing.Entitlements;
import eu.tenderintel.billing.Plan;
import eu.tenderintel.common.error.ApiException;
import eu.tenderintel.market.MarketDtos.Competitor;
import eu.tenderintel.market.MarketDtos.CompetitorsView;
import eu.tenderintel.organization.OrganizationAccess;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Competitors an organization follows; their new wins become COMPETITOR_WON notifications. */
@Service
public class CompetitorService {

    private final JdbcTemplate jdbc;
    private final OrganizationAccess access;
    private final Entitlements entitlements;
    private final Clock clock;

    CompetitorService(JdbcTemplate jdbc, OrganizationAccess access, Entitlements entitlements, Clock clock) {
        this.jdbc = jdbc;
        this.access = access;
        this.entitlements = entitlements;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public CompetitorsView list(UUID orgId, UUID userId) {
        access.requireMember(orgId, userId);
        Plan plan = entitlements.plan(orgId);
        LocalDate since = LocalDate.now(clock.withZone(ZoneOffset.UTC)).minusMonths(plan.marketHistoryMonths());
        List<Competitor> items = jdbc.query("""
                select o.id, o.name, o.country_code, o.national_id,
                       count(distinct f.tender_id) as wins, sum(f.awarded_value_eur) as total, max(f.award_date),
                       w.note, w.created_at
                from watched_companies w
                join economic_operators o on o.id = w.operator_id
                left join award_facts f on f.winner_id = o.id and f.award_date >= ?
                where w.organization_id = ?
                group by o.id, o.name, o.country_code, o.national_id, w.note, w.created_at
                order by max(f.award_date) desc nulls last, o.name
                """, (rs, i) -> new Competitor(rs.getObject(1, UUID.class), rs.getString(2),
                rs.getString(3) == null ? null : rs.getString(3).trim(), rs.getString(4), rs.getLong(5),
                rs.getBigDecimal(6) == null ? null : rs.getBigDecimal(6).setScale(0, java.math.RoundingMode.HALF_UP),
                rs.getDate(7) == null ? null : rs.getDate(7).toLocalDate(), rs.getString(8),
                rs.getTimestamp(9).toInstant()), Date.valueOf(since), orgId);
        return new CompetitorsView(items, plan.watchedCompetitors(), plan.marketHistoryMonths());
    }

    @Transactional
    public void watch(UUID orgId, UUID userId, UUID operatorId, String note) {
        access.requireMember(orgId, userId);
        Integer exists = jdbc.queryForObject("select count(*) from economic_operators where id = ?", Integer.class,
                operatorId);
        if (exists == null || exists == 0) {
            throw ApiException.notFound("Company");
        }
        // Lock the organization so two requests cannot both take the last slot.
        String code = jdbc.queryForObject("select plan_code from organizations where id = ? for update", String.class,
                orgId);
        Plan plan = Plan.of(code);
        Integer already = jdbc.queryForObject("select count(*) from watched_companies where organization_id = ? "
                + "and operator_id = ?", Integer.class, orgId, operatorId);
        if (already != null && already > 0) {
            jdbc.update("update watched_companies set note = ? where organization_id = ? and operator_id = ?",
                    blankToNull(note), orgId, operatorId);
            return;
        }
        Integer used = jdbc.queryForObject("select count(*) from watched_companies where organization_id = ?",
                Integer.class, orgId);
        if (used != null && used >= plan.watchedCompetitors()) {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("plan", plan.name());
            props.put("limit", plan.watchedCompetitors());
            props.put("used", used);
            throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "competitor_limit_reached",
                    "The plan's limit of followed competitors is reached", props);
        }
        jdbc.update("insert into watched_companies (organization_id, operator_id, added_by, note) values (?, ?, ?, ?)",
                orgId, operatorId, userId, blankToNull(note));
    }

    @Transactional
    public void unwatch(UUID orgId, UUID userId, UUID operatorId) {
        access.requireMember(orgId, userId);
        jdbc.update("delete from watched_companies where organization_id = ? and operator_id = ?", orgId, operatorId);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
