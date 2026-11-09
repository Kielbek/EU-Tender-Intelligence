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

    /** {@code countryCode}: ISO 3166 (GR, GB) – NUTS uses EL and UK for these two countries. */
    public record Region(String code, int level, String countryCode, String name, GeoPoint point, int version) {
    }

    /** NUTS country prefix → ISO 3166 code used everywhere else in the app (tenders, profiles). */
    public static String iso(String nutsCountry) {
        if (nutsCountry == null) {
            return null;
        }
        return switch (nutsCountry) {
            case "EL" -> "GR";
            case "UK" -> "GB";
            default -> nutsCountry;
        };
    }

    /** ISO 3166 code → NUTS country prefix. */
    public static String nutsCountry(String iso) {
        if (iso == null) {
            return null;
        }
        return switch (iso) {
            case "GR" -> "EL";
            case "GB" -> "UK";
            default -> iso;
        };
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
            jdbc.query("select code, level, country_code, name, lat, lon, nuts_version from nuts_regions", rs -> {
                loaded.put(rs.getString(1), new Region(rs.getString(1), rs.getInt(2), iso(rs.getString(3).trim()),
                        rs.getString(4), new GeoPoint(rs.getDouble(5), rs.getDouble(6)), rs.getInt(7)));
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

    /**
     * Regions of a country (ISO code, e.g. PL or GR) at a level (0 = the country, 2 = voivodeships in PL), sorted by
     * name; {@code country} null = every country, sorted by country and name.
     */
    public List<Region> list(String country, int level) {
        String iso = country == null ? null : iso(nutsCountry(country));
        List<Region> atLevel = all().values().stream()
                .filter(r -> r.level() == level && (iso == null || r.countryCode().equals(iso))).toList();
        // Older NUTS versions stay in the table for codes in older notices, but are not offered for choosing.
        Map<String, Integer> newest = new java.util.HashMap<>();
        atLevel.forEach(r -> newest.merge(r.countryCode(), r.version(), Math::max));
        return atLevel.stream().filter(r -> r.version() == newest.get(r.countryCode()))
                .sorted(java.util.Comparator.comparing(Region::countryCode).thenComparing(Region::name)).toList();
    }

    public boolean exists(String code) {
        return code != null && all().containsKey(code);
    }
}
