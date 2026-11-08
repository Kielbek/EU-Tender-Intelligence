package eu.tenderintel.ingestion;

import eu.tenderintel.common.id.Ids;
import eu.tenderintel.ingestion.NormalizedNotice.Lot;
import eu.tenderintel.ingestion.NormalizedNotice.Party;
import eu.tenderintel.ingestion.NormalizedNotice.Winner;
import eu.tenderintel.reference.ExchangeRateService;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes one normalized notice into the tender model:
 * <ol>
 *   <li>deduplication – the same source notice is processed once ({@code tender_sources} unique key); notices of the
 *       same procedure (eForms procedure identifier) are merged into one tender;</li>
 *   <li>versioning – every notice becomes a {@code tender_versions} row with a field-level diff;</li>
 *   <li>ordering – the canonical state follows the newest notice (publication order), even when older notices are
 *       ingested later during a backfill;</li>
 *   <li>events – {@link TenderIngestedEvent} for downstream matching and notifications.</li>
 * </ol>
 */
@Service
public class TenderUpsertService {

    public enum Outcome { CREATED, UPDATED, HISTORY_ONLY, SKIPPED }

    /** Fields compared for the change history (description is compared but its text is not copied into the diff). */
    static final List<String> TRACKED = List.of("title", "description", "status", "submissionDeadline",
            "estimatedValue", "currency", "mainCpv", "cpvCodes", "procedureType", "contractType", "documentsUrl",
            "referenceNumber");

    private final JdbcTemplate jdbc;
    private final CountryResolver countries;
    private final ExchangeRateService rates;
    private final ApplicationEventPublisher events;
    private final JsonMapper json;
    private final Clock clock;

    TenderUpsertService(JdbcTemplate jdbc, CountryResolver countries, ExchangeRateService rates,
            ApplicationEventPublisher events, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.countries = countries;
        this.rates = rates;
        this.events = events;
        this.json = json;
        this.clock = clock;
    }

    /** Canonical, mergeable tender state. */
    record State(String title, String titleLang, String description, String countryCode, String nutsCode,
            String city, UUID authorityId, String referenceNumber, String contractType, String procedureType,
            String mainCpv, List<String> cpvCodes, BigDecimal estimatedValue, String currency,
            BigDecimal estimatedValueEur, Instant submissionDeadline, String submissionDeadlineTz, String status,
            String noticeLanguage, String originalUrl, String documentsUrl) {

        Map<String, Object> tracked() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("title", title);
            m.put("description", description);
            m.put("status", status);
            m.put("submissionDeadline", submissionDeadline == null ? null : submissionDeadline.toString());
            m.put("estimatedValue", estimatedValue == null ? null : estimatedValue.stripTrailingZeros().toPlainString());
            m.put("currency", currency);
            m.put("mainCpv", mainCpv);
            m.put("cpvCodes", cpvCodes.stream().distinct().sorted().toList()); // order is not meaningful
            m.put("procedureType", procedureType);
            m.put("contractType", contractType);
            m.put("documentsUrl", documentsUrl);
            m.put("referenceNumber", referenceNumber);
            return m;
        }

        /** New non-null values win; absent values keep the previous state (a result notice has no deadline). */
        State mergedWith(State n) {
            return new State(or(n.title, title), n.title != null ? n.titleLang : titleLang,
                    or(n.description, description), or(n.countryCode, countryCode), or(n.nutsCode, nutsCode),
                    or(n.city, city), or(n.authorityId, authorityId), or(n.referenceNumber, referenceNumber),
                    or(n.contractType, contractType), or(n.procedureType, procedureType), or(n.mainCpv, mainCpv),
                    n.cpvCodes.isEmpty() ? cpvCodes : n.cpvCodes,
                    n.estimatedValue != null ? n.estimatedValue : estimatedValue,
                    n.estimatedValue != null ? n.currency : currency,
                    n.estimatedValue != null ? n.estimatedValueEur : estimatedValueEur,
                    n.submissionDeadline != null ? n.submissionDeadline : submissionDeadline,
                    n.submissionDeadline != null ? n.submissionDeadlineTz : submissionDeadlineTz,
                    n.status, or(n.noticeLanguage, noticeLanguage), or(n.originalUrl, originalUrl),
                    or(n.documentsUrl, documentsUrl));
        }

        private static <T> T or(T a, T b) {
            return a != null ? a : b;
        }
    }

    @Transactional
    public Outcome upsert(NormalizedNotice n, UUID dataSourceId) {
        Integer seen = jdbc.queryForObject(
                "select count(*) from tender_sources where data_source_id = ? and external_id = ?", Integer.class,
                dataSourceId, n.externalId());
        if (seen != null && seen > 0) {
            return Outcome.SKIPPED;
        }
        Instant now = Instant.now(clock);
        countries.ensure(n.countryCode());
        if (n.buyer() != null) {
            countries.ensure(n.buyer().countryCode());
        }
        UUID authorityId = resolveAuthority(n.buyer());
        BigDecimal eur = rates.toEur(n.estimatedValue(), n.currency(), n.publicationDate()).orElse(null);
        Instant deadline = TenderStatusRules.effectiveDeadline(n.deadlines(), now);

        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, status, latest_order_key, current_version_no from tenders
                where procedure_key = ? for update
                """, n.procedureKey());

        if (rows.isEmpty()) {
            State state = stateOf(n, authorityId, eur, deadline, TenderStatusRules.derive(n, deadline, null, now));
            UUID tenderId = Ids.newId();
            insertTender(tenderId, n, state);
            UUID sourceId = insertSource(tenderId, dataSourceId, n);
            insertVersion(tenderId, 1, sourceId, n, state, List.of());
            replaceCpv(tenderId, state);
            replaceLots(tenderId, n);
            insertResults(tenderId, sourceId, n);
            events.publishEvent(new TenderIngestedEvent(tenderId, true, n.changeKind(), state.status(), List.of(), now));
            return Outcome.CREATED;
        }

        Map<String, Object> row = rows.getFirst();
        UUID tenderId = (UUID) row.get("id");
        long latest = ((Number) row.get("latest_order_key")).longValue();
        int versionNo = ((Number) row.get("current_version_no")).intValue() + 1;
        UUID sourceId = insertSource(tenderId, dataSourceId, n);
        insertResults(tenderId, sourceId, n);
        jdbc.update("update tenders set published_at = least(published_at, ?) where id = ?",
                Timestamp.from(n.publishedAt()), tenderId);

        State noticeState = stateOf(n, authorityId, eur, deadline,
                TenderStatusRules.derive(n, deadline, (String) row.get("status"), now));
        if (n.orderKey() <= latest) {
            // Older notice arriving late (backfill): keep it in the history without overriding the newer state.
            insertVersion(tenderId, versionNo, sourceId, n, noticeState, List.of());
            jdbc.update("update tenders set current_version_no = ? where id = ?", versionNo, tenderId);
            return Outcome.HISTORY_ONLY;
        }

        State before = loadState(tenderId);
        State after = before.mergedWith(noticeState);
        List<Map<String, Object>> diff = diff(before, after);
        updateTender(tenderId, n, after, versionNo);
        insertVersion(tenderId, versionNo, sourceId, n, noticeState, diff);
        if (!noticeState.cpvCodes().isEmpty()) {
            replaceCpv(tenderId, after);
        }
        if (!n.lots().isEmpty()) {
            replaceLots(tenderId, n);
        }
        events.publishEvent(new TenderIngestedEvent(tenderId, false, n.changeKind(), after.status(),
                diff.stream().map(d -> (String) d.get("field")).toList(), now));
        return Outcome.UPDATED;
    }

    static List<Map<String, Object>> diff(State before, State after) {
        Map<String, Object> a = before.tracked();
        Map<String, Object> b = after.tracked();
        List<Map<String, Object>> changes = new ArrayList<>();
        for (String field : TRACKED) {
            if (!Objects.equals(a.get(field), b.get(field))) {
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("field", field);
                if (!"description".equals(field)) {
                    change.put("old", a.get(field));
                    change.put("new", b.get(field));
                }
                changes.add(change);
            }
        }
        return changes;
    }

    private State stateOf(NormalizedNotice n, UUID authorityId, BigDecimal eur, Instant deadline, String status) {
        return new State(n.title(), n.titleLang(), n.description(), n.countryCode(), n.nutsCode(), n.city(),
                authorityId, n.referenceNumber(), n.contractType(), n.procedureType(), n.mainCpv(),
                n.cpvCodes() == null ? List.of() : n.cpvCodes(), n.estimatedValue(),
                n.estimatedValue() == null ? null : n.currency(), eur, deadline, deadline == null ? null : n.deadlineTz(),
                status, n.titleLang(), n.externalUrl(), n.documentsUrl());
    }

    private State loadState(UUID tenderId) {
        List<String> cpv = jdbc.queryForList(
                "select cpv_code from tender_cpv where tender_id = ? order by is_main desc, cpv_code", String.class,
                tenderId);
        return jdbc.queryForObject("""
                select title, title_lang, description, country_code, nuts_code, city, contracting_authority_id,
                       reference_number, contract_type, procedure_type, main_cpv, estimated_value, currency,
                       estimated_value_eur, submission_deadline, submission_deadline_tz, status, notice_language,
                       original_url, documents_url
                from tenders where id = ?
                """, (rs, i) -> {
            Timestamp d = rs.getTimestamp("submission_deadline");
            return new State(rs.getString("title"), rs.getString("title_lang"), rs.getString("description"),
                    rs.getString("country_code"), rs.getString("nuts_code"), rs.getString("city"),
                    rs.getObject("contracting_authority_id", UUID.class), rs.getString("reference_number"),
                    rs.getString("contract_type"), rs.getString("procedure_type"), rs.getString("main_cpv"), cpv,
                    rs.getBigDecimal("estimated_value"), rs.getString("currency"),
                    rs.getBigDecimal("estimated_value_eur"), d == null ? null : d.toInstant(),
                    rs.getString("submission_deadline_tz"), rs.getString("status"), rs.getString("notice_language"),
                    rs.getString("original_url"), rs.getString("documents_url"));
        }, tenderId);
    }

    private void insertTender(UUID id, NormalizedNotice n, State s) {
        jdbc.update("""
                insert into tenders (id, procedure_key, title, title_lang, description, country_code, nuts_code, city,
                    contracting_authority_id, reference_number, contract_type, procedure_type, main_cpv,
                    estimated_value, currency, estimated_value_eur, published_at, submission_deadline,
                    submission_deadline_tz, status, notice_language, original_url, documents_url,
                    current_version_no, latest_order_key, last_changed_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, now())
                """, id, n.procedureKey(), s.title(), s.titleLang(), s.description(), s.countryCode(), s.nutsCode(),
                s.city(), s.authorityId(), s.referenceNumber(), s.contractType(), s.procedureType(), s.mainCpv(),
                s.estimatedValue(), s.currency(), s.estimatedValueEur(), Timestamp.from(n.publishedAt()),
                ts(s.submissionDeadline()), s.submissionDeadlineTz(), s.status(), s.noticeLanguage(), s.originalUrl(),
                s.documentsUrl(), n.orderKey());
    }

    private void updateTender(UUID id, NormalizedNotice n, State s, int versionNo) {
        jdbc.update("""
                update tenders set title = ?, title_lang = ?, description = ?, country_code = ?, nuts_code = ?,
                    city = ?, contracting_authority_id = ?, reference_number = ?, contract_type = ?,
                    procedure_type = ?, main_cpv = ?, estimated_value = ?, currency = ?, estimated_value_eur = ?,
                    submission_deadline = ?, submission_deadline_tz = ?, status = ?, notice_language = ?,
                    original_url = ?, documents_url = ?, current_version_no = ?, latest_order_key = ?,
                    last_changed_at = now(), updated_at = now()
                where id = ?
                """, s.title(), s.titleLang(), s.description(), s.countryCode(), s.nutsCode(), s.city(),
                s.authorityId(), s.referenceNumber(), s.contractType(), s.procedureType(), s.mainCpv(),
                s.estimatedValue(), s.currency(), s.estimatedValueEur(), ts(s.submissionDeadline()),
                s.submissionDeadlineTz(), s.status(), s.noticeLanguage(), s.originalUrl(), s.documentsUrl(), versionNo,
                n.orderKey(), id);
    }

    private UUID insertSource(UUID tenderId, UUID dataSourceId, NormalizedNotice n) {
        UUID id = Ids.newId();
        jdbc.update("""
                insert into tender_sources (id, tender_id, data_source_id, external_id, external_url, notice_type)
                values (?, ?, ?, ?, ?, ?)
                """, id, tenderId, dataSourceId, n.externalId(), n.externalUrl(), n.noticeType());
        return id;
    }

    private void insertVersion(UUID tenderId, int versionNo, UUID sourceId, NormalizedNotice n, State snapshot,
            List<Map<String, Object>> diff) {
        Map<String, Object> snap = new LinkedHashMap<>(snapshot.tracked());
        snap.remove("description");
        snap.put("changeReason", n.changeReason());
        jdbc.update("""
                insert into tender_versions (id, tender_id, version_no, tender_source_id, notice_type, change_kind,
                    snapshot, diff, published_at, order_key, external_id)
                values (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?)
                """, Ids.newId(), tenderId, versionNo, sourceId, n.noticeType(), n.changeKind().name(),
                json.writeValueAsString(snap), json.writeValueAsString(diff), Timestamp.from(n.publishedAt()),
                n.orderKey(), n.externalId());
    }

    private void replaceCpv(UUID tenderId, State s) {
        jdbc.update("delete from tender_cpv where tender_id = ?", tenderId);
        List<Object[]> rows = s.cpvCodes().stream().distinct()
                .map(code -> new Object[] {tenderId, code, code.equals(s.mainCpv())}).toList();
        jdbc.batchUpdate("insert into tender_cpv (tender_id, cpv_code, is_main) values (?, ?, ?)", rows);
    }

    private void replaceLots(UUID tenderId, NormalizedNotice n) {
        if (n.lots().isEmpty()) {
            return;
        }
        jdbc.update("delete from tender_lots where tender_id = ? and not exists "
                + "(select 1 from tender_results r where r.lot_id = tender_lots.id)", tenderId);
        for (Lot lot : n.lots()) {
            jdbc.update("""
                    insert into tender_lots (id, tender_id, lot_number, title, estimated_value, currency,
                        submission_deadline)
                    values (?, ?, ?, ?, ?, ?, ?)
                    on conflict (tender_id, lot_number) do update set title = excluded.title,
                        estimated_value = excluded.estimated_value, currency = excluded.currency,
                        submission_deadline = excluded.submission_deadline
                    """, Ids.newId(), tenderId, lot.identifier(), lot.title(), lot.estimatedValue(), lot.currency(),
                    ts(lot.deadline()));
        }
    }

    private void insertResults(UUID tenderId, UUID sourceId, NormalizedNotice n) {
        for (Winner w : n.winners()) {
            countries.ensure(w.countryCode());
            UUID operatorId = resolveOperator(w);
            BigDecimal eur = rates.toEur(w.value(), w.currency(), n.publicationDate()).orElse(null);
            jdbc.update("""
                    insert into tender_results (id, tender_id, winner_id, awarded_value, currency, awarded_value_eur,
                        tenders_received, award_date, tender_source_id, winner_size)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Ids.newId(), tenderId, operatorId, w.value(), w.value() == null ? null : w.currency(), eur,
                    n.tendersReceived(), n.awardDate(), sourceId, w.size());
        }
    }

    /** Authorities are matched by national identifier within a country, otherwise by normalized name. */
    UUID resolveAuthority(Party p) {
        if (p == null || p.name() == null) {
            return null;
        }
        return resolveParty("contracting_authorities", p.name(), p.nationalId(), p.countryCode(), () -> {
            UUID id = Ids.newId();
            jdbc.update("""
                    insert into contracting_authorities (id, name, normalized_name, national_id, country_code, city,
                        website)
                    values (?, ?, immutable_unaccent(lower(?)), ?, ?, ?, ?)
                    """, id, p.name(), p.name(), p.nationalId(), p.countryCode(), p.city(), p.website());
            return id;
        });
    }

    private UUID resolveOperator(Winner w) {
        if (w.name() == null) {
            return null;
        }
        return resolveParty("economic_operators", w.name(), w.nationalId(), w.countryCode(), () -> {
            UUID id = Ids.newId();
            jdbc.update("""
                    insert into economic_operators (id, name, normalized_name, national_id, country_code, is_sme)
                    values (?, ?, immutable_unaccent(lower(?)), ?, ?, ?)
                    """, id, w.name(), w.name(), w.nationalId(), w.countryCode(),
                    w.size() == null ? null : !"large".equals(w.size()));
            return id;
        });
    }

    private UUID resolveParty(String table, String name, String nationalId, String country,
            java.util.function.Supplier<UUID> create) {
        // table is one of two constants above – never user input.
        if (nationalId != null && country != null) {
            List<UUID> byId = jdbc.queryForList("select id from " + table
                    + " where country_code = ? and national_id = ?", UUID.class, country, nationalId);
            if (!byId.isEmpty()) {
                return byId.getFirst();
            }
        }
        List<UUID> byName = jdbc.queryForList("select id from " + table
                + " where country_code is not distinct from cast(? as char(2))"
                + " and normalized_name = immutable_unaccent(lower(cast(? as text)))"
                + " and (national_id is null or cast(? as text) is null) limit 1", UUID.class, country, name,
                nationalId);
        if (!byName.isEmpty()) {
            return byName.getFirst();
        }
        return create.get();
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}
