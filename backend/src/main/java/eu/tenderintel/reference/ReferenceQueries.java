package eu.tenderintel.reference;

import eu.tenderintel.common.error.ApiException;
import eu.tenderintel.reference.ReferenceDtos.CountryView;
import eu.tenderintel.reference.ReferenceDtos.CpvDetailView;
import eu.tenderintel.reference.ReferenceDtos.CpvView;
import eu.tenderintel.reference.ReferenceDtos.CurrencyView;
import eu.tenderintel.reference.ReferenceDtos.ExchangeRateView;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ReferenceQueries {

    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final CpvOpenCounts openCounts;

    ReferenceQueries(JdbcTemplate jdbc, JsonMapper json, CpvOpenCounts openCounts) {
        this.jdbc = jdbc;
        this.json = json;
        this.openCounts = openCounts;
    }

    public List<CountryView> countries() {
        return jdbc.query("select code, code3, name_en, is_eu, default_currency, default_language from countries "
                + "order by name_en", (rs, i) -> new CountryView(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getBoolean(4), rs.getString(5), rs.getString(6)));
    }

    public List<CurrencyView> currencies() {
        return jdbc.query("select code, name_en, minor_units from currencies order by code",
                (rs, i) -> new CurrencyView(rs.getString(1), rs.getString(2), rs.getInt(3)));
    }

    public List<ExchangeRateView> latestRates() {
        return jdbc.query("""
                select distinct on (currency) currency, rate_date, rate_per_eur, source
                from exchange_rates order by currency, rate_date desc
                """, (rs, i) -> new ExchangeRateView(rs.getString(1), rs.getObject(2, java.time.LocalDate.class),
                rs.getBigDecimal(3), rs.getString(4)));
    }

    /** Longest query handled as words; more words are ignored. */
    static final int MAX_WORDS = 6;

    /**
     * Search by code prefix (digits) or by words in {@code lang}: every word must start a word of the label,
     * accent-insensitive, with a short ending cut off so that inflected forms match ("drogi" finds "drogowe",
     * "remont" finds "remontowe"). Labels starting with the whole phrase come first, then general codes before
     * specific ones. Optional {@code parent} lists direct children, which supports browsing the tree; without
     * query and parent: the divisions (level 1).
     */
    public List<CpvView> searchCpv(String query, String parent, String lang, int limit) {
        String l = normalizeLang(lang);
        String label = "coalesce(c.labels->>'" + l + "', c.labels->>'en')"; // l is whitelisted: [a-z]{2}
        String folded = "immutable_unaccent(lower(" + label + "))";
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("select c.code, c.parent_code, c.level, " + label + ", "
                + "coalesce(p.labels->>'" + l + "', p.labels->>'en'), "
                + "exists (select 1 from cpv_codes ch where ch.parent_code = c.code) "
                + "from cpv_codes c left join cpv_codes p on p.code = c.parent_code where true");
        String order = " order by c.level, c.code";
        if (parent != null && !parent.isBlank()) {
            sql.append(" and c.parent_code = ?");
            args.add(parent);
            order = " order by c.code";
        } else if (query == null || query.isBlank()) {
            sql.append(" and c.level = 1");
        }
        if (query != null && !query.isBlank()) {
            String q = query.trim();
            if (q.matches("\\d{1,8}")) {
                sql.append(" and c.code like ?");
                args.add(q + "%");
            } else {
                return searchWords(q, l, limit);
            }
        }
        sql.append(order).append(" limit ?");
        args.add(Math.max(1, Math.min(limit, 100)));
        return jdbc.query(sql.toString(), (rs, i) -> new CpvView(rs.getString(1), rs.getString(2), rs.getInt(3),
                rs.getString(4), rs.getString(5), rs.getBoolean(6), openCounts.openTenders(rs.getString(1))),
                args.toArray());
    }

    /**
     * Word search. Each word (with its synonyms) is one group; a code matches when at least one group matches the
     * start of a word of its label. Order: most groups matched, then {@link #relevance}, label starting with /
     * containing the whole phrase, more general code.
     */
    private List<CpvView> searchWords(String query, String l, int limit) {
        List<String> words = words(query);
        if (words.isEmpty()) {
            return List.of();
        }
        String label = "coalesce(c.labels->>'" + l + "', c.labels->>'en')";
        String folded = "immutable_unaccent(lower(" + label + "))";
        List<Object> args = new ArrayList<>();
        List<String> groups = new ArrayList<>();
        for (String w : words) {
            List<String> alternatives = alternatives(w);
            // alternatives hold only letters and digits, so they are safe inside the regular expression
            groups.add("(case when " + String.join(" or ", alternatives.stream()
                    .map(x -> folded + " ~ ('\\m' || immutable_unaccent(lower(?)))").toList()) + " then 1 else 0 end)");
            args.addAll(alternatives);
        }
        String phrase = String.join(" ", words).toLowerCase(Locale.ROOT);
        args.add(phrase);
        args.add(phrase);
        String sql = "select * from (select c.code, c.parent_code, c.level, " + label + " as label, "
                + "coalesce(p.labels->>'" + l + "', p.labels->>'en'), "
                + "exists (select 1 from cpv_codes ch where ch.parent_code = c.code), "
                + String.join(" + ", groups) + " as score, "
                + "case when " + folded + " like immutable_unaccent(?) || '%' then 0"
                + " when " + folded + " like '% ' || immutable_unaccent(?) || '%' then 1 else 2 end as phrase "
                + "from cpv_codes c left join cpv_codes p on p.code = c.parent_code) x "
                + "where score > 0 order by score desc, phrase, level, code limit 300";
        record Hit(CpvView view, int score, int phrase) {
        }
        List<Hit> hits = jdbc.query(sql, (rs, i) -> new Hit(new CpvView(rs.getString(1), rs.getString(2), rs.getInt(3),
                rs.getString(4), rs.getString(5), rs.getBoolean(6), openCounts.openTenders(rs.getString(1))),
                rs.getInt(7), rs.getInt(8)), args.toArray());
        return hits.stream()
                .sorted(Comparator.comparingInt((Hit h) -> -h.score()).thenComparingDouble(h -> -relevance(h.view()))
                        .thenComparingInt(Hit::phrase).thenComparingInt(h -> h.view().level())
                        .thenComparing(h -> h.view().code()))
                .limit(Math.max(1, Math.min(limit, 100)))
                .map(Hit::view)
                .toList();
    }

    /**
     * Among codes matching the same words: many open tenders (what is actually bought) and a short, specific name
     * ("Roboty w zakresie naprawy dróg" before a long list that mentions roads in passing).
     */
    static double relevance(CpvView v) {
        int words = v.label() == null ? 1 : v.label().trim().split("\\s+").length;
        return Math.log(1 + v.openTenders()) / Math.log(2) - 0.5 * words;
    }

    /**
     * Everyday words that CPV names differently ("remont" → "naprawa", "konserwacja"…), keyed by the folded start
     * of a word. Small on purpose: only frequent searches where the CPV name would otherwise not be found.
     */
    static final Map<String, List<String>> SYNONYMS = Map.ofEntries(
            Map.entry("remont", List.of("remont", "napraw", "konserwac", "renowac", "modernizac")),
            Map.entry("napraw", List.of("napraw", "remont", "konserwac")),
            Map.entry("sprzat", List.of("sprzat", "czyszcz")),
            Map.entry("odsniez", List.of("odsniez", "snieg")),
            Map.entry("smiec", List.of("smiec", "odpad")),
            Map.entry("lek", List.of("lek", "farmaceut")),
            Map.entry("paliw", List.of("paliw", "benzyn", "olej napedow")),
            Map.entry("posilk", List.of("posilk", "zywien", "gastronom")),
            Map.entry("catering", List.of("catering", "posilk", "zywien")),
            Map.entry("it", List.of("informaty", "komputer", "oprogramowan")),
            Map.entry("repair", List.of("repair", "maintenan", "renovat")),
            Map.entry("cleaning", List.of("clean")),
            Map.entry("road", List.of("road", "highway")));

    /** Stem of the word plus its synonyms (all folded: lower case, no diacritics). */
    static List<String> alternatives(String word) {
        String f = fold(word);
        String stem = fold(stem(word));
        List<String> out = new ArrayList<>(List.of(stem));
        SYNONYMS.forEach((key, list) -> {
            if (key.length() <= 2 ? f.equals(key) : f.startsWith(key)) {
                list.stream().filter(x -> !out.contains(x) && x.matches("[\\p{L}\\p{N} ]+")).forEach(out::add);
            }
        });
        return out;
    }

    static String fold(String s) {
        return Normalizer.normalize(s.toLowerCase(Locale.ROOT).replace('ł', 'l'), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
    }

    /** Letters-and-digits words of a query, at most {@link #MAX_WORDS}. */
    static List<String> words(String query) {
        return Arrays.stream(query.split("[^\\p{L}\\p{N}]+")).filter(w -> !w.isEmpty()).limit(MAX_WORDS).toList();
    }

    /** "drogi" → "drog", "remontowe" → "remonto": cuts up to two final letters so inflected forms still match. */
    static String stem(String word) {
        int n = word.length();
        return n <= 4 ? word : word.substring(0, Math.max(4, n - 2));
    }

    public CpvDetailView cpv(String code, String lang) {
        String l = normalizeLang(lang);
        List<CpvDetailView> rows = jdbc.query("select code, parent_code, level, labels::text from cpv_codes where code = ?",
                (rs, i) -> new CpvDetailView(rs.getString(1), rs.getString(2), rs.getInt(3),
                        json.readValue(rs.getString(4), new TypeReference<Map<String, String>>() { }), List.of()),
                code);
        if (rows.isEmpty()) {
            throw ApiException.notFound("CPV code");
        }
        List<CpvView> ancestors = jdbc.query("""
                with recursive chain as (
                    select code, parent_code, level, labels from cpv_codes where code = (
                        select parent_code from cpv_codes where code = ?)
                    union all
                    select c.code, c.parent_code, c.level, c.labels from cpv_codes c join chain on c.code = chain.parent_code
                )
                select code, parent_code, level, coalesce(labels->>?, labels->>'en') from chain order by level
                """, (rs, i) -> new CpvView(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4)), code, l);
        CpvDetailView d = rows.getFirst();
        return new CpvDetailView(d.code(), d.parentCode(), d.level(), d.labels(), Collections.unmodifiableList(ancestors));
    }

    private static String normalizeLang(String lang) {
        return lang != null && lang.matches("[a-z]{2}") ? lang : "en";
    }
}
