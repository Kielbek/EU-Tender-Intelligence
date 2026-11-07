package eu.tenderintel.identity;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_tokens")
class UserToken {

    enum Type { EMAIL_VERIFY, PASSWORD_RESET }

    @Id
    private UUID id;
    private UUID userId;
    @Enumerated(EnumType.STRING)
    private Type type;
    private String tokenHash;
    private Instant expiresAt;
    private Instant usedAt;
    private Instant createdAt;

    protected UserToken() {
    }

    UserToken(UUID id, UUID userId, Type type, String tokenHash, Instant expiresAt, Instant now) {
        this.id = id;
        this.userId = userId;
        this.type = type;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.createdAt = now;
    }

    boolean isUsable(Instant now) {
        return usedAt == null && expiresAt.isAfter(now);
    }

    void markUsed(Instant now) {
        usedAt = now;
    }

    UUID getUserId() { return userId; }
    Type getType() { return type; }
}
