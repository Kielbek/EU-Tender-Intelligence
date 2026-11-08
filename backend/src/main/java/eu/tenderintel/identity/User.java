package eu.tenderintel.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

    public enum PlatformRole { USER, PLATFORM_ADMIN }

    public enum Status { ACTIVE, LOCKED, DELETED }

    @Id
    private UUID id;
    private String email;
    private String passwordHash;
    private String fullName;
    private String locale;
    private String timezone;
    @Enumerated(EnumType.STRING)
    private PlatformRole platformRole = PlatformRole.USER;
    @Enumerated(EnumType.STRING)
    private Status status = Status.ACTIVE;
    private Instant emailVerifiedAt;
    private int failedLoginCount;
    private Instant lockedUntil;
    private Instant lastLoginAt;
    private Instant termsAcceptedAt;
    private String termsVersion;
    private Instant setupCompletedAt;
    @Column(insertable = false, updatable = false)
    private Instant createdAt;
    private Instant updatedAt;
    @Version
    private int version;

    protected User() {
    }

    public User(UUID id, String email, String passwordHash, String fullName, String locale, String timezone,
            Instant termsAcceptedAt, String termsVersion) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.locale = locale;
        this.timezone = timezone;
        this.termsAcceptedAt = termsAcceptedAt;
        this.termsVersion = termsVersion;
        this.updatedAt = termsAcceptedAt;
    }

    public boolean isEmailVerified() {
        return emailVerifiedAt != null;
    }

    public boolean isTemporarilyLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    void markEmailVerified(Instant now) {
        if (emailVerifiedAt == null) {
            emailVerifiedAt = now;
            touch(now);
        }
    }

    void recordFailedLogin(Instant now, int maxFailures, java.time.Duration lockout) {
        failedLoginCount++;
        if (failedLoginCount >= maxFailures) {
            lockedUntil = now.plus(lockout);
            failedLoginCount = 0;
        }
        touch(now);
    }

    void recordSuccessfulLogin(Instant now) {
        failedLoginCount = 0;
        lockedUntil = null;
        lastLoginAt = now;
        touch(now);
    }

    void changePassword(String newHash, Instant now) {
        passwordHash = newHash;
        failedLoginCount = 0;
        lockedUntil = null;
        touch(now);
    }

    void updateProfile(String fullName, String locale, String timezone, Instant now) {
        if (fullName != null) {
            this.fullName = fullName;
        }
        if (locale != null) {
            this.locale = locale;
        }
        if (timezone != null) {
            this.timezone = timezone;
        }
        touch(now);
    }

    void promoteToPlatformAdmin(Instant now) {
        platformRole = PlatformRole.PLATFORM_ADMIN;
        touch(now);
    }

    public void setStatus(Status status, Instant now) {
        this.status = status;
        touch(now);
    }

    private void touch(Instant now) {
        updatedAt = now;
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getFullName() { return fullName; }
    public String getLocale() { return locale; }
    public String getTimezone() { return timezone; }
    public PlatformRole getPlatformRole() { return platformRole; }
    public Status getStatus() { return status; }
    public Instant getEmailVerifiedAt() { return emailVerifiedAt; }
    public Instant getLockedUntil() { return lockedUntil; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getSetupCompletedAt() { return setupCompletedAt; }

    /** The first-run setup wizard was finished or skipped; it is not shown again. */
    public void completeSetup(Instant now) {
        if (setupCompletedAt == null) {
            setupCompletedAt = now;
            updatedAt = now;
        }
    }
}
