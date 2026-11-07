package eu.tenderintel.organization;

import eu.tenderintel.common.error.ApiException;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Tenant isolation guard. Every organization-scoped operation goes through here; a non-member gets 404 (not 403) so
 * that organization ids cannot be probed.
 */
@Component
public class OrganizationAccess {

    private final OrganizationMemberRepository members;

    OrganizationAccess(OrganizationMemberRepository members) {
        this.members = members;
    }

    public OrganizationMember requireMember(UUID organizationId, UUID userId) {
        return members.findByOrganizationIdAndUserId(organizationId, userId)
                .orElseThrow(() -> ApiException.notFound("Organization"));
    }

    public OrganizationMember requireAdmin(UUID organizationId, UUID userId) {
        OrganizationMember member = requireMember(organizationId, userId);
        if (member.getRole() != OrganizationRole.COMPANY_ADMIN) {
            throw ApiException.forbidden();
        }
        return member;
    }
}
