package eu.tenderintel.matching;

import eu.tenderintel.common.api.PageResponse;
import eu.tenderintel.common.error.ApiException;
import eu.tenderintel.company.CompanyProfileService.CompanyProfileChangedEvent;
import eu.tenderintel.ingestion.TenderIngestedEvent;
import eu.tenderintel.matching.MatchingEngine.Profile;
import eu.tenderintel.matching.MatchingEngine.Reference;
import eu.tenderintel.matching.MatchingEngine.Result;
import eu.tenderintel.matching.MatchingEngine.Tender;
import eu.tenderintel.organization.OrganizationAccess;
import eu.tenderintel.tender.TenderDtos.TenderSummary;
import eu.tenderintel.tender.TenderQueries;
import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps {@code tender_matches} up to date and explains scores. Matches are stored for open tenders that share at least
 * a CPV division with the profile and score at least {@link #MIN_STORED}.
 */
@Service
public class MatchingService {

    public static final int MIN_STORED = 40;
    private static final Logger log = LoggerFactory.getLogger(MatchingService.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final OrganizationAccess access;
    private final TenderQueries tenders;
    private final JsonMapper json;
    private final ExecutorService executor;
    private final NutsRegions nuts;

    MatchingService(JdbcTemplate jdbc, TransactionTemplate tx, OrganizationAccess access, TenderQueries tenders,
            JsonMapper json, NutsRegions nuts) {
        this.nuts = nuts;
        this.jdbc = jdbc;
        this.tx = tx;
        this.access = access;
        this.tenders = tenders;
        this.json = json;
        // One background worker: a TED backfill publishes thousands of events and must not compete with requests for
        // database connections. Work is queued in order.
        this.executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("matching").daemon().factory());
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    public record MatchView(int score, MatchingEngine.Result breakdown, TenderSummary tender) {
    }

    // --- events -------------------------------------------------------------------------------------------------

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onProfileChanged(CompanyProfileChangedEvent event) {
        profileCache = null;
        executor.execute(() -> safely(() -> recompute(event.organizationId())));
    }

    /**
     * All company profiles, loaded in three queries and kept until a profile changes. Scoring a newly ingested
     * notice is then pure computation (an import publishes thousands of events).
     */
    private volatile Map<UUID, Profile> profileCache;
    private volatile long profileCacheAt;
    /** Other instances change profiles too: the cache is also refreshed after this long. */
    static final long PROFILE_CACHE_MILLIS = 5 * 60_000;

    Map<UUID, Profile> profiles() {
        Map<UUID, Profile> cached = profileCache;
        if (cached != null && System.currentTimeMillis() - profileCacheAt < PROFILE_CACHE_MILLIS) {
            return cached;
        }
        Map<UUID, Profile> result = Map.copyOf(loadProfiles(null));
        profileCache = result;
        profileCacheAt = System.currentTimeMillis();
        return result;
    }

    /** Profiles of all organizations ({@code orgId} null) or of one, with CPV codes and references. */
    private Map<UUID, Profile> loadProfiles(UUID orgId) {
        String filter = orgId == null ? "" : " where p.organization_id = ?";
        Object[] args = orgId == null ? new Object[0] : new Object[] {orgId};
        Map<UUID, List<String>> cpv = new java.util.HashMap<>();
        jdbc.query("select p.organization_id, c.cpv_code from company_cpv c join company_profiles p "
                + "on p.id = c.company_profile_id" + filter + " order by c.cpv_code", rs -> {
                    cpv.computeIfAbsent(rs.getObject(1, UUID.class), k -> new java.util.ArrayList<>())
                            .add(rs.getString(2));
                }, args);
        Map<UUID, List<Reference>> refs = new java.util.HashMap<>();
        jdbc.query("select p.organization_id, r.title, r.cpv_code from company_references r join company_profiles p "
                + "on p.id = r.company_profile_id" + filter, rs -> {
                    refs.computeIfAbsent(rs.getObject(1, UUID.class), k -> new java.util.ArrayList<>())
                            .add(new Reference(rs.getString(2), rs.getString(3)));
                }, args);
        Map<UUID, Profile> loaded = new java.util.HashMap<>();
        jdbc.query("""
                select p.organization_id, p.countries_of_interest, p.registration_country, p.contract_types,
                       p.min_contract_value_eur, p.max_contract_value_eur, p.base_nuts, p.max_distance_km,
                       p.region_mode, p.extra_regions, p.supplies_nationwide, p.include_keywords, p.exclude_keywords,
                       p.excluded_cpv
                from company_profiles p
                """ + filter, rs -> {
                    UUID org = rs.getObject(1, UUID.class);
                    String base = rs.getString(7);
                    loaded.put(org, new Profile(cpv.getOrDefault(org, List.of()), list(rs.getArray(2)),
                            rs.getString(3) == null ? null : rs.getString(3).trim(), list(rs.getArray(4)),
                            rs.getBigDecimal(5), rs.getBigDecimal(6), refs.getOrDefault(org, List.of()), base,
                            nuts.point(base), (Integer) rs.getObject(8, Integer.class), "STRICT".equals(rs.getString(9)),
                            list(rs.getArray(10)), rs.getBoolean(11), list(rs.getArray(12)), list(rs.getArray(13)),
                            list(rs.getArray(14))));
                }, args);
        return loaded;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onTenderIngested(TenderIngestedEvent event) {
        executor.execute(() -> safely(() -> rescoreTender(event.tenderId())));
    }

    private void safely(Runnable task) {
        try {
            task.run();
        } catch (RuntimeException e) {
            // E.g. an organization deleted since the cache was loaded: reload next time.
            profileCache = null;
            log.error("Matching task failed", e);
        }
    }

    // --- computation --------------------------------------------------------------------------------------------

    /** Recomputes all matches of one organization (after a profile change). Returns the number stored. */
    public int recompute(UUID orgId) {
        Integer stored = tx.execute(status -> {
            Profile profile = loadProfile(orgId);
            jdbc.update("delete from tender_matches where organization_id = ?", orgId);
            if (profile == null || profile.cpvCodes().isEmpty()) {
                return 0;
            }
            String[] divisions = profile.cpvCodes().stream().map(c -> c.substring(0, 2)).distinct()
                    .toArray(String[]::new);
            // STRICT: tenders from countries outside the profile can never match – do not even read them.
            String[] countries = strictCountries(profile);
            List<Object[]> rows = new java.util.ArrayList<>();
            jdbc.query("""
                    select TENDER_COLUMNS
                    from tenders t
                    where t.status = 'OPEN'
                      and (cardinality(?::text[]) = 0 or t.country_code = any (?::text[]))
                      and (left(t.main_cpv, 2) = any (?)
                           or exists (select 1 from tender_cpv tc where tc.tender_id = t.id
                                      and left(tc.cpv_code, 2) = any (?)))
                    """.replace("TENDER_COLUMNS", TENDER_COLUMNS), rs -> {
                Tender tender = tender(rs);
                Result result = MatchingEngine.score(profile, tender);
                if (result.score() >= MIN_STORED) {
                    rows.add(new Object[] {orgId, rs.getObject(1, UUID.class), result.score(),
                            json.writeValueAsString(result), MatchingEngine.VERSION});
                }
            }, countries, countries, divisions, divisions);
            jdbc.batchUpdate("""
                    insert into tender_matches (organization_id, tender_id, score, breakdown, engine_version)
                    values (?, ?, ?, ?::jsonb, ?)
                    """, rows);
            return rows.size();
        });
        log.info("Matching recomputed for organization {}: {} matches", orgId, stored);
        return stored == null ? 0 : stored;
    }

    /** Scores one tender for every organization with a profile (after ingestion of a new or changed notice). */
    public void rescoreTender(UUID tenderId) {
        Map<UUID, Profile> profiles = profiles();
        if (profiles.isEmpty()) {
            return;
        }
        tx.executeWithoutResult(status -> {
            jdbc.update("delete from tender_matches where tender_id = ?", tenderId);
            Tender tender = loadTender(tenderId);
            Boolean open = jdbc.queryForObject("select status = 'OPEN' from tenders where id = ?", Boolean.class,
                    tenderId);
            if (tender == null || !Boolean.TRUE.equals(open)) {
                return;
            }
            for (Map.Entry<UUID, Profile> e : profiles.entrySet()) {
                UUID orgId = e.getKey();
                Profile profile = e.getValue();
                if (profile == null || profile.cpvCodes().isEmpty()) {
                    continue;
                }
                Result result = MatchingEngine.score(profile, tender);
                if (result.score() >= MIN_STORED) {
                    jdbc.update("""
                            insert into tender_matches (organization_id, tender_id, score, breakdown, engine_version)
                            values (?, ?, ?, ?::jsonb, ?)
                            """, orgId, tenderId, result.score(), json.writeValueAsString(result),
                            MatchingEngine.VERSION);
                }
            }
        });
    }

    // --- queries ------------------------------------------------------------------------------------------------

    public PageResponse<MatchView> list(UUID orgId, UUID userId, int minScore, int page, int size, String lang) {
        access.requireMember(orgId, userId);
        Long total = jdbc.queryForObject("""
                select count(*) from tender_matches m join tenders t on t.id = m.tender_id
                where m.organization_id = ? and m.score >= ? and t.status = 'OPEN'
                  and not exists (select 1 from match_dismissals d where d.organization_id = m.organization_id
                                  and d.tender_id = m.tender_id)
                  and (t.submission_deadline is null or t.submission_deadline >= now() + make_interval(days =>
                       coalesce((select p.min_days_to_deadline from company_profiles p
                                 where p.organization_id = m.organization_id), 0)))
                """, Long.class, orgId, minScore);
        List<Object[]> rows = jdbc.query("""
                select m.tender_id, m.score, m.breakdown::text from tender_matches m join tenders t on t.id = m.tender_id
                where m.organization_id = ? and m.score >= ? and t.status = 'OPEN'
                  and not exists (select 1 from match_dismissals d where d.organization_id = m.organization_id
                                  and d.tender_id = m.tender_id)
                  and (t.submission_deadline is null or t.submission_deadline >= now() + make_interval(days =>
                       coalesce((select p.min_days_to_deadline from company_profiles p
                                 where p.organization_id = m.organization_id), 0)))
                order by m.score desc, t.submission_deadline nulls last, t.id
                limit ? offset ?
                """, (rs, i) -> new Object[] {rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3)}, orgId,
                minScore, size, page * size);
        Map<UUID, TenderSummary> summaries = tenders.summaries(rows.stream().map(r -> (UUID) r[0]).toList(), lang);
        List<MatchView> items = rows.stream().filter(r -> summaries.containsKey((UUID) r[0]))
                .map(r -> new MatchView((Integer) r[1], json.readValue((String) r[2], Result.class),
                        summaries.get((UUID) r[0])))
                .toList();
        return PageResponse.of(items, page, size, total == null ? 0 : total);
    }

    /** Live explanation for any tender (also below the storage threshold or closed). */
    public MatchView explain(UUID orgId, UUID userId, UUID tenderId, String lang) {
        access.requireMember(orgId, userId);
        Profile profile = loadProfile(orgId);
        if (profile == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "profile_missing", "The organization has no company profile");
        }
        Tender tender = loadTender(tenderId);
        if (tender == null) {
            throw ApiException.notFound("Tender");
        }
        Result result = MatchingEngine.score(profile, tender);
        return new MatchView(result.score(), result, tenders.summaries(List.of(tenderId), lang).get(tenderId));
    }

    public long countAtLeast(UUID orgId, int minScore) {
        Long n = jdbc.queryForObject("""
                select count(*) from tender_matches m join tenders t on t.id = m.tender_id
                where m.organization_id = ? and m.score >= ? and t.status = 'OPEN'
                  and not exists (select 1 from match_dismissals d where d.organization_id = m.organization_id
                                  and d.tender_id = m.tender_id)
                  and (t.submission_deadline is null or t.submission_deadline >= now() + make_interval(days =>
                       coalesce((select p.min_days_to_deadline from company_profiles p
                                 where p.organization_id = m.organization_id), 0)))
                """, Long.class, orgId, minScore);
        return n == null ? 0 : n;
    }

    static final java.util.Set<String> DISMISS_REASONS = java.util.Set.of("LOCATION", "SECTOR", "VALUE",
            "CONTRACT_TYPE", "REQUIREMENTS", "DEADLINE", "OTHER");

    /** "Not relevant": hides the tender from the organization's matches; the reason helps to tune the profile. */
    public void dismiss(UUID orgId, UUID userId, UUID tenderId, String reason) {
        access.requireMember(orgId, userId);
        if (!DISMISS_REASONS.contains(reason)) {
            throw ApiException.badRequest("invalid_reason", "Unknown reason");
        }
        Integer exists = jdbc.queryForObject("select count(*) from tenders where id = ?", Integer.class, tenderId);
        if (exists == null || exists == 0) {
            throw ApiException.notFound("Tender");
        }
        jdbc.update("""
                insert into match_dismissals (organization_id, tender_id, reason, user_id) values (?, ?, ?, ?)
                on conflict (organization_id, tender_id) do update set reason = excluded.reason,
                    user_id = excluded.user_id, created_at = now()
                """, orgId, tenderId, reason, userId);
    }

    public void undismiss(UUID orgId, UUID userId, UUID tenderId) {
        access.requireMember(orgId, userId);
        jdbc.update("delete from match_dismissals where organization_id = ? and tender_id = ?", orgId, tenderId);
    }

    /** How often each reason was given in the last 90 days (drives hints such as "narrow the radius"). */
    public Map<String, Long> dismissalReasons(UUID orgId, UUID userId) {
        access.requireMember(orgId, userId);
        Map<String, Long> out = new java.util.LinkedHashMap<>();
        jdbc.query("""
                select reason, count(*) from match_dismissals
                where organization_id = ? and created_at > now() - interval '90 days'
                group by reason order by count(*) desc
                """, rs -> {
                    out.put(rs.getString(1), rs.getLong(2));
                }, orgId);
        return out;
    }

    /** After an engine change, stored matches are recomputed with the new rules (in the background). */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    void recomputeOutdated() {
        List<UUID> orgs = jdbc.queryForList("""
                select p.organization_id from company_profiles p
                where exists (select 1 from tender_matches m where m.organization_id = p.organization_id
                              and m.engine_version <> ?)
                """, UUID.class, MatchingEngine.VERSION);
        if (!orgs.isEmpty()) {
            log.info("Matching engine v{}: recomputing {} organizations", MatchingEngine.VERSION, orgs.size());
            orgs.forEach(org -> executor.execute(() -> safely(() -> recompute(org))));
        }
    }

    public boolean hasProfile(UUID orgId) {
        Integer n = jdbc.queryForObject("select count(*) from company_profiles where organization_id = ?",
                Integer.class, orgId);
        return n != null && n > 0;
    }

    Profile loadProfile(UUID orgId) {
        return loadProfiles(orgId).get(orgId);
    }

    /** id, country, CPV, type, value, NUTS and text (title + start of the description, for keywords). */
    static final String TENDER_COLUMNS = """
            t.id, t.country_code, t.main_cpv, t.contract_type, t.estimated_value_eur,
            coalesce((select array_agg(cpv_code) from tender_cpv tc where tc.tender_id = t.id), '{}'),
            t.nuts_code, left(coalesce(t.title, '') || ' ' || coalesce(t.description, ''), 4000)
            """;

    private Tender tender(java.sql.ResultSet rs) throws SQLException {
        String nutsCode = rs.getString(7);
        return new Tender(rs.getString(2).trim(), rs.getString(3), list(rs.getArray(6)), rs.getString(4),
                rs.getBigDecimal(5), nutsCode, nuts.point(nutsCode), rs.getString(8));
    }

    private Tender loadTender(UUID tenderId) {
        List<Tender> rows = jdbc.query("select " + TENDER_COLUMNS + " from tenders t where t.id = ?",
                (rs, i) -> tender(rs), tenderId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static List<String> list(Array array) throws SQLException {
        return array == null ? List.of()
                : Arrays.stream((Object[]) array.getArray()).map(o -> o.toString().strip()).toList();
    }

    /**
     * STRICT: the only countries a tender can match in – the profile's countries (or the home country) plus the
     * countries of extra regions. Empty = no filter (not strict, or nothing known about the company's location).
     */
    static String[] strictCountries(Profile profile) {
        if (!profile.strictRegion()) {
            return new String[0];
        }
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        if (!profile.countriesOfInterest().isEmpty()) {
            out.addAll(profile.countriesOfInterest());
        } else if (profile.baseNuts() != null) {
            out.add(NutsRegions.iso(profile.baseNuts().substring(0, 2)));
        } else if (profile.registrationCountry() != null) {
            out.add(profile.registrationCountry());
        } else {
            return new String[0];
        }
        profile.extraRegions().forEach(r -> out.add(NutsRegions.iso(r.substring(0, 2))));
        return out.toArray(String[]::new);
    }
}
