package eu.tenderintel.market;

import eu.tenderintel.billing.Entitlements;
import eu.tenderintel.billing.Plan;
import eu.tenderintel.common.error.ApiException;
import eu.tenderintel.market.MarketDtos.AuthorityHit;
import eu.tenderintel.market.MarketDtos.AuthorityProfile;
import eu.tenderintel.market.MarketDtos.AuthorityRow;
import eu.tenderintel.market.MarketDtos.AuthoritySummary;
import eu.tenderintel.market.MarketDtos.AwardRow;
import eu.tenderintel.market.MarketDtos.Bucket;
import eu.tenderintel.market.MarketDtos.CompanyHit;
import eu.tenderintel.market.MarketDtos.CompanyProfile;
import eu.tenderintel.market.MarketDtos.CompetitionStats;
import eu.tenderintel.market.MarketDtos.Scope;
import eu.tenderintel.market.MarketDtos.TenderMarket;
import eu.tenderintel.market.MarketDtos.WinnerRow;
import eu.tenderintel.organization.OrganizationAccess;
import eu.tenderintel.tender.TenderQueries;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Date;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who wins, at what price and against how many competitors – computed from published award notices (TED, BZP) in
 * {@code award_facts} / {@code tender_award_summary}. Only facts from the notices: no estimates of win probability.
 * The time window depends on the organization's plan.
 */
@Service
@Transactional(readOnly = true)
public class MarketService {

    /** Statistics from fewer awards than this are not shown (they would mislead). */
    public static final int MIN_SAMPLE = 5;
    /** The comparison set for a tender is narrowed (CPV class → group → division) while it keeps this many awards. */
    static final int TARGET_SAMPLE = 20;

    private static final String RATIO = "(s.awarded_value_eur / s.estimated_value_eur)";
    /** Net prices only: BZP award prices are gross (with VAT) while estimates are net – see V17. */
    private static final String RATIO_OK = "s.net_value and s.awarded_value_eur > 0 and s.estimated_value_eur > 0 and "
            + RATIO
            + " between 0.1 and 2 and abs(" + RATIO + " - 1) > 0.005";
    private static final String OFFERS_OK = "s.tenders_received between 1 and 500";

    private final NamedParameterJdbcTemplate jdbc;
    private final OrganizationAccess access;
    private final Entitlements entitlements;
    private final TenderQueries tenders;
    private final Clock clock;

    MarketService(NamedParameterJdbcTemplate jdbc, OrganizationAccess access, Entitlements entitlements,
            TenderQueries tenders, Clock clock) {
        this.jdbc = jdbc;
        this.access = access;
        this.entitlements = entitlements;
        this.tenders = tenders;
        this.clock = clock;
    }

    /** A filter on the award views: {@code where} uses alias {@code s} for tender_award_summary-like columns. */
    private record Filter(String where, MapSqlParameterSource params) {
    }

    // --- tender: similar awards -----------------------------------------------------------------------------------

    public TenderMarket forTender(UUID orgId, UUID userId, UUID tenderId, String lang) {
        access.requireMember(orgId, userId);
        Plan plan = entitlements.plan(orgId);
        LocalDate since = since(plan);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select t.country_code, t.main_cpv, t.contracting_authority_id, ca.name as authority_name
                from tenders t left join contracting_authorities ca on ca.id = t.contracting_authority_id
                where t.id = :id
                """, Map.of("id", tenderId));
        if (rows.isEmpty()) {
            throw ApiException.notFound("Tender");
        }
        Map<String, Object> t = rows.get(0);
        String country = ((String) t.get("country_code")).trim();
        String cpv = (String) t.get("main_cpv");

        // The most specific CPV prefix that still has enough awards to compare with.
        String prefix = null;
        if (cpv != null) {
            for (int len : new int[] {5, 4, 3, 2}) {
                String candidate = cpv.substring(0, len);
                if (count(market(country, candidate, since, tenderId)) >= TARGET_SAMPLE || len == 2) {
                    prefix = candidate;
                    break;
                }
            }
        }
        Filter f = market(country, prefix, since, tenderId);
        AuthoritySummary authority = null;
        UUID authorityId = (UUID) t.get("contracting_authority_id");
        if (authorityId != null) {
            Filter a = authority(authorityId, since, tenderId);
            authority = new AuthoritySummary(authorityId, (String) t.get("authority_name"), stats(a), winners(a, 5));
        }
        return new TenderMarket(scope(country, prefix, lang, plan, since), stats(f), winners(f, 8), awards(f, 10),
                authority);
    }

    private Filter market(String country, String cpvPrefix, LocalDate since, UUID exclude) {
        MapSqlParameterSource p = new MapSqlParameterSource("country", country).addValue("since", Date.valueOf(since))
                .addValue("exclude", exclude);
        String where = "s.country_code = :country and s.award_date >= :since and s.tender_id <> :exclude";
        if (cpvPrefix != null) {
            where += " and s.main_cpv like :cpv";
            p.addValue("cpv", cpvPrefix + "%");
        }
        return new Filter(where, p);
    }

    private Filter authority(UUID authorityId, LocalDate since, UUID exclude) {
        return new Filter("s.authority_id = :authority and s.award_date >= :since and s.tender_id <> :exclude",
                new MapSqlParameterSource("authority", authorityId).addValue("since", Date.valueOf(since))
                        .addValue("exclude", exclude == null ? new UUID(0, 0) : exclude));
    }

    private Filter winner(UUID operatorId, LocalDate since) {
        return new Filter("s.winner_id = :winner and s.award_date >= :since",
                new MapSqlParameterSource("winner", operatorId).addValue("since", Date.valueOf(since)));
    }

    // --- authority profile ----------------------------------------------------------------------------------------

    public AuthorityProfile authorityProfile(UUID orgId, UUID userId, UUID authorityId, String lang) {
        access.requireMember(orgId, userId);
        Plan plan = entitlements.plan(orgId);
        LocalDate since = since(plan);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select name, country_code, city, national_id, website from contracting_authorities where id = :id
                """, Map.of("id", authorityId));
        if (rows.isEmpty()) {
            throw ApiException.notFound("Authority");
        }
        Map<String, Object> a = rows.get(0);
        MapSqlParameterSource p = new MapSqlParameterSource("id", authorityId).addValue("since", Date.valueOf(since))
                .addValue("lang", lang(lang));
        Map<String, Object> counts = jdbc.queryForMap("""
                select count(*) filter (where published_at >= :since) as published,
                       count(*) filter (where status = 'OPEN') as open_now,
                       count(*) filter (where status = 'CANCELLED' and published_at >= :since) as cancelled
                from tenders where contracting_authority_id = :id
                """, p);
        List<Bucket> topCpv = jdbc.query("""
                select left(t.main_cpv, 2) as division, coalesce(c.labels->>:lang, c.labels->>'en') as label,
                       count(*) as n
                from tenders t left join cpv_codes c on c.code = left(t.main_cpv, 2) || '000000'
                where t.contracting_authority_id = :id and t.published_at >= :since and t.main_cpv is not null
                group by 1, 2 order by n desc limit 6
                """, p, (rs, i) -> new Bucket(rs.getString(1), rs.getString(2), rs.getLong(3)));
        List<UUID> open = jdbc.queryForList("""
                select id from tenders where contracting_authority_id = :id and status = 'OPEN'
                order by submission_deadline nulls last limit 10
                """, p, UUID.class);
        Filter f = authority(authorityId, since, null);
        String country = a.get("country_code") == null ? null : ((String) a.get("country_code")).trim();
        return new AuthorityProfile(authorityId, (String) a.get("name"), country, (String) a.get("city"),
                (String) a.get("national_id"), (String) a.get("website"), scope(country, null, lang, plan, since),
                ((Number) counts.get("published")).longValue(), ((Number) counts.get("open_now")).longValue(),
                ((Number) counts.get("cancelled")).longValue(), stats(f), topCpv, winners(f, 10), awards(f, 15),
                List.copyOf(tenders.summaries(open, lang(lang)).values()));
    }

    // --- company (economic operator) profile ---------------------------------------------------------------------

    public CompanyProfile companyProfile(UUID orgId, UUID userId, UUID operatorId, String lang) {
        access.requireMember(orgId, userId);
        Plan plan = entitlements.plan(orgId);
        LocalDate since = since(plan);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select name, country_code, national_id, is_sme from economic_operators where id = :id
                """, Map.of("id", operatorId));
        if (rows.isEmpty()) {
            throw ApiException.notFound("Company");
        }
        Map<String, Object> o = rows.get(0);
        MapSqlParameterSource p = new MapSqlParameterSource("id", operatorId).addValue("since", Date.valueOf(since))
                .addValue("lang", lang(lang)).addValue("org", orgId);
        Map<String, Object> totals = jdbc.queryForMap("""
                select count(distinct f.tender_id) as wins, sum(f.awarded_value_eur) as total,
                       percentile_cont(0.5) within group (order by f.tenders_received)
                           filter (where f.tenders_received between 1 and 500) as med_offers
                from award_facts f where f.winner_id = :id and f.award_date >= :since
                """, p);
        List<Bucket> topCpv = jdbc.query("""
                select left(f.main_cpv, 2), coalesce(c.labels->>:lang, c.labels->>'en'), count(*) as n
                from award_facts f left join cpv_codes c on c.code = left(f.main_cpv, 2) || '000000'
                where f.winner_id = :id and f.award_date >= :since and f.main_cpv is not null
                group by 1, 2 order by n desc limit 6
                """, p, (rs, i) -> new Bucket(rs.getString(1), rs.getString(2), rs.getLong(3)));
        List<Bucket> countries = jdbc.query("""
                select f.country_code, f.country_code, count(*) as n from award_facts f
                where f.winner_id = :id and f.award_date >= :since group by 1 order by n desc limit 8
                """, p, (rs, i) -> new Bucket(rs.getString(1).trim(), rs.getString(2).trim(), rs.getLong(3)));
        List<AuthorityRow> authorities = jdbc.query("""
                select ca.id, ca.name, ca.country_code, count(*) as n, sum(f.awarded_value_eur)
                from award_facts f join contracting_authorities ca on ca.id = f.authority_id
                where f.winner_id = :id and f.award_date >= :since
                group by 1, 2, 3 order by n desc, 5 desc nulls last limit 8
                """, p, (rs, i) -> new AuthorityRow(rs.getObject(1, UUID.class), rs.getString(2),
                trim(rs.getString(3)), rs.getLong(4), rs.getBigDecimal(5)));
        Boolean watched = jdbc.queryForObject("select exists (select 1 from watched_companies "
                + "where organization_id = :org and operator_id = :id)", p, Boolean.class);
        String country = o.get("country_code") == null ? null : ((String) o.get("country_code")).trim();
        return new CompanyProfile(operatorId, (String) o.get("name"), country, (String) o.get("national_id"),
                (Boolean) o.get("is_sme"), scope(null, null, lang, plan, since),
                ((Number) totals.get("wins")).longValue(), (BigDecimal) totals.get("total"),
                (Double) totals.get("med_offers"), topCpv, countries, authorities,
                awards(winner(operatorId, since), 15), Boolean.TRUE.equals(watched));
    }

    // --- search ---------------------------------------------------------------------------------------------------

    /** By name (accent-insensitive, any part) or exact national id (e.g. NIP); companies with wins first. */
    public List<CompanyHit> searchCompanies(UUID orgId, UUID userId, String q) {
        access.requireMember(orgId, userId);
        String query = q == null ? "" : q.trim();
        if (query.length() < 2) {
            return List.of();
        }
        MapSqlParameterSource p = new MapSqlParameterSource("q", like(query)).addValue("id", digits(query));
        return jdbc.query("""
                select o.id, o.name, o.country_code, o.national_id,
                       (select count(distinct f.tender_id) from award_facts f where f.winner_id = o.id) as wins
                from economic_operators o
                where o.normalized_name like '%' || immutable_unaccent(lower(:q)) || '%'
                   or (:id <> '' and regexp_replace(o.national_id, '\\D', '', 'g') = :id)
                order by wins desc, o.name limit 20
                """, p, (rs, i) -> new CompanyHit(rs.getObject(1, UUID.class), rs.getString(2), trim(rs.getString(3)),
                rs.getString(4), rs.getLong(5)));
    }

    public List<AuthorityHit> searchAuthorities(UUID orgId, UUID userId, String q) {
        access.requireMember(orgId, userId);
        String query = q == null ? "" : q.trim();
        if (query.length() < 2) {
            return List.of();
        }
        MapSqlParameterSource p = new MapSqlParameterSource("q", like(query)).addValue("id", digits(query));
        return jdbc.query("""
                select a.id, a.name, a.country_code, a.city,
                       (select count(*) from tenders t where t.contracting_authority_id = a.id) as n
                from contracting_authorities a
                where a.normalized_name like '%' || immutable_unaccent(lower(:q)) || '%'
                   or (:id <> '' and regexp_replace(a.national_id, '\\D', '', 'g') = :id)
                order by n desc, a.name limit 20
                """, p, (rs, i) -> new AuthorityHit(rs.getObject(1, UUID.class), rs.getString(2), trim(rs.getString(3)),
                rs.getString(4), rs.getLong(5)));
    }

    // --- building blocks ------------------------------------------------------------------------------------------

    private long count(Filter f) {
        Long n = jdbc.queryForObject("select count(*) from tender_award_summary s where " + f.where(), f.params(),
                Long.class);
        return n == null ? 0 : n;
    }

    CompetitionStats stats(Filter f) {
        return jdbc.queryForObject("""
                select count(*),
                       percentile_cont(0.5) within group (order by s.tenders_received) filter (where %2$s),
                       avg((s.tenders_received = 1)::int) filter (where %2$s),
                       count(*) filter (where %2$s),
                       percentile_cont(array[0.25, 0.5, 0.75]) within group (order by %3$s) filter (where %1$s),
                       count(*) filter (where %1$s),
                       percentile_cont(0.5) within group (order by s.awarded_value_eur)
                           filter (where s.awarded_value_eur > 0)
                from tender_award_summary s where %4$s
                """.formatted(RATIO_OK, OFFERS_OK, RATIO, f.where()), f.params(), (rs, i) -> {
            long offersSample = rs.getLong(4);
            long ratioSample = rs.getLong(6);
            Double[] q = quartiles(rs.getArray(5));
            boolean ratios = ratioSample >= MIN_SAMPLE && q != null;
            boolean offers = offersSample >= MIN_SAMPLE;
            return new CompetitionStats(rs.getLong(1), offers ? round(rs.getDouble(2), 1) : null,
                    offers ? round(rs.getDouble(3), 3) : null, offersSample,
                    ratios ? round(q[0], 3) : null, ratios ? round(q[1], 3) : null, ratios ? round(q[2], 3) : null,
                    ratioSample, rs.getLong(1) >= MIN_SAMPLE ? scale(rs.getBigDecimal(7)) : null);
        });
    }

    List<WinnerRow> winners(Filter f, int limit) {
        f.params().addValue("limit", limit);
        return jdbc.query("""
                select o.id, o.name, o.country_code, count(distinct s.tender_id) as wins,
                       sum(s.awarded_value_eur) as total, max(s.award_date) as last_win
                from award_facts s join economic_operators o on o.id = s.winner_id
                where %s
                group by o.id, o.name, o.country_code
                order by wins desc, total desc nulls last limit :limit
                """.formatted(f.where()), f.params(), (rs, i) -> new WinnerRow(rs.getObject(1, UUID.class),
                rs.getString(2), trim(rs.getString(3)), rs.getLong(4), scale(rs.getBigDecimal(5)),
                rs.getDate(6) == null ? null : rs.getDate(6).toLocalDate()));
    }

    List<AwardRow> awards(Filter f, int limit) {
        f.params().addValue("limit", limit);
        return jdbc.query("""
                select s.tender_id, t.title, t.country_code, ca.id, ca.name, o.id, o.name, s.awarded_value_eur,
                       s.tenders_received, s.award_date
                from award_facts s
                join tenders t on t.id = s.tender_id
                join economic_operators o on o.id = s.winner_id
                left join contracting_authorities ca on ca.id = s.authority_id
                where %s
                order by s.award_date desc, s.tender_id limit :limit
                """.formatted(f.where()), f.params(), (rs, i) -> new AwardRow(rs.getObject(1, UUID.class),
                rs.getString(2), trim(rs.getString(3)), rs.getObject(4, UUID.class), rs.getString(5),
                rs.getObject(6, UUID.class), rs.getString(7), scale(rs.getBigDecimal(8)),
                (Integer) rs.getObject(9, Integer.class), rs.getDate(10) == null ? null : rs.getDate(10).toLocalDate()));
    }

    private Scope scope(String country, String cpvPrefix, String lang, Plan plan, LocalDate since) {
        String label = null;
        if (cpvPrefix != null) {
            String code = (cpvPrefix + "00000000").substring(0, 8);
            List<String> labels = jdbc.queryForList("select coalesce(labels->>:lang, labels->>'en') from cpv_codes "
                    + "where code = :code", new MapSqlParameterSource("lang", lang(lang)).addValue("code", code),
                    String.class);
            label = labels.isEmpty() ? null : labels.get(0);
        }
        // Coverage: from when most awards date (a handful of very old dates in notices would mislead).
        Date oldest = jdbc.queryForObject("select percentile_disc(0.05) within group (order by award_date) "
                + "from award_facts", Map.of(), Date.class);
        return new Scope(country, cpvPrefix, label, plan.marketHistoryMonths(), since,
                oldest == null ? null : oldest.toLocalDate());
    }

    LocalDate since(Plan plan) {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC)).minusMonths(plan.marketHistoryMonths());
    }

    private static Double[] quartiles(Array array) throws SQLException {
        if (array == null) {
            return null;
        }
        Object[] values = (Object[]) array.getArray();
        Double[] out = new Double[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = values[i] == null ? null : ((Number) values[i]).doubleValue();
        }
        return out.length == 3 && out[1] != null ? out : null;
    }

    private static Double round(double v, int digits) {
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }

    private static BigDecimal scale(BigDecimal v) {
        return v == null ? null : v.setScale(0, java.math.RoundingMode.HALF_UP);
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private static String like(String q) {
        return q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String digits(String q) {
        String d = q.replaceAll("\\D", "");
        return d.length() >= 6 && q.replaceAll("[\\d\\s\\-A-Za-z]", "").isEmpty() && d.length() * 2 >= q.length()
                ? d : "";
    }

    static String lang(String lang) {
        return lang == null || lang.isBlank() ? "en" : lang.toLowerCase(java.util.Locale.ROOT).substring(0,
                Math.min(2, lang.length()));
    }
}
