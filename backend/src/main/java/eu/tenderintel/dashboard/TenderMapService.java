package eu.tenderintel.dashboard;

import eu.tenderintel.common.error.ApiException;
import eu.tenderintel.matching.NutsRegions;
import eu.tenderintel.matching.NutsRegions.Region;
import eu.tenderintel.organization.OrganizationAccess;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Open tenders on a map: counts per country (level 0), NUTS 2 region or NUTS 3 subregion, placed at the region's
 * Eurostat label point. The place of a tender is its NUTS code (the place of performance in TED, the contracting
 * authority in BZP); tenders without a region are counted only per country ({@code withoutRegion}).
 */
@Service
public class TenderMapService {

    /** Matches shown on the map: the same threshold as the dashboard's "good matches". */
    static final int MATCH_MIN_SCORE = 60;
    static final Duration CACHE = Duration.ofMinutes(5);

    public enum Scope { ALL, MATCHES }

    public record MapPoint(String code, String name, String country, double lat, double lon, long count) {
    }

    public record TenderMap(int level, Scope scope, long total, long withoutRegion, List<MapPoint> points) {
    }

    private record Cached(TenderMap map, Instant at) {
    }

    private final JdbcTemplate jdbc;
    private final NutsRegions nuts;
    private final OrganizationAccess access;
    private final Clock clock;
    private final Map<Integer, Cached> allCache = new ConcurrentHashMap<>();

    TenderMapService(JdbcTemplate jdbc, NutsRegions nuts, OrganizationAccess access, Clock clock) {
        this.jdbc = jdbc;
        this.nuts = nuts;
        this.access = access;
        this.clock = clock;
    }

    public TenderMap map(UUID orgId, UUID userId, int level, Scope scope) {
        access.requireMember(orgId, userId);
        if (level != 0 && level != 2 && level != 3) {
            throw ApiException.badRequest("invalid_level", "level must be 0, 2 or 3");
        }
        if (scope == Scope.ALL) {
            Cached c = allCache.get(level);
            if (c != null && c.at().plus(CACHE).isAfter(clock.instant())) {
                return c.map();
            }
            TenderMap m = build(level, scope, null);
            allCache.put(level, new Cached(m, clock.instant()));
            return m;
        }
        return build(level, scope, orgId);
    }

    private TenderMap build(int level, Scope scope, UUID orgId) {
        String from = scope == Scope.ALL ? "tenders t where t.status = 'OPEN'" : """
                tender_matches m join tenders t on t.id = m.tender_id
                where m.organization_id = ? and m.score >= ? and t.status = 'OPEN'
                  and not exists (select 1 from match_dismissals d where d.organization_id = m.organization_id
                                  and d.tender_id = m.tender_id)
                  and (t.submission_deadline is null or t.submission_deadline >= now() + make_interval(days =>
                       coalesce((select p.min_days_to_deadline from company_profiles p
                                 where p.organization_id = m.organization_id), 0)))
                """;
        Object[] args = scope == Scope.ALL ? new Object[0] : new Object[] {orgId, MATCH_MIN_SCORE};
        int len = level == 0 ? 0 : level + 2;
        String key = level == 0 ? "t.country_code" : "case when length(t.nuts_code) >= " + len
                + " then substr(t.nuts_code, 1, " + len + ") end";
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.query("select " + key + ", count(*) from " + from + " group by 1", rs -> {
            counts.merge(rs.getString(1) == null ? "" : rs.getString(1), rs.getLong(2), Long::sum);
        }, args);

        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        long without = 0;
        Map<String, MapPoint> points = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : counts.entrySet()) {
            Region r = e.getKey().isEmpty() ? null
                    : nuts.find(level == 0 ? NutsRegions.nutsCountry(e.getKey()) : e.getKey());
            // Codes such as PLZZZ ("extra-regio") or unknown ones fall back to a coarser region: not placeable here.
            if (r == null || r.level() != level || r.point() == null) {
                without += e.getValue();
                continue;
            }
            String iso = level == 0 ? e.getKey() : r.countryCode();
            points.merge(r.code(), new MapPoint(r.code(), r.name(), iso, r.point().lat(), r.point().lon(), e.getValue()),
                    (a, b) -> new MapPoint(a.code(), a.name(), a.country(), a.lat(), a.lon(), a.count() + b.count()));
        }
        List<MapPoint> list = new ArrayList<>(points.values());
        list.sort(Comparator.comparingLong(MapPoint::count).reversed());
        return new TenderMap(level, scope, total, without, list);
    }
}
