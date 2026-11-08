package eu.tenderintel.ingestion.bzp;

import eu.tenderintel.ingestion.ChangeKind;
import eu.tenderintel.ingestion.NormalizedNotice;
import eu.tenderintel.ingestion.NormalizedNotice.FormType;
import eu.tenderintel.ingestion.NormalizedNotice.Lot;
import eu.tenderintel.ingestion.NormalizedNotice.Party;
import eu.tenderintel.ingestion.NormalizedNotice.Winner;
import eu.tenderintel.ingestion.bzp.BzpNoticeText.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Maps one notice of the BZP API ({@code GET /mo-board/api/v1/notice}) to a {@link NormalizedNotice}. Summary fields
 * come from the JSON; description, CPV codes, documents link, NUTS, reference number, part values, prices and
 * changed deadlines are read from the notice's HTML body (numbered fields, stable across notices). Rules were derived
 * from a full publication day (2026-11-05). Fields that are absent stay null – never guessed.
 */
public final class BzpNoticeParser {

    /** Notice types ingested. Contract-performance reports ("ContractPerformingNotice") carry nothing for bidders. */
    public static final List<String> TYPES = List.of("ContractNotice", "SmallContractNotice", "CompetitionNotice",
            "NoticeUpdateNotice", "TenderResultNotice", "AgreementIntentionNotice");

    static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");
    static final String NOTICE_URL = "https://ezamowienia.gov.pl/mo-client-board/bzp/notice-details/id/";
    private static final Pattern NUMBER = Pattern.compile("^(\\d{4})/BZP (\\d{8})(?:/(\\d{2}))?$");
    private static final Pattern CPV = Pattern.compile("\\b(\\d{8})-\\d\\b");
    private static final Pattern NUTS = Pattern.compile("\\b(PL[0-9A-Z]{1,3})\\b");
    private static final Pattern URL = Pattern.compile("(https?://\\S+|www\\.\\S+)");
    private static final Pattern AMOUNT = Pattern.compile("([0-9][0-9 .\\u00a0]*(?:,\\d{1,2})?)\\s*([A-Z]{3})");
    private static final Pattern DATE_TIME = Pattern.compile("(\\d{4}-\\d{2}-\\d{2}) (\\d{2}:\\d{2})");
    private static final Pattern CHANGED_DEADLINE = Pattern.compile(
            "8\\.1\\.?\\)? Termin składania ofert[^\\n]*?Przed zmianą:?\\s*[^\\n]*?\\s*Po zmianie:?\\s*"
                    + "(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2})", Pattern.DOTALL);
    private static final Pattern AWARD_DATE = Pattern.compile("Data zawarcia umowy:?\\s*(\\d{4}-\\d{2}-\\d{2})");
    private static final int MAX_TITLE = 1000;
    private static final int MAX_DESCRIPTION = 20_000;

    private BzpNoticeParser() {
    }

    public static NormalizedNotice parse(JsonNode n) {
        String noticeNumber = text(n, "noticeNumber");
        if (noticeNumber == null) {
            throw new IllegalArgumentException("BZP notice without noticeNumber");
        }
        String type = text(n, "noticeType");
        String tenderId = text(n, "tenderId");
        String objectId = text(n, "objectId");
        String published = text(n, "publicationDate");
        if (type == null || published == null) {
            throw new IllegalArgumentException("BZP notice " + noticeNumber + " without type or publication date");
        }
        Instant publishedAt = Instant.parse(published);
        String html = text(n, "htmlBody");
        List<Field> fields = BzpNoticeText.fields(html);
        String plain = BzpNoticeText.plainText(html);

        FormType formType;
        ChangeKind changeKind;
        List<Instant> deadlines = new ArrayList<>();
        Instant deadline = instant(text(n, "submittingOffersDate"));
        if (deadline != null) {
            deadlines.add(deadline);
        }
        switch (type) {
            case "ContractNotice", "SmallContractNotice", "CompetitionNotice" -> {
                formType = FormType.COMPETITION;
                changeKind = ChangeKind.COMPETITION;
            }
            case "NoticeUpdateNotice" -> {
                Instant changed = changedDeadline(plain);
                if (changed != null) {
                    deadlines.add(changed);
                }
                // A new deadline re-opens or keeps the procedure open; other changes keep the status.
                formType = changed != null ? FormType.COMPETITION : FormType.OTHER;
                changeKind = ChangeKind.CHANGE;
            }
            case "TenderResultNotice" -> {
                formType = FormType.RESULT;
                changeKind = cancelled(text(n, "procedureResult")) ? ChangeKind.CANCELLATION : ChangeKind.AWARD;
            }
            case "AgreementIntentionNotice" -> {
                formType = FormType.DIRECT_AWARD;
                changeKind = ChangeKind.OTHER;
            }
            default -> {
                formType = FormType.OTHER;
                changeKind = ChangeKind.OTHER;
            }
        }

        String title = clean(text(n, "orderObject"), MAX_TITLE);
        Party buyer = new Party(clean(text(n, "organizationName"), 500), text(n, "organizationNationalId"),
                country(n), text(n, "organizationCity"), url(firstValue(fields, "Adres strony internetowej zamawiającego")));

        List<String> descriptions = values(fields, "Krótki opis przedmiotu zamówienia");
        String description = descriptions.isEmpty() ? null
                : clean(descriptions.size() == 1 ? descriptions.getFirst()
                        : String.join("\n\n", numbered(descriptions)), MAX_DESCRIPTION);

        Set<String> cpv = new LinkedHashSet<>();
        String mainCpv = cpvOf(text(n, "cpvCode"));
        if (mainCpv != null) {
            cpv.add(mainCpv);
        }
        for (Field f : fields) {
            if (f.label().contains("kod CPV") || f.label().contains("kody CPV")) {
                Matcher m = CPV.matcher(f.value() + " " + f.label());
                while (m.find()) {
                    cpv.add(m.group(1));
                }
            }
        }
        if (mainCpv == null && !cpv.isEmpty()) {
            mainCpv = cpv.iterator().next();
        }

        String nuts = null;
        String nutsField = firstValue(fields, "Lokalizacja NUTS 3");
        if (nutsField != null) {
            Matcher m = NUTS.matcher(nutsField);
            nuts = m.find() ? m.group(1) : null;
        }

        // Result notices: estimated value of each part; awarded price of each part, in the order of the contractors.
        List<BigDecimal[]> partValues = amounts(values(fields, "Wartość części"));
        BigDecimal estimated = null;
        String currency = null;
        if (!partValues.isEmpty()) {
            estimated = partValues.stream().map(a -> a[0]).reduce(BigDecimal.ZERO, BigDecimal::add);
            currency = currencyOf(values(fields, "Wartość części").getFirst());
        }
        List<Winner> winners = winners(n, fields);
        List<String> received = values(fields, "Liczba otrzymanych ofert lub wniosków");
        Integer tendersReceived = received.size() == 1 ? intOrNull(received.getFirst()) : null;
        Matcher award = AWARD_DATE.matcher(plain);
        LocalDate awardDate = award.find() ? LocalDate.parse(award.group(1)) : null;

        List<Lot> lots = new ArrayList<>();
        if (descriptions.size() > 1 && formType == FormType.COMPETITION) {
            for (int i = 0; i < descriptions.size(); i++) {
                lots.add(new Lot("Część " + (i + 1), clean(descriptions.get(i), 300), null, null, null));
            }
        }

        return new NormalizedNotice(
                noticeNumber,
                orderKey(noticeNumber),
                objectId == null ? null : NOTICE_URL + objectId,
                type,
                formType,
                changeKind,
                null,
                publishedAt,
                publishedAt.atZone(WARSAW).toLocalDate(),
                tenderId != null ? "bzp:" + tenderId : "bzp-notice:" + text(n, "bzpNumber"),
                title,
                title == null ? null : "pl",
                description,
                buyer,
                country(n),
                nuts,
                text(n, "organizationCity"),
                contractType(text(n, "orderType"), firstValue(fields, "Rodzaj zamówienia")),
                procedureType(firstValue(fields, "Tryb udzielenia zamówienia wraz z podstawą prawną")),
                mainCpv,
                List.copyOf(cpv),
                estimated,
                estimated == null ? null : currency,
                deadlines,
                deadlines.isEmpty() ? null : WARSAW.getRules().getOffset(deadlines.getLast()).getId(),
                firstValue(fields, "Numer referencyjny"),
                url(firstValue(fields, "Adres strony internetowej prowadzonego postępowania")),
                lots,
                winners,
                tendersReceived,
                awardDate,
                null);
    }

    /** "2026/BZP 00523249/01" → 2026 0052324901 (year, number, version): orders notices of one procedure. */
    static long orderKey(String noticeNumber) {
        Matcher m = NUMBER.matcher(noticeNumber.strip());
        if (!m.matches()) {
            return 0;
        }
        long version = m.group(3) == null ? 0 : Long.parseLong(m.group(3));
        return Long.parseLong(m.group(1)) * 10_000_000_000L + Long.parseLong(m.group(2)) * 100 + version;
    }

    static boolean cancelled(String procedureResult) {
        if (procedureResult == null || procedureResult.isBlank()) {
            return false;
        }
        for (String part : procedureResult.split(";")) {
            if (!part.isBlank() && !part.strip().toLowerCase(Locale.ROOT).startsWith("uniewaz")) {
                return false;
            }
        }
        return true;
    }

    /** New time limit from a change notice ("8.1. Termin składania ofert Przed zmianą: … Po zmianie: …"). */
    static Instant changedDeadline(String plain) {
        Matcher m = CHANGED_DEADLINE.matcher(plain);
        Instant last = null;
        while (m.find()) {
            Matcher dt = DATE_TIME.matcher(m.group(1));
            if (dt.find()) {
                last = LocalDateTime.parse(dt.group(1) + "T" + dt.group(2)).atZone(WARSAW).toInstant();
            }
        }
        return last;
    }

    static String contractType(String orderType, String field) {
        String v = orderType != null ? orderType : field;
        if (v == null) {
            return null;
        }
        String s = v.toLowerCase(Locale.ROOT);
        if (s.contains("work") || s.contains("robot")) {
            return "WORKS";
        }
        if (s.contains("deliver") || s.contains("dostaw")) {
            return "SUPPLIES";
        }
        if (s.contains("service") || s.contains("usług")) {
            return "SERVICES";
        }
        return null;
    }

    /** Polish procedures, as eForms-style codes where one exists; "pl-basic" = tryb podstawowy (below EU thresholds). */
    static String procedureType(String text) {
        if (text == null) {
            return null;
        }
        String s = text.toLowerCase(Locale.ROOT);
        if (s.contains("podstawow")) {
            return "pl-basic";
        }
        if (s.contains("przetarg nieograniczon") || s.contains("przetargu nieograniczon")) {
            return "open";
        }
        if (s.contains("przetarg ograniczon") || s.contains("przetargu ograniczon")) {
            return "restricted";
        }
        if (s.contains("negocjac") && s.contains("ogłoszeni")) {
            return "neg-w-call";
        }
        if (s.contains("dialog")) {
            return "comp-dial";
        }
        if (s.contains("partnerstw")) {
            return "innovation";
        }
        return null;
    }

    private static List<Winner> winners(JsonNode n, List<Field> fields) {
        JsonNode list = n.get("contractors");
        List<Winner> out = new ArrayList<>();
        if (list == null || !list.isArray()) {
            return out;
        }
        List<String> prices = values(fields, "Cena lub koszt oferty wykonawcy, któremu udzielono zamówienia");
        boolean paired = prices.size() == list.size();
        for (int i = 0; i < list.size(); i++) {
            JsonNode c = list.get(i);
            String name = clean(text(c, "contractorName"), 500);
            if (name == null) {
                continue;
            }
            BigDecimal value = null;
            String currency = null;
            if (paired) {
                List<BigDecimal[]> a = amounts(List.of(prices.get(i)));
                value = a.isEmpty() ? null : a.getFirst()[0];
                currency = value == null ? null : currencyOf(prices.get(i));
            }
            String country = text(c, "contractorCountry");
            out.add(new Winner(name, text(c, "contractorNationalId"), country == null ? null : country.toUpperCase(),
                    null, value, currency));
        }
        return out;
    }

    /** "550088,89 PLN" → 550088.89 (Polish decimal comma, spaces as thousands separators). */
    static List<BigDecimal[]> amounts(List<String> texts) {
        List<BigDecimal[]> out = new ArrayList<>();
        for (String t : texts) {
            Matcher m = AMOUNT.matcher(t);
            if (m.find()) {
                String num = m.group(1).replaceAll("[ .\\u00a0]", "").replace(',', '.');
                try {
                    out.add(new BigDecimal[] {new BigDecimal(num)});
                } catch (NumberFormatException e) {
                    // not an amount
                }
            }
        }
        return out;
    }

    private static String currencyOf(String t) {
        Matcher m = AMOUNT.matcher(t);
        return m.find() ? m.group(2) : null;
    }

    private static List<String> numbered(List<String> parts) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            out.add("Część " + (i + 1) + ": " + parts.get(i));
        }
        return out;
    }

    private static List<String> values(List<Field> fields, String label) {
        return fields.stream().filter(f -> f.label().startsWith(label)).map(Field::value)
                .filter(v -> v != null && !v.isBlank()).toList();
    }

    private static String firstValue(List<Field> fields, String label) {
        List<String> v = values(fields, label);
        return v.isEmpty() ? null : v.getFirst();
    }

    private static String cpvOf(String s) {
        if (s == null) {
            return null;
        }
        Matcher m = CPV.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static String url(String s) {
        if (s == null) {
            return null;
        }
        Matcher m = URL.matcher(s);
        if (!m.find()) {
            return null;
        }
        String u = m.group(1).replaceAll("[),.;]+$", "");
        return u.startsWith("http") ? u : "https://" + u;
    }

    private static String country(JsonNode n) {
        String c = text(n, "organizationCountry");
        return c == null || c.length() != 2 ? "PL" : c.toUpperCase(Locale.ROOT);
    }

    private static Instant instant(String s) {
        if (s == null) {
            return null;
        }
        try {
            return Instant.parse(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Integer intOrNull(String s) {
        try {
            return Integer.valueOf(s.replaceAll("\\D", ""));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String clean(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.replaceAll("\\s+", " ").strip();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() > max ? t.substring(0, max) : t;
    }

    static String text(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asString();
        return s == null || s.isBlank() ? null : s;
    }
}
