package eu.tenderintel.organization;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "organization_invitations")
class OrganizationInvitation {

    @Id
    private UUID id;
    private UUID organizationId;
    private String email;
    @Enumerated(EnumType.STRING)
    private OrganizationRole role;
    private String tokenHash;
    private UUID invitedBy;
    private Instant expiresAt;
    private Instant acceptedAt;
    private Instant revokedAt;
    private Instant createdAt;

    protected OrganizationInvitation() {
    }

    OrganizationInvitation(UUID id, UUID organizationId, String email, OrganizationRole role, String tokenHash,
            UUID invitedBy, Instant expiresAt, Instant now) {
        this.id = id;
        this.organizationId = organizationId;
        this.email = email;
        this.role = role;
        this.tokenHash = tokenHash;
        this.invitedBy = invitedBy;
        this.expiresAt = expiresAt;
        this.createdAt = now;
    }

    boolean isOpen(Instant now) {
        return acceptedAt == null && revokedAt == null && expiresAt.isAfter(now);
    }

    void accept(Instant now) {
        acceptedAt = now;
    }

    void revoke(Instant now) {
        revokedAt = now;
    }

    UUID getId() { return id; }
    UUID getOrganizationId() { return organizationId; }
    String getEmail() { return email; }
    OrganizationRole getRole() { return role; }
    Instant getExpiresAt() { return expiresAt; }
    Instant getCreatedAt() { return createdAt; }
}
