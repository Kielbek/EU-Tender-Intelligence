package eu.tenderintel.reference;

import eu.tenderintel.common.error.ApiException;
import eu.tenderintel.reference.ReferenceDtos.CountryView;
import eu.tenderintel.reference.ReferenceDtos.CpvDetailView;
import eu.tenderintel.reference.ReferenceDtos.CpvView;
import eu.tenderintel.reference.ReferenceDtos.CurrencyView;
import eu.tenderintel.reference.ReferenceDtos.ExchangeRateView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ReferenceQueries {

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    ReferenceQueries(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
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

    /**
     * Search by code prefix (digits) or by label words in {@code lang} (accent-insensitive). Optional {@code parent}
     * lists direct children, which supports a tree picker in the UI.
     */
    public List<CpvView> searchCpv(String query, String parent, String lang, int limit) {
        String l = normalizeLang(lang);
        String label = "coalesce(labels->>'" + l + "', labels->>'en')"; // l is whitelisted: [a-z]{2}
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("select code, parent_code, level, " + label + " from cpv_codes where true");
        if (parent != null && !parent.isBlank()) {
            sql.append(" and parent_code = ?");
            args.add(parent);
        } else if (parent == null && (query == null || query.isBlank())) {
            sql.append(" and level = 1");
        }
        if (query != null && !query.isBlank()) {
            String q = query.trim();
            if (q.matches("\\d{1,8}")) {
                sql.append(" and code like ?");
                args.add(q + "%");
            } else {
                sql.append(" and immutable_unaccent(lower(" + label + ")) like immutable_unaccent(lower(?))");
                args.add("%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
            }
        }
        sql.append(" order by level, code limit ?");
        args.add(Math.max(1, Math.min(limit, 100)));
        return jdbc.query(sql.toString(), (rs, i) -> new CpvView(rs.getString(1), rs.getString(2), rs.getInt(3),
                rs.getString(4)), args.toArray());
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
