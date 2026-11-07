package eu.tenderintel.organization;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface OrganizationRepository extends JpaRepository<Organization, UUID> {

    boolean existsBySlug(String slug);
}

interface OrganizationMemberRepository extends JpaRepository<OrganizationMember, OrganizationMember.Key> {

    Optional<OrganizationMember> findByOrganizationIdAndUserId(UUID organizationId, UUID userId);

    List<OrganizationMember> findByUserIdOrderByJoinedAt(UUID userId);

    List<OrganizationMember> findByOrganizationIdOrderByJoinedAt(UUID organizationId);

    @Query("select count(m) from OrganizationMember m where m.organizationId = :orgId and m.role = 'COMPANY_ADMIN'")
    long countAdmins(UUID orgId);
}

interface OrganizationInvitationRepository extends JpaRepository<OrganizationInvitation, UUID> {

    Optional<OrganizationInvitation> findByTokenHash(String tokenHash);

    @Query("""
            select i from OrganizationInvitation i
            where i.organizationId = :orgId and i.acceptedAt is null and i.revokedAt is null
            order by i.createdAt desc
            """)
    List<OrganizationInvitation> findOpen(UUID orgId);

    @Query("""
            select i from OrganizationInvitation i
            where i.organizationId = :orgId and i.email = :email and i.acceptedAt is null and i.revokedAt is null
            """)
    Optional<OrganizationInvitation> findOpenByEmail(UUID orgId, String email);
}
