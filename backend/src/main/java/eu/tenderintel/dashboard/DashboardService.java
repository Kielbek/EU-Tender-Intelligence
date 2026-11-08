package eu.tenderintel.dashboard;

import eu.tenderintel.organization.OrganizationAccess;
import eu.tenderintel.tender.TenderDtos.TenderSummary;
import eu.tenderintel.tender.TenderQueries;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dashboard figures, all computed from stored data – nothing is estimated or invented. "Matching the company profile"
 * metrics are added with the matching engine (Stage 5).
 */
@Service
public class DashboardService {

    static final int CHART_DAYS = 30;

    private final JdbcTemplate jdbc;
    private final OrganizationAccess access;
    private final TenderQueries tenders;
    private final eu.tenderintel.matching.MatchingService matching;

    DashboardService(JdbcTemplate jdbc, OrganizationAccess access, TenderQueries tenders,
            eu.tenderintel.matching.MatchingService matching) {
        this.jdbc = jdbc;
        this.access = access;
        this.tenders = tenders;
        this.matching = matching;
    }

    /** Score from which a tender counts as a good fit on the dashboard. */
    static final int GOOD_MATCH = 60;

    public record Summary(boolean hasProfile, long goodMatches, List<eu.tenderintel.matching.MatchingService.MatchView> topMatches,
            long openTenders, long openCountries, long dueWithin7Days, long newLast7Days,
            long watchedActive, BigDecimal watchedValueEur,
            long watchedWithoutValue, List<TenderSummary> upcomingDeadlines, List<Bucket> topCpvDivisions,
            List<Bucket> byCountry, List<DayCount> noticesPerDay, List<Update> recentUpdates, String updatesScope,
            Instant lastIngestionAt,
            /* open tenders by contract type (WORKS, SUPPLIES, SERVICES, MIXED, UNKNOWN) */
            List<Bucket> byContractType,
            /* the team's watch-list by stage, with summed estimated value in EUR */
            List<Stage> pipeline,
            /* tenders first published per day, last 14 days (trend = last 7 vs previous 7) */
            List<DayCount> newPerDay,
            /* first-steps checklist: what the organization has already done */
            Onboarding onboarding) {
    }

    public record Onboarding(boolean profile, boolean watched, boolean savedSearch, boolean aiAnalysis,
            boolean teammate) {
    }

    public record Stage(String status, long count, BigDecimal valueEur) {
    }

    public record Bucket(String key, String label, long count) {
    }

    public record DayCount(LocalDate day, long count) {
    }

    public record Update(UUID tenderId, String title, String countryCode, String changeKind, Instant publishedAt,
            List<String> changedFields) {
    }

    @Transactional(readOnly = true)
    public Summary summary(UUID orgId, UUID userId, String lang) {
        access.requireMember(orgId, userId);
        String l = lang != null && lang.matches("[a-z]{2}") ? lang : "en";

        long open = count("select count(*) from tenders where status = 'OPEN'");
        long openCountries = count("select count(distinct country_code) from tenders where status = 'OPEN'");
        long dueSoon = count("select count(*) from tenders where status = 'OPEN' and submission_deadline > now() "
                + "and submission_deadline <= now() + interval '7 days'");
        long newTenders = count("select count(*) from tenders where published_at >= now() - interval '7 days'");
        Map<String, Object> watched = jdbc.queryForMap("""
                select count(*) as n, coalesce(sum(t.estimated_value_eur), 0) as v,
                       count(*) filter (where t.estimated_value_eur is null) as no_value
                from saved_tenders s join tenders t on t.id = s.tender_id
                where s.organization_id = ? and s.status in ('WATCHING', 'PREPARING', 'SUBMITTED')
                """, orgId);

        List<UUID> upcomingIds = jdbc.queryForList("""
                select t.id from saved_tenders s join tenders t on t.id = s.tender_id
                where s.organization_id = ? and s.status in ('WATCHING', 'PREPARING')
                  and t.status = 'OPEN' and t.submission_deadline > now()
                order by t.submission_deadline limit 8
                """, UUID.class, orgId);
        Map<UUID, TenderSummary> summaries = tenders.summaries(upcomingIds, l);
        List<TenderSummary> upcoming = upcomingIds.stream().map(summaries::get).filter(java.util.Objects::nonNull)
                .toList();

        List<Bucket> cpv = jdbc.query("""
                select d.division, coalesce(c.labels->>?, c.labels->>'en'), d.n from (
                    select left(main_cpv, 2) || '000000' as division, count(*) as n from tenders
                    where status = 'OPEN' and main_cpv is not null group by 1 order by 2 desc limit 8
                ) d left join cpv_codes c on c.code = d.division order by d.n desc
                """, (rs, i) -> new Bucket(rs.getString(1), rs.getString(2), rs.getLong(3)), l);
        List<Bucket> countries = jdbc.query("""
                select t.country_code, c.name_en, count(*) from tenders t join countries c on c.code = t.country_code
                where t.status = 'OPEN' group by 1, 2 order by 3 desc limit 12
                """, (rs, i) -> new Bucket(rs.getString(1), rs.getString(2), rs.getLong(3)));
        List<DayCount> perDay = jdbc.query("""
                select d::date, coalesce(v.n, 0) from generate_series(
                    (now() at time zone 'Europe/Brussels')::date - (? - 1), (now() at time zone 'Europe/Brussels')::date,
                    interval '1 day') d
                left join (select (published_at at time zone 'Europe/Brussels')::date as day, count(*) as n
                           from tender_versions
                           where published_at >= now() - make_interval(days => ? + 1) group by 1) v on v.day = d::date
                order by 1
                """, (rs, i) -> new DayCount(rs.getObject(1, LocalDate.class), rs.getLong(2)), CHART_DAYS,
                CHART_DAYS);

        long watchedCount = ((Number) watched.get("n")).longValue();
        String scope = watchedCount > 0 ? "WATCHED" : "ALL";
        List<Update> updates = jdbc.query("""
                select t.id, t.title, t.country_code, v.change_kind, v.published_at,
                       coalesce((select array_agg(e->>'field') from jsonb_array_elements(v.diff) e), '{}')
                from tender_versions v join tenders t on t.id = v.tender_id
                where v.change_kind in ('CHANGE', 'CANCELLATION', 'AWARD')
                  and (? = 'ALL' or exists (select 1 from saved_tenders s
                                            where s.organization_id = ? and s.tender_id = t.id))
                order by v.published_at desc, v.order_key desc limit 10
                """, (rs, i) -> new Update(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getTimestamp(5).toInstant(),
                List.of((String[]) rs.getArray(6).getArray())), scope, orgId);

        Timestamp last = jdbc.query("""
                select max(j.finished_at) from ingestion_jobs j join data_sources d on d.id = j.data_source_id
                where d.code in ('TED', 'PL_EZAMOWIENIA') and j.status in ('SUCCEEDED', 'PARTIAL')
                """, rs -> rs.next() ? rs.getTimestamp(1) : null);

        boolean hasProfile = matching.hasProfile(orgId);
        long good = hasProfile ? matching.countAtLeast(orgId, GOOD_MATCH) : 0;
        List<eu.tenderintel.matching.MatchingService.MatchView> top = hasProfile
                ? matching.list(orgId, userId, GOOD_MATCH, 0, 5, l).items() : List.of();
        List<Bucket> byType = jdbc.query("""
                select coalesce(contract_type, 'UNKNOWN'), count(*) from tenders where status = 'OPEN'
                group by 1 order by 2 desc
                """, (rs, i) -> new Bucket(rs.getString(1), null, rs.getLong(2)));
        List<Stage> pipeline = jdbc.query("""
                select s.status, count(*), coalesce(sum(t.estimated_value_eur), 0)
                from saved_tenders s join tenders t on t.id = s.tender_id
                where s.organization_id = ? group by 1
                """, (rs, i) -> new Stage(rs.getString(1), rs.getLong(2), rs.getBigDecimal(3)), orgId);
        List<DayCount> newPerDay = jdbc.query("""
                select d::date, coalesce(v.n, 0) from generate_series(
                    (now() at time zone 'Europe/Brussels')::date - 13, (now() at time zone 'Europe/Brussels')::date,
                    interval '1 day') d
                left join (select (published_at at time zone 'Europe/Brussels')::date as day, count(*) as n
                           from tenders where published_at >= now() - interval '15 days' group by 1) v
                  on v.day = d::date
                order by 1
                """, (rs, i) -> new DayCount(rs.getObject(1, LocalDate.class), rs.getLong(2)));
        Onboarding onboarding = jdbc.queryForObject("""
                select exists (select 1 from saved_tenders where organization_id = ?),
                       exists (select 1 from saved_searches where organization_id = ?),
                       exists (select 1 from ai_usage where organization_id = ?)
                         or exists (select 1 from ai_analyses where requested_by_org = ?),
                       (select count(*) from organization_members where organization_id = ?) > 1
                """, (rs, i) -> new Onboarding(hasProfile, rs.getBoolean(1), rs.getBoolean(2), rs.getBoolean(3),
                rs.getBoolean(4)), orgId, orgId, orgId, orgId, orgId);
        return new Summary(hasProfile, good, top, open, openCountries, dueSoon, newTenders, watchedCount, (BigDecimal) watched.get("v"),
                ((Number) watched.get("no_value")).longValue(), upcoming, cpv, countries, perDay, updates, scope,
                last == null ? null : last.toInstant(), byType, pipeline, newPerDay, onboarding);
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }
}
