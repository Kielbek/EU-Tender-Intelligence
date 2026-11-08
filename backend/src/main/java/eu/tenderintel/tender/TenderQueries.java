package eu.tenderintel.tender;

import eu.tenderintel.common.api.PageResponse;
import eu.tenderintel.common.error.ApiException;
import eu.tenderintel.tender.TenderDtos.AuthorityView;
import eu.tenderintel.tender.TenderDtos.SortDirection;
import eu.tenderintel.tender.TenderDtos.SortField;
import eu.tenderintel.tender.TenderDtos.SourceView;
import eu.tenderintel.tender.TenderDtos.TenderDetail;
import eu.tenderintel.tender.TenderDtos.TenderSearchCriteria;
import eu.tenderintel.tender.TenderDtos.TenderSummary;
import eu.tenderintel.tender.TenderDtos.VersionView;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Read model for tenders. Plain SQL with bound parameters only (no string concatenation of user input); sort columns
 * come from a whitelist.
 */
@Service
@Transactional(readOnly = true)
public class TenderQueries {

    private static final String SUMMARY_COLUMNS = """
            t.id, t.title, t.country_code, t.city, ca.name as authority_name, t.main_cpv,
            coalesce(cpv.labels->>:lang, cpv.labels->>'en') as main_cpv_label, t.contract_type,
            t.procedure_type, t.estimated_value, t.currency, t.estimated_value_eur, t.published_at,
            t.submission_deadline, t.status, t.original_url
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper json;

    TenderQueries(NamedParameterJdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    static final String FROM = " from tenders t left join contracting_authorities ca on ca.id = t.contracting_authority_id "
            + "left join cpv_codes cpv on cpv.code = t.main_cpv ";

    public PageResponse<TenderSummary> search(TenderSearchCriteria c, String lang) {
        MapSqlParameterSource p = new MapSqlParameterSource("lang", lang(lang));
        String where = where(c, p);
        String from = FROM;
        Long total = jdbc.queryForObject("select count(*)" + from + where, p, Long.class);
        int size = c.sizeOrDefault();
        int page = c.pageOrDefault();
        p.addValue("limit", size).addValue("offset", page * size);
        List<TenderSummary> items = jdbc.query("select " + SUMMARY_COLUMNS + from + where + orderBy(c)
                + " limit :limit offset :offset", p, (rs, i) -> summary(rs));
        return PageResponse.of(items, page, size, total == null ? 0 : total);
    }

    /** Up to {@code limit} rows matching the criteria, in the requested order (used by exports). */
    public List<TenderSummary> list(TenderSearchCriteria c, String lang, int limit) {
        MapSqlParameterSource p = new MapSqlParameterSource("lang", lang(lang)).addValue("limit", limit);
        String where = where(c, p);
        return jdbc.query("select " + SUMMARY_COLUMNS + FROM + where + orderBy(c) + " limit :limit", p,
                (rs, i) -> summary(rs));
    }

    public long count(TenderSearchCriteria c) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        String where = where(c, p);
        Long n = jdbc.queryForObject("select count(*)" + FROM + where, p, Long.class);
        return n == null ? 0 : n;
    }

    /**
     * Tenders matching saved-search criteria that were added to the database in {@code (from, to]} and published in
     * the last {@code recentDays} days (a backfill of old notices is not "new"). Used by saved-search alerts.
     */
    public NewTenders addedBetween(TenderSearchCriteria c, Instant from, Instant to, int recentDays, int sample) {
        MapSqlParameterSource p = new MapSqlParameterSource("lang", "en");
        String where = where(c, p);
        where = (where.isEmpty() ? " where " : where + " and ")
                + "t.created_at > :addedFrom and t.created_at <= :addedTo and t.published_at > :recent";
        p.addValue("addedFrom", Timestamp.from(from)).addValue("addedTo", Timestamp.from(to))
                .addValue("recent", Timestamp.from(to.minus(java.time.Duration.ofDays(recentDays))))
                .addValue("sample", sample);
        Long total = jdbc.queryForObject("select count(*)" + FROM + where, p, Long.class);
        if (total == null || total == 0) {
            return new NewTenders(0, List.of());
        }
        List<UUID> ids = jdbc.queryForList("select t.id" + FROM + where + " order by t.published_at desc, t.id "
                + "limit :sample", p, UUID.class);
        return new NewTenders(total, ids);
    }

    public record NewTenders(long count, List<UUID> sample) {
    }

    /** Summaries of the given tenders, keyed by id (missing ids are simply absent). */
    public java.util.Map<UUID, TenderSummary> summaries(java.util.Collection<UUID> ids, String lang) {
        if (ids.isEmpty()) {
            return java.util.Map.of();
        }
        MapSqlParameterSource p = new MapSqlParameterSource("lang", lang(lang)).addValue("ids", ids);
        java.util.Map<UUID, TenderSummary> out = new java.util.LinkedHashMap<>();
        jdbc.query("select " + SUMMARY_COLUMNS + FROM + " where t.id in (:ids)", p,
                rs -> {
                    TenderSummary s = summary(rs);
                    out.put(s.id(), s);
                });
        return out;
    }

    public TenderDetail get(UUID id) {
        return get(id, "en");
    }

    public TenderDetail get(UUID id, String lang) {
        MapSqlParameterSource p = new MapSqlParameterSource("id", id).addValue("lang", lang(lang));
        List<TenderDetail> rows = jdbc.query("""
                select t.*, ca.id as ca_id, ca.name as ca_name, ca.national_id as ca_national_id,
                       ca.country_code as ca_country, ca.city as ca_city, ca.website as ca_website,
                       t.participation_conditions::text as pc_text, t.award_criteria::text as ac_text
                from tenders t left join contracting_authorities ca on ca.id = t.contracting_authority_id
                where t.id = :id
                """, p, (rs, i) -> detail(rs, id, lang(lang)));
        if (rows.isEmpty()) {
            throw ApiException.notFound("Tender");
        }
        return rows.getFirst();
    }

    public List<VersionView> versions(UUID tenderId) {
        get(tenderId);
        return jdbc.query("""
                select version_no, change_kind, notice_type, published_at, diff::text from tender_versions
                where tender_id = :id order by order_key desc, version_no desc
                """, new MapSqlParameterSource("id", tenderId), (rs, i) -> new VersionView(rs.getInt(1),
                rs.getString(2), rs.getString(3), instant(rs, 4), readJson(rs.getString(5))));
    }

    static String where(TenderSearchCriteria c, MapSqlParameterSource p) {
        List<String> conditions = new ArrayList<>();
        if (notBlank(c.q())) {
            conditions.add("t.search_vector @@ websearch_to_tsquery('simple', immutable_unaccent(:q))");
            p.addValue("q", c.q().trim());
        }
        if (notEmpty(c.country())) {
            conditions.add("t.country_code in (:country)");
            p.addValue("country", c.country());
        }
        if (notEmpty(c.status())) {
            conditions.add("t.status in (:status)");
            p.addValue("status", c.status());
        }
        if (notEmpty(c.contractType())) {
            conditions.add("t.contract_type in (:contractType)");
            p.addValue("contractType", c.contractType());
        }
        if (notEmpty(c.procedureType())) {
            conditions.add("t.procedure_type in (:procedureType)");
            p.addValue("procedureType", c.procedureType());
        }
        if (notEmpty(c.cpv())) {
            // "45000000" means the whole division 45: match by significant prefix, in main or additional CPV codes.
            List<String> ors = new ArrayList<>();
            for (int i = 0; i < c.cpv().size(); i++) {
                String prefix = c.cpv().get(i).replaceAll("0+$", "");
                if (prefix.length() < 2) {
                    prefix = c.cpv().get(i).substring(0, 2);
                }
                String name = "cpv" + i;
                p.addValue(name, prefix + "%");
                ors.add("t.main_cpv like :" + name
                        + " or exists (select 1 from tender_cpv tc where tc.tender_id = t.id and tc.cpv_code like :"
                        + name + ")");
            }
            conditions.add("(" + String.join(" or ", ors) + ")");
        }
        if (notBlank(c.authority())) {
            conditions.add("ca.normalized_name like '%' || immutable_unaccent(lower(:authority)) || '%'");
            p.addValue("authority", escapeLike(c.authority().trim()));
        }
        if (notBlank(c.city())) {
            conditions.add("immutable_unaccent(lower(t.city)) = immutable_unaccent(lower(:city))");
            p.addValue("city", c.city().trim());
        }
        if (notBlank(c.nuts())) {
            conditions.add("t.nuts_code like :nuts");
            p.addValue("nuts", c.nuts() + "%");
        }
        if (notBlank(c.currency())) {
            conditions.add("t.currency = :currency");
            p.addValue("currency", c.currency());
        }
        if (notBlank(c.language())) {
            conditions.add("t.notice_language = :language");
            p.addValue("language", c.language());
        }
        if (c.valueMinEur() != null) {
            conditions.add("t.estimated_value_eur >= :valueMin");
            p.addValue("valueMin", c.valueMinEur());
        }
        if (c.valueMaxEur() != null) {
            conditions.add("t.estimated_value_eur <= :valueMax");
            p.addValue("valueMax", c.valueMaxEur());
        }
        addRange(conditions, p, "t.published_at", "published", c.publishedFrom(), c.publishedTo());
        addRange(conditions, p, "t.submission_deadline", "deadline", c.deadlineFrom(), c.deadlineTo());
        return conditions.isEmpty() ? "" : " where " + String.join(" and ", conditions);
    }

    static String orderBy(TenderSearchCriteria c) {
        SortField field = c.sort() == null ? (notBlank(c.q()) ? SortField.RELEVANCE : SortField.PUBLISHED) : c.sort();
        boolean asc = c.direction() == null ? field == SortField.DEADLINE : c.direction() == SortDirection.ASC;
        String dir = asc ? " asc" : " desc";
        String nulls = " nulls last";
        return switch (field) {
            case DEADLINE -> " order by t.submission_deadline" + dir + nulls + ", t.id";
            case VALUE -> " order by t.estimated_value_eur" + dir + nulls + ", t.id";
            case RELEVANCE -> notBlank(c.q())
                    ? " order by ts_rank(t.search_vector, websearch_to_tsquery('simple', immutable_unaccent(:q))) desc,"
                            + " t.published_at desc, t.id"
                    : " order by t.published_at desc, t.id";
            case PUBLISHED -> " order by t.published_at" + dir + ", t.id";
        };
    }

    private static void addRange(List<String> conditions, MapSqlParameterSource p, String column, String name,
            Instant from, Instant to) {
        if (from != null) {
            conditions.add(column + " >= :" + name + "From");
            p.addValue(name + "From", Timestamp.from(from));
        }
        if (to != null) {
            conditions.add(column + " < :" + name + "To");
            p.addValue(name + "To", Timestamp.from(to));
        }
    }

    private TenderDetail detail(ResultSet rs, UUID id, String lang) throws SQLException {
        MapSqlParameterSource p = new MapSqlParameterSource("id", id).addValue("lang", lang);
        java.util.Map<String, String> cpvLabels = new java.util.LinkedHashMap<>();
        jdbc.query("""
                select tc.cpv_code, coalesce(c.labels->>:lang, c.labels->>'en') from tender_cpv tc
                left join cpv_codes c on c.code = tc.cpv_code where tc.tender_id = :id
                union
                select t.main_cpv, coalesce(c.labels->>:lang, c.labels->>'en') from tenders t
                left join cpv_codes c on c.code = t.main_cpv where t.id = :id and t.main_cpv is not null
                """, p, r -> {
            if (r.getString(2) != null) {
                cpvLabels.put(r.getString(1), r.getString(2));
            }
        });
        List<TenderDtos.LotView> lots = jdbc.query("""
                select lot_number, title, estimated_value, currency, submission_deadline from tender_lots
                where tender_id = :id order by lot_number
                """, p, (r, i) -> new TenderDtos.LotView(r.getString(1), r.getString(2), r.getBigDecimal(3),
                r.getString(4), instant(r, 5)));
        List<TenderDtos.ResultView> results = jdbc.query("""
                select o.name, o.country_code, r.winner_size, r.awarded_value, r.currency, r.awarded_value_eur,
                       r.tenders_received, r.award_date
                from tender_results r left join economic_operators o on o.id = r.winner_id
                where r.tender_id = :id order by r.award_date nulls last, o.name
                """, p, (r, i) -> new TenderDtos.ResultView(r.getString(1), r.getString(2), r.getString(3),
                r.getBigDecimal(4), r.getString(5), r.getBigDecimal(6), (Integer) r.getObject(7),
                r.getObject(8, java.time.LocalDate.class)));
        List<String> cpv = jdbc.queryForList(
                "select cpv_code from tender_cpv where tender_id = :id order by is_main desc, cpv_code", p,
                String.class);
        List<SourceView> sources = jdbc.query("""
                select ds.code, ts.external_id, ts.external_url, ts.notice_type, ts.first_seen_at, ts.last_seen_at
                from tender_sources ts join data_sources ds on ds.id = ts.data_source_id
                where ts.tender_id = :id order by ts.first_seen_at
                """, p, (r, i) -> new SourceView(r.getString(1), r.getString(2), r.getString(3), r.getString(4),
                instant(r, 5), instant(r, 6)));
        UUID caId = rs.getObject("ca_id", UUID.class);
        AuthorityView authority = caId == null ? null : new AuthorityView(caId, rs.getString("ca_name"),
                rs.getString("ca_national_id"), rs.getString("ca_country"), rs.getString("ca_city"),
                rs.getString("ca_website"));
        return new TenderDetail(id, rs.getString("title"), rs.getString("title_lang"), rs.getString("description"),
                rs.getString("country_code"), rs.getString("nuts_code"), rs.getString("city"), authority,
                rs.getString("reference_number"), rs.getString("contract_type"), rs.getString("procedure_type"),
                rs.getString("main_cpv"), cpv, cpvLabels, rs.getBigDecimal("estimated_value"), rs.getString("currency"),
                rs.getBigDecimal("estimated_value_eur"), instant(rs, "published_at"),
                instant(rs, "submission_deadline"), rs.getString("submission_deadline_tz"), instant(rs, "opening_at"),
                rs.getString("status"), rs.getString("notice_language"), rs.getString("original_url"),
                rs.getString("documents_url"), readJson(rs.getString("pc_text")), readJson(rs.getString("ac_text")),
                rs.getInt("current_version_no"), instant(rs, "last_changed_at"), sources, lots, results);
    }

    private static TenderSummary summary(ResultSet rs) throws SQLException {
        return new TenderSummary(rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("country_code"),
                rs.getString("city"), rs.getString("authority_name"), rs.getString("main_cpv"),
                rs.getString("main_cpv_label"), rs.getString("contract_type"), rs.getString("procedure_type"), rs.getBigDecimal("estimated_value"),
                rs.getString("currency"), rs.getBigDecimal("estimated_value_eur"), instant(rs, "published_at"),
                instant(rs, "submission_deadline"), rs.getString("status"), rs.getString("original_url"));
    }

    private Object readJson(String value) {
        return value == null ? null : json.readValue(value, Object.class);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    static String lang(String lang) {
        return lang != null && lang.matches("[a-z]{2}") ? lang : "en";
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static boolean notEmpty(List<?> l) {
        return l != null && !l.isEmpty();
    }
}
