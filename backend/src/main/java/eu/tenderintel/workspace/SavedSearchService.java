package eu.tenderintel.workspace;

import eu.tenderintel.billing.Entitlements;
import eu.tenderintel.common.error.ApiException;
import eu.tenderintel.common.id.Ids;
import eu.tenderintel.organization.OrganizationAccess;
import eu.tenderintel.workspace.WorkspaceDtos.SavedSearchView;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Personal saved searches (per user, within an organization). Filters are validated against the same rules as the
 * search API, so a saved search can always be executed.
 */
@Service
public class SavedSearchService {

    /** Keys accepted in saved filters – exactly the search parameters, without paging. */
    static final Set<String> ALLOWED_KEYS = Set.of("q", "country", "cpv", "status", "contractType", "procedureType",
            "authority", "city", "nuts", "currency", "language", "valueMinEur", "valueMaxEur", "publishedFrom",
            "publishedTo", "deadlineFrom", "deadlineTo", "sort", "direction");

    private final JdbcTemplate jdbc;
    private final OrganizationAccess access;
    private final JsonMapper json;
    private final Validator validator;
    private final Entitlements entitlements;

    SavedSearchService(JdbcTemplate jdbc, OrganizationAccess access, JsonMapper json, Validator validator,
            Entitlements entitlements) {
        this.jdbc = jdbc;
        this.access = access;
        this.json = json;
        this.validator = validator;
        this.entitlements = entitlements;
    }

    @Transactional(readOnly = true)
    public List<SavedSearchView> list(UUID orgId, UUID userId) {
        access.requireMember(orgId, userId);
        return jdbc.query("""
                select id, name, filters::text, created_at, updated_at, last_run_at, alert_enabled, alert_frequency
                from saved_searches
                where organization_id = ? and user_id = ? order by name
                """, (rs, i) -> new SavedSearchView(rs.getObject(1, UUID.class), rs.getString(2),
                json.readValue(rs.getString(3), new TypeReference<Map<String, Object>>() { }),
                rs.getTimestamp(4).toInstant(), rs.getTimestamp(5).toInstant(),
                rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant(), rs.getBoolean(7),
                rs.getString(8) == null ? "DAILY" : rs.getString(8)), orgId, userId);
    }

    @Transactional
    public SavedSearchView create(UUID orgId, UUID userId, String name, Map<String, Object> filters) {
        access.requireMember(orgId, userId);
        entitlements.requireSavedSearchSlot(orgId, userId);
        String filtersJson = validated(filters);
        UUID id = Ids.newId();
        try {
            jdbc.update("""
                    insert into saved_searches (id, organization_id, user_id, name, filters)
                    values (?, ?, ?, ?, ?::jsonb)
                    """, id, orgId, userId, name.strip(), filtersJson);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("duplicate_name", "A saved search with this name already exists");
        }
        return find(orgId, userId, id);
    }

    @Transactional
    public SavedSearchView update(UUID orgId, UUID userId, UUID id, String name, Map<String, Object> filters) {
        access.requireMember(orgId, userId);
        find(orgId, userId, id);
        try {
            jdbc.update("""
                    update saved_searches set name = coalesce(?, name), filters = coalesce(?::jsonb, filters),
                        updated_at = now()
                    where id = ? and organization_id = ? and user_id = ?
                    """, name == null ? null : name.strip(), filters == null ? null : validated(filters), id, orgId,
                    userId);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("duplicate_name", "A saved search with this name already exists");
        }
        return find(orgId, userId, id);
    }

    /** Called when the user opens the saved search (used later to compute "new since last visit"). */
    @Transactional
    public SavedSearchView markRun(UUID orgId, UUID userId, UUID id) {
        access.requireMember(orgId, userId);
        find(orgId, userId, id);
        jdbc.update("update saved_searches set last_run_at = now() where id = ?", id);
        return find(orgId, userId, id);
    }

    /**
     * Switches the alert on or off. When switched on, only tenders added from now on are reported (no flood of
     * everything that already matches). {@code INSTANT} e-mails need a plan with instant alerts.
     */
    @Transactional
    public SavedSearchView setAlert(UUID orgId, UUID userId, UUID id, boolean enabled, String frequency) {
        access.requireMember(orgId, userId);
        SavedSearchView current = find(orgId, userId, id);
        String f = frequency == null ? current.alertFrequency() : frequency;
        if (enabled && "INSTANT".equals(f) && !entitlements.plan(orgId).instantEmails()) {
            throw new ApiException(org.springframework.http.HttpStatus.PAYMENT_REQUIRED,
                    "instant_alerts_unavailable", "Instant e-mails are not part of the plan",
                    Map.of("plan", entitlements.plan(orgId).name()));
        }
        jdbc.update("""
                update saved_searches set alert_enabled = ?, alert_frequency = ?, updated_at = now(),
                    alert_checked_at = case when ? and not alert_enabled then now() else alert_checked_at end
                where id = ? and organization_id = ? and user_id = ?
                """, enabled, f, enabled, id, orgId, userId);
        return find(orgId, userId, id);
    }

    @Transactional
    public void delete(UUID orgId, UUID userId, UUID id) {
        access.requireMember(orgId, userId);
        jdbc.update("delete from saved_searches where id = ? and organization_id = ? and user_id = ?", id, orgId,
                userId);
    }

    private SavedSearchView find(UUID orgId, UUID userId, UUID id) {
        return list(orgId, userId).stream().filter(s -> s.id().equals(id)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Saved search"));
    }

    /** Rejects unknown keys and values the search API would reject; returns canonical JSON. */
    String validated(Map<String, Object> filters) {
        for (String key : filters.keySet()) {
            if (!ALLOWED_KEYS.contains(key)) {
                throw ApiException.badRequest("invalid_filters", "Unknown filter: " + key);
            }
        }
        eu.tenderintel.tender.TenderDtos.TenderSearchCriteria criteria;
        try {
            criteria = json.convertValue(filters, eu.tenderintel.tender.TenderDtos.TenderSearchCriteria.class);
        } catch (RuntimeException e) {
            throw ApiException.badRequest("invalid_filters", "Filters have invalid values");
        }
        if (!validator.validate(criteria).isEmpty()) {
            throw ApiException.badRequest("invalid_filters", "Filters have invalid values");
        }
        return json.writeValueAsString(filters);
    }
}
