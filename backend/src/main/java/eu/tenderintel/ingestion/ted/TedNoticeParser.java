package eu.tenderintel.ingestion.ted;

import eu.tenderintel.ingestion.ChangeKind;
import eu.tenderintel.ingestion.CountryResolver;
import eu.tenderintel.ingestion.NormalizedNotice;
import eu.tenderintel.ingestion.NormalizedNotice.FormType;
import eu.tenderintel.ingestion.NormalizedNotice.Lot;
import eu.tenderintel.ingestion.NormalizedNotice.Party;
import eu.tenderintel.ingestion.NormalizedNotice.Winner;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Maps one TED Search API v3 notice (eForms fields, JSON) to a {@link NormalizedNotice}. Rules were derived from a full
 * publication day of real notices (2026-11-06, 3 799 notices); see docs/ted-integration.md.
 *
 * <p>Conventions of the API: list fields often repeat values (one per lot/section) and are de-duplicated; texts are
 * maps from ISO 639-2/T language to value(s); dates carry an offset ("2026-11-13+02:00") and deadline times are a
 * separate field ("15:00:00+02:00").
 */
public final class TedNoticeParser {

    /** Fields requested from the Search API. fields × page size must stay ≤ 10 000 (API limit). */
    public static final List<String> FIELDS = List.of("publication-number", "notice-type", "form-type",
            "publication-date", "procedure-identifier", "title-proc", "description-proc", "buyer-name",
            "buyer-country", "buyer-city", "buyer-identifier", "buyer-internet-address", "place-of-performance",
            "place-of-performance-country-proc", "place-of-performance-city-proc", "classification-cpv",
            "main-classification-proc", "contract-nature-main-proc", "procedure-type", "estimated-value-proc",
            "estimated-value-cur-proc", "estimated-value-glo", "estimated-value-cur-glo",
            "deadline-receipt-tender-date-lot", "deadline-receipt-tender-time-lot", "document-url-lot",
            "internal-identifier-proc", "identifier-lot", "title-lot", "estimated-value-lot",
            "estimated-value-cur-lot", "change-reason-code", "change-notice-version-identifier", "winner-name",
            "winner-country", "winner-identifier", "winner-size", "tender-value", "tender-value-cur",
            "received-submissions-type-code", "received-submissions-type-val", "winner-decision-date", "links");

    private static final Pattern PUBLICATION_NUMBER = Pattern.compile("^(\\d{1,8})-(\\d{4})$");
    private static final Pattern NUTS = Pattern.compile("^[A-Z]{2}[0-9A-Z]{1,3}$");
    private static final Pattern CPV = Pattern.compile("^\\d{8}$");
    private static final int MAX_TITLE = 1000;
    private static final int MAX_DESCRIPTION = 20_000;

    private TedNoticeParser() {
    }

    public static NormalizedNotice parse(JsonNode n) {
        String publicationNumber = text(n, "publication-number")
                .orElseThrow(() -> new IllegalArgumentException("notice without publication-number"));
        LocalDateWithOffset published = parseDate(text(n, "publication-date")
                .orElseThrow(() -> new IllegalArgumentException("notice without publication-date")));
        String formTypeCode = text(n, "form-type").orElse("");
        FormType formType = formType(formTypeCode);
        String changeReason = text(n, "change-reason-code").orElse(null);
        boolean isChange = changeReason != null || has(n, "change-notice-version-identifier");

        Map.Entry<String, String> title = firstText(n.get("title-proc"));
        String titleLang = title == null ? null : CountryResolver.language2(title.getKey());
        String description = textInLanguage(n.get("description-proc"), title == null ? null : title.getKey());

        String buyerCountry = CountryResolver.alpha2(first(n, "buyer-country"));
        String country = buyerCountry != null ? buyerCountry
                : CountryResolver.alpha2(first(n, "place-of-performance-country-proc"));
        if (country == null) {
            throw new IllegalArgumentException("notice " + publicationNumber + " has no resolvable country");
        }
        Party buyer = new Party(clean(textInLanguage(n.get("buyer-name"), title == null ? null : title.getKey()), 500),
                first(n, "buyer-identifier"), buyerCountry, textInLanguage(n.get("buyer-city"), null),
                first(n, "buyer-internet-address"));

        List<String> cpv = distinct(n, "classification-cpv").stream().filter(c -> CPV.matcher(c).matches()).toList();
        String mainCpv = Optional.ofNullable(first(n, "main-classification-proc")).filter(c -> CPV.matcher(c).matches())
                .orElse(cpv.isEmpty() ? null : cpv.getFirst());

        BigDecimal value = decimal(first(n, "estimated-value-proc"));
        String currency = first(n, "estimated-value-cur-proc");
        if (value == null) {
            value = decimal(first(n, "estimated-value-glo"));
            currency = first(n, "estimated-value-cur-glo");
        }

        List<String> deadlineDates = list(n, "deadline-receipt-tender-date-lot");
        List<String> deadlineTimes = list(n, "deadline-receipt-tender-time-lot");
        List<Instant> deadlines = new ArrayList<>();
        String deadlineTz = null;
        for (int i = 0; i < deadlineDates.size(); i++) {
            OffsetDateTime d = deadline(deadlineDates.get(i), i < deadlineTimes.size() ? deadlineTimes.get(i) : null);
            if (d != null) {
                deadlines.add(d.toInstant());
                deadlineTz = deadlineTz == null ? d.getOffset().getId() : deadlineTz;
            }
        }

        List<Lot> lots = lots(n, deadlines, deadlineDates.size());
        List<Winner> winners = formType == FormType.RESULT || formType == FormType.CONTRACT_MODIFICATION
                || formType == FormType.DIRECT_AWARD ? winners(n) : List.of();

        String procedureKey = text(n, "procedure-identifier").map(id -> "eforms:" + id.toLowerCase())
                .orElse("ted:" + publicationNumber);

        return new NormalizedNotice(publicationNumber, orderKey(publicationNumber), externalUrl(n, publicationNumber),
                text(n, "notice-type").orElse(null), formType, changeKind(formType, changeReason, isChange),
                changeReason, published.start(), published.date(), procedureKey,
                clean(title == null ? publicationNumber : title.getValue(), MAX_TITLE), titleLang,
                clean(description, MAX_DESCRIPTION), buyer, country, nuts(n),
                Optional.ofNullable(first(n, "place-of-performance-city-proc")).orElse(buyer.city()),
                contractType(text(n, "contract-nature-main-proc").orElse(null)),
                text(n, "procedure-type").orElse(null), mainCpv, cpv, value, value == null ? null : currency,
                deadlines, deadlineTz, clean(text(n, "internal-identifier-proc").orElse(null), 200),
                first(n, "document-url-lot"), lots, winners, tendersReceived(n), awardDate(n), n.toString());
    }

    static FormType formType(String code) {
        return switch (code) {
            case "planning" -> FormType.PLANNING;
            case "competition" -> FormType.COMPETITION;
            case "result" -> FormType.RESULT;
            case "dir-awa-pre" -> FormType.DIRECT_AWARD;
            case "cont-modif" -> FormType.CONTRACT_MODIFICATION;
            default -> FormType.OTHER;
        };
    }

    static ChangeKind changeKind(FormType form, String reason, boolean isChange) {
        if ("cancel".equals(reason) || "cancel-intent".equals(reason)) {
            return ChangeKind.CANCELLATION;
        }
        return switch (form) {
            case PLANNING -> isChange ? ChangeKind.CHANGE : ChangeKind.PRIOR_INFORMATION;
            case COMPETITION -> isChange ? ChangeKind.CHANGE : ChangeKind.COMPETITION;
            // A changed result notice still publishes the outcome (e.g. "update-add" with the winner details).
            case RESULT, DIRECT_AWARD -> ChangeKind.AWARD;
            case CONTRACT_MODIFICATION -> ChangeKind.MODIFICATION;
            case OTHER -> ChangeKind.OTHER;
        };
    }

    /** "771348-2026" → 2026_00771348: monotonic in publication order. */
    static long orderKey(String publicationNumber) {
        Matcher m = PUBLICATION_NUMBER.matcher(publicationNumber);
        if (!m.matches()) {
            throw new IllegalArgumentException("unexpected publication number " + publicationNumber);
        }
        return Long.parseLong(m.group(2)) * 100_000_000L + Long.parseLong(m.group(1));
    }

    record LocalDateWithOffset(LocalDate date, Instant start) {
    }

    /** "2026-11-06+01:00" or "2026-11-06Z". */
    static LocalDateWithOffset parseDate(String value) {
        LocalDate date = LocalDate.parse(value.substring(0, 10));
        ZoneOffset offset = value.length() > 10 ? ZoneOffset.of(value.substring(10)) : ZoneOffset.UTC;
        return new LocalDateWithOffset(date, date.atStartOfDay().atOffset(offset).toInstant());
    }

    /**
     * Combines "2026-11-13+02:00" and "15:00:00+02:00". Without a time, the start of the day is used – never later
     * than the real deadline, so a bidder is never misled into submitting late.
     */
    static OffsetDateTime deadline(String date, String time) {
        if (date == null || date.length() < 10) {
            return null;
        }
        LocalDateWithOffset d = parseDate(date);
        ZoneOffset dateOffset = date.length() > 10 ? ZoneOffset.of(date.substring(10)) : ZoneOffset.UTC;
        if (time == null || time.length() < 8) {
            return d.date().atStartOfDay().atOffset(dateOffset);
        }
        LocalTime t = LocalTime.parse(time.substring(0, 8));
        ZoneOffset timeOffset = time.length() > 8 ? ZoneOffset.of(time.substring(8)) : dateOffset;
        return d.date().atTime(t).atOffset(timeOffset);
    }

    private static List<Lot> lots(JsonNode n, List<Instant> deadlines, int deadlineCount) {
        List<String> ids = list(n, "identifier-lot");
        if (ids.size() < 2) {
            return List.of(); // single-lot procedures are fully described by the procedure-level fields
        }
        Map.Entry<String, JsonNode> titles = firstEntry(n.get("title-lot"));
        List<String> lotTitles = titles == null ? List.of() : values(titles.getValue());
        List<String> values = list(n, "estimated-value-lot");
        List<String> currencies = list(n, "estimated-value-cur-lot");
        List<Lot> lots = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            BigDecimal v = values.size() == ids.size() ? decimal(values.get(i)) : null;
            String cur = v == null ? null : currencies.size() == ids.size() ? currencies.get(i)
                    : currencies.size() == 1 ? currencies.getFirst() : null;
            lots.add(new Lot(ids.get(i), lotTitles.size() == ids.size() ? clean(lotTitles.get(i), MAX_TITLE) : null,
                    cur == null ? null : v, cur, deadlineCount == ids.size() && deadlines.size() == ids.size()
                            ? deadlines.get(i) : null));
        }
        return lots;
    }

    /**
     * Winners from the flat Search API lists. Values are attached only when the lists have equal length; otherwise the
     * winner is stored without a value rather than with a guessed one.
     */
    private static List<Winner> winners(JsonNode n) {
        Map.Entry<String, JsonNode> names = firstEntry(n.get("winner-name"));
        if (names == null) {
            return List.of();
        }
        List<String> nameList = values(names.getValue());
        List<String> countries = list(n, "winner-country");
        List<String> ids = list(n, "winner-identifier");
        List<String> sizes = list(n, "winner-size");
        List<String> amounts = list(n, "tender-value");
        List<String> currencies = list(n, "tender-value-cur");
        int count = nameList.size();
        List<Winner> winners = new ArrayList<>();
        java.util.Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < count; i++) {
            String name = clean(nameList.get(i), 500);
            BigDecimal value = amounts.size() == count ? decimal(amounts.get(i)) : null;
            String cur = value == null ? null : currencies.size() == count ? currencies.get(i)
                    : currencies.size() == 1 ? currencies.getFirst() : null;
            String key = name + "|" + (value == null ? "" : value.toPlainString());
            if (name == null || !seen.add(key)) {
                continue;
            }
            winners.add(new Winner(name, ids.size() == count ? ids.get(i) : null,
                    countries.size() == count ? CountryResolver.alpha2(countries.get(i)) : null,
                    sizes.size() == count ? sizes.get(i) : null, cur == null ? null : value, cur));
        }
        return winners;
    }

    /** Number of tenders received – only when the notice reports exactly one "tenders" figure. */
    private static Integer tendersReceived(JsonNode n) {
        List<String> codes = list(n, "received-submissions-type-code");
        List<String> vals = list(n, "received-submissions-type-val");
        if (codes.size() != vals.size()) {
            return null;
        }
        Integer result = null;
        for (int i = 0; i < codes.size(); i++) {
            if ("tenders".equals(codes.get(i))) {
                if (result != null) {
                    return null;
                }
                try {
                    result = Integer.valueOf(vals.get(i));
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return result;
    }

    private static LocalDate awardDate(JsonNode n) {
        return list(n, "winner-decision-date").stream().filter(s -> s.length() >= 10)
                .map(s -> LocalDate.parse(s.substring(0, 10))).min(LocalDate::compareTo).orElse(null);
    }

    private static String nuts(JsonNode n) {
        return list(n, "place-of-performance").stream()
                .filter(c -> NUTS.matcher(c).matches() && c.chars().anyMatch(Character::isDigit))
                .max(java.util.Comparator.comparingInt(String::length)).orElse(null);
    }

    private static String contractType(String nature) {
        if (nature == null) {
            return null;
        }
        return switch (nature) {
            case "works" -> "WORKS";
            case "supplies" -> "SUPPLIES";
            case "services" -> "SERVICES";
            default -> null;
        };
    }

    private static String externalUrl(JsonNode n, String publicationNumber) {
        JsonNode html = n.path("links").path("html").path("ENG");
        return html.isString() ? html.asString() : "https://ted.europa.eu/en/notice/-/detail/" + publicationNumber;
    }

    // --- JSON helpers -------------------------------------------------------------------------------------------

    private static boolean has(JsonNode n, String field) {
        return n.hasNonNull(field);
    }

    private static Optional<String> text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            return Optional.empty();
        }
        if (v.isArray()) {
            return v.isEmpty() ? Optional.empty() : Optional.of(v.get(0).asString());
        }
        return Optional.of(v.asString());
    }

    private static String first(JsonNode n, String field) {
        List<String> l = list(n, field);
        return l.isEmpty() ? null : l.getFirst();
    }

    /** All values of a list field, in order, without consecutive duplicates removed (alignment matters). */
    private static List<String> list(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            return List.of();
        }
        return values(v);
    }

    private static List<String> distinct(JsonNode n, String field) {
        return List.copyOf(new LinkedHashSet<>(list(n, field)));
    }

    private static List<String> values(JsonNode v) {
        List<String> out = new ArrayList<>();
        if (v.isArray()) {
            v.forEach(x -> out.add(x.asString()));
        } else if (!v.isNull()) {
            out.add(v.asString());
        }
        return out;
    }

    private static Map.Entry<String, JsonNode> firstEntry(JsonNode map) {
        if (map == null || !map.isObject()) {
            return null;
        }
        Iterator<Map.Entry<String, JsonNode>> it = map.properties().iterator();
        return it.hasNext() ? it.next() : null;
    }

    private static Map.Entry<String, String> firstText(JsonNode map) {
        Map.Entry<String, JsonNode> e = firstEntry(map);
        if (e == null) {
            return null;
        }
        List<String> v = values(e.getValue());
        return v.isEmpty() ? null : Map.entry(e.getKey(), v.getFirst());
    }

    /** Value in the preferred language if present, otherwise the first available. */
    private static String textInLanguage(JsonNode map, String lang) {
        if (map == null || !map.isObject()) {
            return null;
        }
        if (lang != null && map.has(lang)) {
            List<String> v = values(map.get(lang));
            if (!v.isEmpty()) {
                return v.getFirst();
            }
        }
        Map.Entry<String, String> e = firstText(map);
        return e == null ? null : e.getValue();
    }

    private static BigDecimal decimal(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            BigDecimal v = new BigDecimal(s.trim());
            return v.signum() < 0 ? null : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Removes control characters (except new lines/tabs), trims and bounds the length. */
    static String clean(String s, int max) {
        if (s == null) {
            return null;
        }
        String c = s.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "").replace(' ', ' ').strip();
        if (c.isEmpty()) {
            return null;
        }
        return c.length() > max ? c.substring(0, max) : c;
    }
}
