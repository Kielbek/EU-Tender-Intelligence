package eu.tenderintel.matching;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Explainable, rule-based fit score (0–100) between a company profile and a tender. It measures how well the tender
 * fits what the company declared – it is NOT a probability of winning and says nothing about competitors.
 *
 * <p>Components and maximum points (docs/matching.md, shown to the user):
 * <ul>
 *   <li>sector (CPV) – 35;</li>
 *   <li>location – 25: distance between the company's base and the place of the tender (NUTS 3 label points), the
 *       declared working radius, extra regions, countries of interest; supplies can be nationwide;</li>
 *   <li>value – 15: estimated value (EUR) inside the declared range;</li>
 *   <li>contract type – 10;</li>
 *   <li>keywords – 10: words the company looks for in the title or description;</li>
 *   <li>experience – 5: a reference contract in the same CPV division.</li>
 * </ul>
 * Hard exclusions (score 0, never shown as a match): an excluded keyword or CPV code, and – in STRICT mode – a place
 * outside the working area or the countries of interest. Missing data never counts as a match: it scores a neutral
 * part and is reported as missing information.
 */
public final class MatchingEngine {

    public static final int VERSION = 2;
    public static final int MAX_CPV = 35;
    public static final int MAX_LOCATION = 25;
    public static final int MAX_VALUE = 15;
    public static final int MAX_TYPE = 10;
    public static final int MAX_KEYWORDS = 10;
    public static final int MAX_EXPERIENCE = 5;

    private MatchingEngine() {
    }

    /** A point on the map (NUTS label point). */
    public record GeoPoint(double lat, double lon) {
    }

    public record Profile(List<String> cpvCodes, List<String> countriesOfInterest, String registrationCountry,
            List<String> contractTypes, BigDecimal minValueEur, BigDecimal maxValueEur, List<Reference> references,
            String baseNuts, GeoPoint base, Integer maxDistanceKm, boolean strictRegion, List<String> extraRegions,
            boolean suppliesNationwide, List<String> includeKeywords, List<String> excludeKeywords,
            List<String> excludedCpv) {

        /** The profile as it was before location and keyword settings (tests, simple callers). */
        public static Profile basic(List<String> cpvCodes, List<String> countriesOfInterest, String registrationCountry,
                List<String> contractTypes, BigDecimal minValueEur, BigDecimal maxValueEur,
                List<Reference> references) {
            return new Profile(cpvCodes, countriesOfInterest, registrationCountry, contractTypes, minValueEur,
                    maxValueEur, references, null, null, null, false, List.of(), true, List.of(), List.of(),
                    List.of());
        }
    }

    public record Reference(String title, String cpvCode) {
    }

    /** {@code text}: title and description, used for keywords (may be null). */
    public record Tender(String countryCode, String mainCpv, List<String> cpvCodes, String contractType,
            BigDecimal valueEur, String nutsCode, GeoPoint place, String text) {

        public static Tender basic(String countryCode, String mainCpv, List<String> cpvCodes, String contractType,
                BigDecimal valueEur) {
            return new Tender(countryCode, mainCpv, cpvCodes, contractType, valueEur, null, null, null);
        }
    }

    /** A reason shown to the user: a stable code (translated in the UI) plus parameters. */
    public record Reason(String code, Map<String, Object> params) {
        static Reason of(String code, Object... kv) {
            Map<String, Object> p = new LinkedHashMap<>();
            for (int i = 0; i + 1 < kv.length; i += 2) {
                p.put((String) kv[i], kv[i + 1]);
            }
            return new Reason(code, p);
        }
    }

    public record Component(String key, int points, int max) {
    }

    /** {@code excluded}: a hard rule of the profile rules the tender out (score 0, reasons in {@code unmet}). */
    public record Result(int score, List<Component> components, List<Reason> met, List<Reason> unmet,
            List<Reason> missing, boolean excluded, Integer distanceKm) {
    }

    public static Result score(Profile p, Tender t) {
        List<Reason> met = new ArrayList<>();
        List<Reason> unmet = new ArrayList<>();
        List<Reason> missing = new ArrayList<>();
        List<Reason> exclusions = new ArrayList<>();
        String text = normalize(t.text());

        // Hard exclusions first: they are explained, but the tender is not a match whatever else fits.
        for (String word : p.excludeKeywords()) {
            String w = normalize(word);
            if (containsWord(text, w)) {
                exclusions.add(Reason.of("EXCLUDED_KEYWORD", "keyword", word));
                break;
            }
        }
        if (t.mainCpv() != null) {
            for (String code : p.excludedCpv()) {
                if (t.mainCpv().startsWith(significant(code))) {
                    exclusions.add(Reason.of("EXCLUDED_CPV", "tenderCpv", t.mainCpv(), "profileCpv", code));
                    break;
                }
            }
        }

        int cpv = cpv(p, t, met, unmet, missing);
        Location loc = location(p, t, met, unmet, missing);
        if (loc.excluded() != null) {
            exclusions.add(loc.excluded());
        }
        int value = value(p, t, met, unmet, missing);
        int type = type(p, t, met, unmet, missing);
        int keywords = keywords(p, text, met, unmet);
        int experience = experience(p, t, met, unmet, missing);
        List<Component> components = List.of(new Component("cpv", cpv, MAX_CPV),
                new Component("location", loc.points(), MAX_LOCATION), new Component("value", value, MAX_VALUE),
                new Component("contractType", type, MAX_TYPE), new Component("keywords", keywords, MAX_KEYWORDS),
                new Component("experience", experience, MAX_EXPERIENCE));
        if (!exclusions.isEmpty()) {
            List<Reason> u = new ArrayList<>(exclusions);
            u.addAll(unmet);
            return new Result(0, components, met, u, missing, true, loc.distanceKm());
        }
        return new Result(cpv + loc.points() + value + type + keywords + experience, components, met, unmet, missing,
                false, loc.distanceKm());
    }

    /** "45210000" → "4521": the significant prefix that a declared code covers. */
    static String significant(String code) {
        String s = code.replaceAll("0+$", "");
        return s.length() < 2 ? code.substring(0, Math.min(2, code.length())) : s;
    }

    private static int commonPrefix(String a, String b) {
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        return i;
    }

    private static int cpv(Profile p, Tender t, List<Reason> met, List<Reason> unmet, List<Reason> missing) {
        if (p.cpvCodes().isEmpty()) {
            missing.add(Reason.of("PROFILE_NO_CPV"));
            return 0;
        }
        List<String> tenderCodes = new ArrayList<>();
        if (t.mainCpv() != null) {
            tenderCodes.add(t.mainCpv());
        }
        t.cpvCodes().stream().filter(c -> !tenderCodes.contains(c)).forEach(tenderCodes::add);
        if (tenderCodes.isEmpty()) {
            missing.add(Reason.of("TENDER_NO_CPV"));
            return 0;
        }
        int best = 0;
        String bestTender = null;
        String bestCompany = null;
        for (String company : p.cpvCodes()) {
            String sig = significant(company);
            for (String code : tenderCodes) {
                int points;
                if (code.startsWith(sig)) {
                    points = MAX_CPV;
                } else {
                    int common = commonPrefix(significant(code), sig);
                    points = common >= 5 ? 28 : common == 4 ? 23 : common == 3 ? 17 : common == 2 ? 9 : 0;
                }
                // Additional (non-main) codes say less about what the contract is mostly about.
                if (!code.equals(t.mainCpv()) && points > 0) {
                    points = Math.max(1, points - 4);
                }
                if (points > best) {
                    best = points;
                    bestTender = code;
                    bestCompany = company;
                }
            }
        }
        if (best >= MAX_CPV - 4 && bestTender.startsWith(significant(bestCompany))) {
            met.add(Reason.of("CPV_MATCH", "tenderCpv", bestTender, "profileCpv", bestCompany));
        } else if (best > 0) {
            met.add(Reason.of("CPV_RELATED", "tenderCpv", bestTender, "profileCpv", bestCompany));
        } else {
            unmet.add(Reason.of("CPV_OTHER_SECTOR", "tenderCpv", tenderCodes.getFirst()));
        }
        return best;
    }

    private record Location(int points, Reason excluded, Integer distanceKm) {
    }

    private static Location location(Profile p, Tender t, List<Reason> met, List<Reason> unmet,
            List<Reason> missing) {
        // NUTS uses EL/UK where tenders use the ISO codes GR/GB.
        String home = p.baseNuts() != null ? NutsRegions.iso(p.baseNuts().substring(0, 2)) : p.registrationCountry();
        boolean countryOk = p.countriesOfInterest().isEmpty()
                ? home == null || home.equals(t.countryCode()) : p.countriesOfInterest().contains(t.countryCode());
        if (!countryOk) {
            Reason r = Reason.of("COUNTRY_OUTSIDE", "country", t.countryCode());
            unmet.add(r);
            return new Location(0, p.strictRegion() ? r : null, null);
        }
        Integer km = p.base() != null && t.place() != null ? (int) Math.round(distanceKm(p.base(), t.place())) : null;

        // Explicitly chosen regions (e.g. a whole voivodeship) always count as the working area.
        if (t.nutsCode() != null) {
            for (String region : p.extraRegions()) {
                if (t.nutsCode().startsWith(region)) {
                    met.add(Reason.of("IN_SELECTED_REGION", "region", region));
                    return new Location(MAX_LOCATION, null, km);
                }
            }
        }
        // Only for companies that do supplies at all: a works-only company keeps its radius for everything.
        boolean doesSupplies = p.contractTypes().isEmpty() || p.contractTypes().contains("SUPPLIES");
        if ("SUPPLIES".equals(t.contractType()) && p.suppliesNationwide() && doesSupplies && home != null
                && home.equals(t.countryCode())) {
            met.add(Reason.of("SUPPLIES_NATIONWIDE", "country", t.countryCode()));
            return new Location(MAX_LOCATION, null, km);
        }
        if (km == null) {
            if (p.base() == null) {
                missing.add(Reason.of("PROFILE_NO_BASE"));
            }
            if (t.place() == null) {
                missing.add(Reason.of("TENDER_NO_LOCATION"));
            }
            met.add(Reason.of("COUNTRY_OF_INTEREST", "country", t.countryCode()));
            return new Location(MAX_LOCATION * 3 / 5, null, null);
        }
        if (p.maxDistanceKm() != null) {
            int max = p.maxDistanceKm();
            if (km <= max) {
                // Full points in the nearer half of the radius, then down to 40 % at its edge.
                double share = km <= max / 2.0 ? 1.0 : 1.0 - 0.6 * (km - max / 2.0) / (max / 2.0);
                met.add(km < 5 ? Reason.of("SAME_AREA", "radius", max) : Reason.of("WITHIN_DISTANCE", "km", km, "radius", max));
                return new Location((int) Math.round(MAX_LOCATION * share), null, km);
            }
            Reason r = Reason.of("BEYOND_DISTANCE", "km", km, "radius", max);
            unmet.add(r);
            return new Location(0, p.strictRegion() ? r : null, km);
        }
        // No radius declared: nearer is still better, nothing is ruled out.
        int points = km <= 50 ? MAX_LOCATION : km <= 150 ? 20 : km <= 300 ? 14 : 9;
        met.add(km < 5 ? Reason.of("SAME_AREA") : Reason.of("DISTANCE", "km", km));
        return new Location(points, null, km);
    }

    /** Great-circle distance (haversine), km. */
    static double distanceKm(GeoPoint a, GeoPoint b) {
        double r = 6371.0;
        double dLat = Math.toRadians(b.lat() - a.lat());
        double dLon = Math.toRadians(b.lon() - a.lon());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(a.lat()))
                * Math.cos(Math.toRadians(b.lat())) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.min(1, Math.sqrt(h)));
    }

    private static int value(Profile p, Tender t, List<Reason> met, List<Reason> unmet, List<Reason> missing) {
        if (t.valueEur() == null) {
            missing.add(Reason.of("TENDER_VALUE_UNKNOWN"));
            return MAX_VALUE / 2;
        }
        if (p.minValueEur() == null && p.maxValueEur() == null) {
            missing.add(Reason.of("PROFILE_NO_VALUE_RANGE"));
            return MAX_VALUE / 2;
        }
        if (p.maxValueEur() != null && t.valueEur().compareTo(p.maxValueEur()) > 0) {
            unmet.add(Reason.of("VALUE_ABOVE_MAX", "value", t.valueEur(), "max", p.maxValueEur()));
            return 0;
        }
        if (p.minValueEur() != null && t.valueEur().compareTo(p.minValueEur()) < 0) {
            unmet.add(Reason.of("VALUE_BELOW_MIN", "value", t.valueEur(), "min", p.minValueEur()));
            return 4;
        }
        met.add(Reason.of("VALUE_IN_RANGE", "value", t.valueEur()));
        return MAX_VALUE;
    }

    private static int type(Profile p, Tender t, List<Reason> met, List<Reason> unmet, List<Reason> missing) {
        if (t.contractType() == null) {
            missing.add(Reason.of("TENDER_TYPE_UNKNOWN"));
            return MAX_TYPE / 2;
        }
        if (p.contractTypes().isEmpty()) {
            missing.add(Reason.of("PROFILE_NO_CONTRACT_TYPES"));
            return MAX_TYPE / 2;
        }
        if (p.contractTypes().contains(t.contractType())) {
            met.add(Reason.of("CONTRACT_TYPE", "type", t.contractType()));
            return MAX_TYPE;
        }
        unmet.add(Reason.of("CONTRACT_TYPE_OTHER", "type", t.contractType()));
        return 0;
    }

    /** Any of the declared words (accent- and case-insensitive, also as a word stem: "drog" finds "drogowe"). */
    private static int keywords(Profile p, String text, List<Reason> met, List<Reason> unmet) {
        if (p.includeKeywords().isEmpty()) {
            return MAX_KEYWORDS / 2;
        }
        List<String> found = new ArrayList<>();
        for (String word : p.includeKeywords()) {
            String w = normalize(word);
            if (containsWord(text, w)) {
                found.add(word);
            }
        }
        if (found.isEmpty()) {
            unmet.add(Reason.of("NO_KEYWORD"));
            return 0;
        }
        met.add(Reason.of("KEYWORD_MATCH", "keywords", String.join(", ", found)));
        return MAX_KEYWORDS;
    }

    private static int experience(Profile p, Tender t, List<Reason> met, List<Reason> unmet, List<Reason> missing) {
        if (p.references().isEmpty()) {
            missing.add(Reason.of("PROFILE_NO_REFERENCES"));
            return 0;
        }
        List<String> divisions = new ArrayList<>();
        if (t.mainCpv() != null) {
            divisions.add(t.mainCpv().substring(0, 2));
        }
        t.cpvCodes().forEach(c -> divisions.add(c.substring(0, 2)));
        for (Reference r : p.references()) {
            if (r.cpvCode() != null && divisions.contains(r.cpvCode().substring(0, 2))) {
                met.add(Reason.of("REFERENCE_SAME_SECTOR", "title", r.title()));
                return MAX_EXPERIENCE;
            }
        }
        unmet.add(Reason.of("NO_RELATED_REFERENCE"));
        return 1;
    }

    /** {@code word} at the start of a word of {@code text} (a stem: "drog" → "drogowe", not "podroze"). */
    static boolean containsWord(String text, String word) {
        if (word.isBlank()) {
            return false;
        }
        int from = 0;
        while (true) {
            int i = text.indexOf(word, from);
            if (i < 0) {
                return false;
            }
            if (i == 0 || !Character.isLetterOrDigit(text.charAt(i - 1))) {
                return true;
            }
            from = i + 1;
        }
    }

    /** Lower case, without diacritics (ł → l), single spaces: "Roboty DROGOWE – Łódź" → "roboty drogowe - lodz". */
    static String normalize(String s) {
        if (s == null) {
            return "";
        }
        String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT).replace('ł', 'l'), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return n.replaceAll("\\s+", " ").trim();
    }
}
