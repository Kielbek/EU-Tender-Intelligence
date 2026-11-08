package eu.tenderintel.matching;

import eu.tenderintel.matching.MatchingEngine.GeoPoint;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * NUTS regions with their label points (Eurostat GISCO, static reference data from migration V18), kept in memory.
 * A code that is not known (e.g. from an older NUTS version) falls back to its nearest known parent region.
 */
@Component
public class NutsRegions {

    public record Region(String code, int level, String countryCode, String name, GeoPoint point) {
    }

    private final JdbcTemplate jdbc;
    private volatile Map<String, Region> regions;

    NutsRegions(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private Map<String, Region> all() {
        Map<String, Region> r = regions;
        if (r == null) {
            Map<String, Region> loaded = new HashMap<>();
            jdbc.query("select code, level, country_code, name, lat, lon from nuts_regions", rs -> {
                loaded.put(rs.getString(1), new Region(rs.getString(1), rs.getInt(2), rs.getString(3).trim(),
                        rs.getString(4), new GeoPoint(rs.getDouble(5), rs.getDouble(6))));
            });
            r = Map.copyOf(loaded);
            regions = r;
        }
        return r;
    }

    /** The region itself or its nearest known parent; null for blank or unknown codes. */
    public Region find(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String c = code.trim().toUpperCase(java.util.Locale.ROOT);
        Map<String, Region> map = all();
        while (c.length() >= 2) {
            Region r = map.get(c);
            if (r != null) {
                return r;
            }
            c = c.substring(0, c.length() - 1);
        }
        return null;
    }

    public GeoPoint point(String code) {
        Region r = find(code);
        return r == null ? null : r.point();
    }

    /** Regions of a country at a level (e.g. PL, 2 = voivodeships), sorted by name. */
    public List<Region> list(String country, int level) {
        return all().values().stream().filter(r -> r.countryCode().equals(country) && r.level() == level)
                .sorted(java.util.Comparator.comparing(Region::name)).toList();
    }

    public boolean exists(String code) {
        return code != null && all().containsKey(code);
    }
}
