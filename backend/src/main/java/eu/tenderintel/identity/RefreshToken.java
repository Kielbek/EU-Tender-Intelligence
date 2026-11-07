package eu.tenderintel.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;

@Entity
@Table(name = "refresh_tokens")
class RefreshToken {

    @Id
    private UUID id;
    private UUID userId;
    private UUID familyId;
    private String tokenHash;
    private Instant expiresAt;
    private Instant revokedAt;
    private UUID replacedBy;
    private String userAgent;
    @Column(columnDefinition = "inet")
    @ColumnTransformer(write = "?::inet")
    private String ip;
    private Instant createdAt;

    protected RefreshToken() {
    }

    RefreshToken(UUID id, UUID userId, UUID familyId, String tokenHash, Instant expiresAt, String userAgent, String ip,
            Instant now) {
        this.id = id;
        this.userId = userId;
        this.familyId = familyId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.userAgent = userAgent;
        this.ip = ip;
        this.createdAt = now;
    }

    boolean isActive(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    void rotate(UUID replacement, Instant now) {
        revokedAt = now;
        replacedBy = replacement;
    }

    UUID getId() { return id; }
    UUID getUserId() { return userId; }
    UUID getFamilyId() { return familyId; }
    Instant getRevokedAt() { return revokedAt; }
    UUID getReplacedBy() { return replacedBy; }
}
