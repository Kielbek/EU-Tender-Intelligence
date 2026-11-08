package eu.tenderintel.matching;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Explainable, rule-based fit score (0–100) between a company profile and a tender. It measures how well the tender
 * fits the profile the company declared – it is NOT a probability of winning, and says nothing about competitors.
 *
 * <p>Components and maximum points (documented in docs/matching.md, shown to the user):
 * <ul>
 *   <li>sector (CPV) – 45: a tender code inside a declared code/prefix scores full points, closer siblings score more;</li>
 *   <li>location – 20: country of interest, or the registration country when no countries are declared;</li>
 *   <li>value – 15: estimated value (EUR) inside the declared contract value range;</li>
 *   <li>contract type – 10: works / supplies / services the company declared;</li>
 *   <li>experience – 10: a reference contract in the same CPV division.</li>
 * </ul>
 * Missing data never counts as a match: it scores a neutral half and is reported as missing information.
 */
public final class MatchingEngine {

    public static final int VERSION = 1;
    public static final int MAX_CPV = 45;
    public static final int MAX_LOCATION = 20;
    public static final int MAX_VALUE = 15;
    public static final int MAX_TYPE = 10;
    public static final int MAX_EXPERIENCE = 10;

    private MatchingEngine() {
    }

    public record Profile(List<String> cpvCodes, List<String> countriesOfInterest, String registrationCountry,
            List<String> contractTypes, BigDecimal minValueEur, BigDecimal maxValueEur, List<Reference> references) {
    }

    public record Reference(String title, String cpvCode) {
    }

    public record Tender(String countryCode, String mainCpv, List<String> cpvCodes, String contractType,
            BigDecimal valueEur) {
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

    public record Result(int score, List<Component> components, List<Reason> met, List<Reason> unmet,
            List<Reason> missing) {
    }

    public static Result score(Profile p, Tender t) {
        List<Reason> met = new ArrayList<>();
        List<Reason> unmet = new ArrayList<>();
        List<Reason> missing = new ArrayList<>();
        int cpv = cpv(p, t, met, unmet, missing);
        int location = location(p, t, met, unmet, missing);
        int value = value(p, t, met, unmet, missing);
        int type = type(p, t, met, unmet, missing);
        int experience = experience(p, t, met, unmet, missing);
        List<Component> components = List.of(new Component("cpv", cpv, MAX_CPV),
                new Component("location", location, MAX_LOCATION), new Component("value", value, MAX_VALUE),
                new Component("contractType", type, MAX_TYPE), new Component("experience", experience, MAX_EXPERIENCE));
        return new Result(cpv + location + value + type + experience, components, met, unmet, missing);
    }

    /** "45210000" → "4521": the significant prefix that a declared code covers. */
    static String significant(String code) {
        String s = code.replaceAll("0+$", "");
        return s.length() < 2 ? code.substring(0, 2) : s;
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
                    points = common >= 5 ? 36 : common == 4 ? 30 : common == 3 ? 22 : common == 2 ? 12 : 0;
                }
                if (points > best) {
                    best = points;
                    bestTender = code;
                    bestCompany = company;
                }
            }
        }
        if (best == MAX_CPV) {
            met.add(Reason.of("CPV_MATCH", "tenderCpv", bestTender, "profileCpv", bestCompany));
        } else if (best > 0) {
            met.add(Reason.of("CPV_RELATED", "tenderCpv", bestTender, "profileCpv", bestCompany));
        } else {
            unmet.add(Reason.of("CPV_OTHER_SECTOR", "tenderCpv", tenderCodes.getFirst()));
        }
        return best;
    }

    private static int location(Profile p, Tender t, List<Reason> met, List<Reason> unmet, List<Reason> missing) {
        if (!p.countriesOfInterest().isEmpty()) {
            if (p.countriesOfInterest().contains(t.countryCode())) {
                met.add(Reason.of("COUNTRY_OF_INTEREST", "country", t.countryCode()));
                return MAX_LOCATION;
            }
            unmet.add(Reason.of("COUNTRY_OUTSIDE", "country", t.countryCode()));
            return 0;
        }
        if (p.registrationCountry() != null && p.registrationCountry().equals(t.countryCode())) {
            met.add(Reason.of("HOME_COUNTRY", "country", t.countryCode()));
            return MAX_LOCATION;
        }
        missing.add(Reason.of("PROFILE_NO_COUNTRIES"));
        return MAX_LOCATION / 2;
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
        return 2;
    }
}
