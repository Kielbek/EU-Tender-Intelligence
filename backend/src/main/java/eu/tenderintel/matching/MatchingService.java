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

    MatchingService(JdbcTemplate jdbc, TransactionTemplate tx, OrganizationAccess access, TenderQueries tenders,
            JsonMapper json) {
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
        executor.execute(() -> safely(() -> recompute(event.organizationId())));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onTenderIngested(TenderIngestedEvent event) {
        executor.execute(() -> safely(() -> rescoreTender(event.tenderId())));
    }

    private static void safely(Runnable task) {
        try {
            task.run();
        } catch (RuntimeException e) {
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
            List<Object[]> rows = new java.util.ArrayList<>();
            jdbc.query("""
                    select t.id, t.country_code, t.main_cpv, t.contract_type, t.estimated_value_eur,
                           coalesce((select array_agg(cpv_code) from tender_cpv tc where tc.tender_id = t.id), '{}')
                    from tenders t
                    where t.status = 'OPEN'
                      and (left(t.main_cpv, 2) = any (?)
                           or exists (select 1 from tender_cpv tc where tc.tender_id = t.id
                                      and left(tc.cpv_code, 2) = any (?)))
                    """, rs -> {
                Tender tender = new Tender(rs.getString(2), rs.getString(3), list(rs.getArray(6)), rs.getString(4),
                        rs.getBigDecimal(5));
                Result result = MatchingEngine.score(profile, tender);
                if (result.score() >= MIN_STORED) {
                    rows.add(new Object[] {orgId, rs.getObject(1, UUID.class), result.score(),
                            json.writeValueAsString(result), MatchingEngine.VERSION});
                }
            }, divisions, divisions);
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
        List<UUID> orgs = jdbc.queryForList("select organization_id from company_profiles", UUID.class);
        if (orgs.isEmpty()) {
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
            for (UUID orgId : orgs) {
                Profile profile = loadProfile(orgId);
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
                """, Long.class, orgId, minScore);
        List<Object[]> rows = jdbc.query("""
                select m.tender_id, m.score, m.breakdown::text from tender_matches m join tenders t on t.id = m.tender_id
                where m.organization_id = ? and m.score >= ? and t.status = 'OPEN'
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
                """, Long.class, orgId, minScore);
        return n == null ? 0 : n;
    }

    public boolean hasProfile(UUID orgId) {
        Integer n = jdbc.queryForObject("select count(*) from company_profiles where organization_id = ?",
                Integer.class, orgId);
        return n != null && n > 0;
    }

    Profile loadProfile(UUID orgId) {
        List<Object[]> rows = jdbc.query("""
                select id, countries_of_interest, registration_country, contract_types, min_contract_value_eur,
                       max_contract_value_eur
                from company_profiles where organization_id = ?
                """, (rs, i) -> new Object[] {rs.getObject(1, UUID.class), list(rs.getArray(2)), rs.getString(3),
                list(rs.getArray(4)), rs.getBigDecimal(5), rs.getBigDecimal(6)}, orgId);
        if (rows.isEmpty()) {
            return null;
        }
        Object[] r = rows.getFirst();
        UUID pid = (UUID) r[0];
        List<String> cpv = jdbc.queryForList("select cpv_code from company_cpv where company_profile_id = ? "
                + "order by cpv_code", String.class, pid);
        List<Reference> refs = jdbc.query("select title, cpv_code from company_references where company_profile_id = ?",
                (rs, i) -> new Reference(rs.getString(1), rs.getString(2)), pid);
        @SuppressWarnings("unchecked")
        Profile profile = new Profile(cpv, (List<String>) r[1], (String) r[2], (List<String>) r[3],
                (java.math.BigDecimal) r[4], (java.math.BigDecimal) r[5], refs);
        return profile;
    }

    private Tender loadTender(UUID tenderId) {
        List<Tender> rows = jdbc.query("""
                select t.country_code, t.main_cpv, t.contract_type, t.estimated_value_eur,
                       coalesce((select array_agg(cpv_code) from tender_cpv tc where tc.tender_id = t.id), '{}')
                from tenders t where t.id = ?
                """, (rs, i) -> new Tender(rs.getString(1), rs.getString(2), list(rs.getArray(5)), rs.getString(3),
                rs.getBigDecimal(4)), tenderId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static List<String> list(Array array) throws SQLException {
        return array == null ? List.of()
                : Arrays.stream((Object[]) array.getArray()).map(o -> o.toString().strip()).toList();
    }
}
