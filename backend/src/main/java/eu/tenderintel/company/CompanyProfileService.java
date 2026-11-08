package eu.tenderintel.company;

import eu.tenderintel.audit.AuditAction;
import eu.tenderintel.audit.AuditService;
import eu.tenderintel.common.error.ApiException;
import eu.tenderintel.common.id.Ids;
import eu.tenderintel.company.CompanyDtos.CertificationRequest;
import eu.tenderintel.company.CompanyDtos.CertificationView;
import eu.tenderintel.company.CompanyDtos.CompanyProfileRequest;
import eu.tenderintel.company.CompanyDtos.CompanyProfileView;
import eu.tenderintel.company.CompanyDtos.ReferenceRequest;
import eu.tenderintel.company.CompanyDtos.ReferenceView;
import eu.tenderintel.organization.OrganizationAccess;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The organization's company profile – private data, visible to members only, editable by COMPANY_ADMIN. Every
 * change publishes {@link CompanyProfileChangedEvent} so matches are recomputed.
 */
@Service
public class CompanyProfileService {

    private final JdbcTemplate jdbc;
    private final OrganizationAccess access;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    CompanyProfileService(JdbcTemplate jdbc, OrganizationAccess access, AuditService audit,
            ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
        this.events = events;
    }

    public record CompanyProfileChangedEvent(UUID organizationId) {
    }

    @Transactional(readOnly = true)
    public CompanyProfileView get(UUID orgId, UUID userId, String lang) {
        access.requireMember(orgId, userId);
        CompanyProfileView view = find(orgId, lang);
        if (view == null) {
            throw new ApiException(org.springframework.http.HttpStatus.NOT_FOUND, "profile_missing",
                    "The organization has no company profile yet");
        }
        return view;
    }

    @Transactional
    public CompanyProfileView save(UUID orgId, UUID userId, CompanyProfileRequest r, String lang) {
        access.requireAdmin(orgId, userId);
        if (r.minContractValueEur() != null && r.maxContractValueEur() != null
                && r.minContractValueEur().compareTo(r.maxContractValueEur()) > 0) {
            throw ApiException.badRequest("invalid_value_range", "Minimum contract value exceeds the maximum");
        }
        UUID id = profileId(orgId);
        if (id == null) {
            id = Ids.newId();
            jdbc.update("insert into company_profiles (id, organization_id, name) values (?, ?, ?)", id, orgId,
                    r.name().strip());
        }
        jdbc.update("""
                update company_profiles set name = ?, registration_country = ?, industry = ?, description = ?,
                    website = ?, countries_of_interest = ?::char(2)[], contract_types = ?::text[], company_size = ?,
                    employees = ?, annual_revenue = ?, revenue_currency = ?, years_of_experience = ?,
                    min_contract_value_eur = ?, max_contract_value_eur = ?, technical_resources = ?,
                    staff_resources = ?, financial_capacity = ?, updated_at = now()
                where id = ?
                """, r.name().strip(), r.registrationCountry(), blank(r.industry()), blank(r.description()),
                blank(r.website()), pgArray(r.countriesOfInterest()), pgArray(r.contractTypes()), r.companySize(),
                r.employees(), r.annualRevenue(), r.annualRevenue() == null ? null : r.revenueCurrency(),
                r.yearsOfExperience(), r.minContractValueEur(), r.maxContractValueEur(),
                blank(r.technicalResources()), blank(r.staffResources()), blank(r.financialCapacity()), id);
        jdbc.update("delete from company_cpv where company_profile_id = ?", id);
        UUID pid = id;
        jdbc.batchUpdate("insert into company_cpv (company_profile_id, cpv_code) values (?, ?)",
                r.cpvCodes().stream().distinct().map(c -> new Object[] {pid, c}).toList());
        audit.record(AuditAction.COMPANY_PROFILE_UPDATED, userId, orgId, "company_profile", id, Map.of());
        events.publishEvent(new CompanyProfileChangedEvent(orgId));
        return find(orgId, lang);
    }

    @Transactional
    public CertificationView addCertification(UUID orgId, UUID userId, CertificationRequest r) {
        access.requireAdmin(orgId, userId);
        UUID pid = requireProfile(orgId);
        UUID id = Ids.newId();
        jdbc.update("insert into company_certifications (id, company_profile_id, name, issuer, valid_until) "
                + "values (?, ?, ?, ?, ?)", id, pid, r.name().strip(), blank(r.issuer()), r.validUntil());
        events.publishEvent(new CompanyProfileChangedEvent(orgId));
        return new CertificationView(id, r.name().strip(), blank(r.issuer()), r.validUntil());
    }

    @Transactional
    public void deleteCertification(UUID orgId, UUID userId, UUID id) {
        access.requireAdmin(orgId, userId);
        UUID pid = requireProfile(orgId);
        jdbc.update("delete from company_certifications where id = ? and company_profile_id = ?", id, pid);
        events.publishEvent(new CompanyProfileChangedEvent(orgId));
    }

    @Transactional
    public ReferenceView addReference(UUID orgId, UUID userId, ReferenceRequest r) {
        access.requireAdmin(orgId, userId);
        UUID pid = requireProfile(orgId);
        UUID id = Ids.newId();
        jdbc.update("""
                insert into company_references (id, company_profile_id, title, client, country_code, value, currency,
                    year_completed, cpv_code, description)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, pid, r.title().strip(), blank(r.client()), r.countryCode(), r.value(),
                r.value() == null ? null : r.currency(), r.yearCompleted(), r.cpvCode(), blank(r.description()));
        events.publishEvent(new CompanyProfileChangedEvent(orgId));
        return new ReferenceView(id, r.title().strip(), blank(r.client()), r.countryCode(), r.value(),
                r.value() == null ? null : r.currency(), r.yearCompleted(), r.cpvCode(), blank(r.description()));
    }

    @Transactional
    public void deleteReference(UUID orgId, UUID userId, UUID id) {
        access.requireAdmin(orgId, userId);
        UUID pid = requireProfile(orgId);
        jdbc.update("delete from company_references where id = ? and company_profile_id = ?", id, pid);
        events.publishEvent(new CompanyProfileChangedEvent(orgId));
    }

    private UUID requireProfile(UUID orgId) {
        UUID id = profileId(orgId);
        if (id == null) {
            throw ApiException.conflict("profile_missing", "Save the company profile first");
        }
        return id;
    }

    private UUID profileId(UUID orgId) {
        List<UUID> ids = jdbc.queryForList("select id from company_profiles where organization_id = ?", UUID.class,
                orgId);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private CompanyProfileView find(UUID orgId, String lang) {
        String l = lang != null && lang.matches("[a-z]{2}") ? lang : "en";
        List<CompanyProfileView> rows = jdbc.query("select * from company_profiles where organization_id = ?",
                (rs, i) -> view(rs, l), orgId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private CompanyProfileView view(ResultSet rs, String lang) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        Map<String, String> labels = new LinkedHashMap<>();
        jdbc.query("""
                select cc.cpv_code, coalesce(c.labels->>?, c.labels->>'en') from company_cpv cc
                left join cpv_codes c on c.code = cc.cpv_code where cc.company_profile_id = ? order by cc.cpv_code
                """, r -> {
            labels.put(r.getString(1), r.getString(2));
        }, lang, id);
        List<CertificationView> certs = jdbc.query("""
                select id, name, issuer, valid_until from company_certifications where company_profile_id = ?
                order by name
                """, (r, i) -> new CertificationView(r.getObject(1, UUID.class), r.getString(2), r.getString(3),
                r.getObject(4, java.time.LocalDate.class)), id);
        List<ReferenceView> refs = jdbc.query("""
                select id, title, client, country_code, value, currency, year_completed, cpv_code, description
                from company_references where company_profile_id = ? order by year_completed desc nulls last, title
                """, (r, i) -> new ReferenceView(r.getObject(1, UUID.class), r.getString(2), r.getString(3),
                r.getString(4), r.getBigDecimal(5), r.getString(6), (Integer) r.getObject(7), r.getString(8),
                r.getString(9)), id);
        return new CompanyProfileView(id, rs.getString("name"), rs.getString("registration_country"),
                rs.getString("industry"), rs.getString("description"), rs.getString("website"),
                list(rs.getArray("countries_of_interest")), list(rs.getArray("contract_types")),
                rs.getString("company_size"), (Integer) rs.getObject("employees"), rs.getBigDecimal("annual_revenue"),
                rs.getString("revenue_currency"), (Integer) rs.getObject("years_of_experience"),
                rs.getBigDecimal("min_contract_value_eur"), rs.getBigDecimal("max_contract_value_eur"),
                rs.getString("technical_resources"), rs.getString("staff_resources"),
                rs.getString("financial_capacity"), List.copyOf(labels.keySet()), labels, certs, refs,
                rs.getTimestamp("updated_at").toInstant());
    }

    static List<String> list(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        return Arrays.stream((Object[]) array.getArray()).map(o -> o.toString().strip()).toList();
    }

    /** PostgreSQL array literal; values were validated against strict patterns (no quoting needed). */
    static String pgArray(List<String> values) {
        return "{" + String.join(",", values.stream().distinct().toList()) + "}";
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
