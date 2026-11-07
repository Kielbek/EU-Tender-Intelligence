package eu.tenderintel.organization;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "organization_members")
@IdClass(OrganizationMember.Key.class)
public class OrganizationMember {

    public record Key(UUID organizationId, UUID userId) implements Serializable {
        public Key() {
            this(null, null);
        }
    }

    @Id
    private UUID organizationId;
    @Id
    private UUID userId;
    @Enumerated(EnumType.STRING)
    private OrganizationRole role;
    private UUID invitedBy;
    private Instant joinedAt;

    protected OrganizationMember() {
    }

    OrganizationMember(UUID organizationId, UUID userId, OrganizationRole role, UUID invitedBy, Instant now) {
        this.organizationId = organizationId;
        this.userId = userId;
        this.role = role;
        this.invitedBy = invitedBy;
        this.joinedAt = now;
    }

    void changeRole(OrganizationRole role) {
        this.role = role;
    }

    public UUID getOrganizationId() { return organizationId; }
    public UUID getUserId() { return userId; }
    public OrganizationRole getRole() { return role; }
    public Instant getJoinedAt() { return joinedAt; }
}
