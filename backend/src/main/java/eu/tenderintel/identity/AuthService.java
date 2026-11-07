package eu.tenderintel.identity;

import eu.tenderintel.audit.AuditAction;
import eu.tenderintel.audit.AuditService;
import eu.tenderintel.common.error.ApiException;
import eu.tenderintel.common.id.Ids;
import eu.tenderintel.common.id.Tokens;
import eu.tenderintel.common.web.ClientInfo;
import eu.tenderintel.config.AppProperties;
import eu.tenderintel.identity.AuthDtos.MeView;
import eu.tenderintel.identity.AuthDtos.RegisterRequest;
import eu.tenderintel.mail.EmailOutbox;
import eu.tenderintel.mail.EmailTemplate;
import eu.tenderintel.organization.OrganizationService;
import eu.tenderintel.ratelimit.RateLimiter;
import eu.tenderintel.security.AccessTokenService;
import eu.tenderintel.security.AccessTokenService.IssuedAccessToken;
import eu.tenderintel.security.PasswordHasher;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration, e-mail verification, login, refresh-token rotation and password reset.
 *
 * <p>Anti-enumeration: registration, verification resend and password reset always answer the same way whether or
 * not the e-mail exists; login returns the same error for unknown accounts and wrong passwords and spends the same
 * hashing time.
 */
@Service
public class AuthService {

    private final UserRepository users;
    private final UserTokenRepository userTokens;
    private final RefreshTokenRepository refreshTokens;
    private final OrganizationService organizations;
    private final PasswordHasher hasher;
    private final AccessTokenService accessTokens;
    private final EmailOutbox outbox;
    private final AuditService audit;
    private final RateLimiter rateLimiter;
    private final AppProperties props;
    private final Clock clock;

    AuthService(UserRepository users, UserTokenRepository userTokens, RefreshTokenRepository refreshTokens,
            OrganizationService organizations, PasswordHasher hasher, AccessTokenService accessTokens,
            EmailOutbox outbox, AuditService audit, RateLimiter rateLimiter, AppProperties props, Clock clock) {
        this.users = users;
        this.userTokens = userTokens;
        this.refreshTokens = refreshTokens;
        this.organizations = organizations;
        this.hasher = hasher;
        this.accessTokens = accessTokens;
        this.outbox = outbox;
        this.audit = audit;
        this.rateLimiter = rateLimiter;
        this.props = props;
        this.clock = clock;
    }

    public record LoginResult(IssuedAccessToken accessToken, String refreshToken, MeView user) {
    }

    @Transactional
    public void register(RegisterRequest req) {
        ClientInfo client = ClientInfo.current();
        rateLimiter.check("register-ip", String.valueOf(client.ip()), 10, Duration.ofHours(1));
        String email = normalizeEmail(req.email());
        PasswordPolicy.validate(req.password(), email);
        String locale = resolveLocale(req.locale());

        var existing = users.findByEmail(email);
        if (existing.isPresent()) {
            User u = existing.get();
            outbox.enqueue(u.getEmail(), EmailTemplate.ACCOUNT_ALREADY_EXISTS, u.getLocale(),
                    props.publicUrl() + "/auth/login", props.publicUrl() + "/auth/forgot-password");
            return;
        }
        Instant now = Instant.now(clock);
        User user = users.save(new User(Ids.newId(), email, hasher.hash(req.password()), req.fullName().trim(), locale,
                "Europe/Warsaw", now, props.termsVersion()));
        String orgName = req.organizationName() != null && !req.organizationName().isBlank()
                ? req.organizationName().trim() : user.getFullName();
        organizations.createForNewUser(user.getId(), orgName);
        sendVerification(user, now);
        audit.record(AuditAction.USER_REGISTERED, user.getId(), null, "user", user.getId(),
                Map.of("termsVersion", props.termsVersion()));
    }

    @Transactional
    public void verifyEmail(String token) {
        Instant now = Instant.now(clock);
        UserToken t = userTokens.findByTokenHashAndType(Tokens.sha256(token), UserToken.Type.EMAIL_VERIFY)
                .filter(x -> x.isUsable(now))
                .orElseThrow(() -> ApiException.badRequest("invalid_token", "Link is invalid or has expired"));
        t.markUsed(now);
        User user = users.findById(t.getUserId()).orElseThrow();
        user.markEmailVerified(now);
        audit.record(AuditAction.EMAIL_VERIFIED, user.getId());
        boolean bootstrapAdmin = props.security().platformAdminEmails().stream()
                .anyMatch(e -> e.trim().equalsIgnoreCase(user.getEmail()));
        if (bootstrapAdmin && user.getPlatformRole() != User.PlatformRole.PLATFORM_ADMIN) {
            user.promoteToPlatformAdmin(now);
            audit.record(AuditAction.PLATFORM_ADMIN_GRANTED, user.getId(), null, "user", user.getId(),
                    Map.of("source", "PLATFORM_ADMIN_EMAILS"));
        }
    }

    @Transactional
    public void resendVerification(String rawEmail) {
        String email = normalizeEmail(rawEmail);
        rateLimiter.check("verify-resend-ip", String.valueOf(ClientInfo.current().ip()), 10, Duration.ofHours(1));
        rateLimiter.check("verify-resend-email", email, 3, Duration.ofHours(1));
        users.findByEmail(email)
                .filter(u -> !u.isEmailVerified() && u.getStatus() == User.Status.ACTIVE)
                .ifPresent(u -> sendVerification(u, Instant.now(clock)));
    }

    @Transactional(noRollbackFor = ApiException.class)
    public LoginResult login(String rawEmail, String password) {
        ClientInfo client = ClientInfo.current();
        String email = normalizeEmail(rawEmail);
        rateLimiter.check("login-ip", String.valueOf(client.ip()), 30, Duration.ofMinutes(1));
        Instant now = Instant.now(clock);

        User user = users.findByEmail(email).orElse(null);
        if (user == null) {
            // Same budget as for existing accounts, so unknown e-mails are not distinguishable by lockout behaviour.
            rateLimiter.check("login-unknown-email", email, props.security().maxFailedLogins(),
                    props.security().lockoutDuration());
            hasher.burnTime(password);
            audit.record(AuditAction.LOGIN_FAILED, null, null, "user", null, Map.of("reason", "unknown_account"));
            throw invalidCredentials();
        }
        if (user.isTemporarilyLocked(now)) {
            hasher.burnTime(password);
            long retry = Math.max(1, Duration.between(now, user.getLockedUntil()).toSeconds());
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_attempts",
                    "Too many failed attempts. Try again later.", Map.of("retryAfterSeconds", retry));
        }
        if (!hasher.matches(password, user.getPasswordHash())) {
            user.recordFailedLogin(now, props.security().maxFailedLogins(), props.security().lockoutDuration());
            audit.record(AuditAction.LOGIN_FAILED, user.getId(), null, "user", user.getId(),
                    Map.of("reason", "bad_password"));
            if (user.isTemporarilyLocked(now)) {
                audit.record(AuditAction.ACCOUNT_TEMPORARILY_LOCKED, user.getId());
            }
            throw invalidCredentials();
        }
        if (user.getStatus() != User.Status.ACTIVE) {
            throw new ApiException(HttpStatus.FORBIDDEN, "account_disabled", "This account is disabled");
        }
        if (!user.isEmailVerified()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "email_not_verified", "Confirm your e-mail address first");
        }
        user.recordSuccessfulLogin(now);
        String refresh = newRefreshToken(user.getId(), Ids.newId(), client, now);
        audit.record(AuditAction.LOGIN_SUCCEEDED, user.getId());
        return new LoginResult(accessTokens.issue(user.getId(), user.getPlatformRole().name()), refresh,
                MeView.of(user));
    }

    /**
     * Rotates the refresh token. Presenting an already rotated token means it was stolen or replayed: the whole
     * token family (all sessions derived from that login) is revoked.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public LoginResult refresh(String presentedToken) {
        Instant now = Instant.now(clock);
        RefreshToken current = refreshTokens.findByTokenHash(Tokens.sha256(presentedToken))
                .orElseThrow(AuthService::invalidSession);
        if (current.getRevokedAt() != null && current.getReplacedBy() != null) {
            // A token that was already rotated is presented again: theft or replay.
            refreshTokens.revokeFamily(current.getFamilyId(), now);
            audit.record(AuditAction.REFRESH_TOKEN_REUSE_DETECTED, current.getUserId(), null, "refresh_family",
                    current.getFamilyId(), Map.of());
            throw invalidSession();
        }
        if (!current.isActive(now)) {
            throw invalidSession();
        }
        User user = users.findById(current.getUserId()).orElseThrow(AuthService::invalidSession);
        if (user.getStatus() != User.Status.ACTIVE || !user.isEmailVerified()) {
            refreshTokens.revokeFamily(current.getFamilyId(), now);
            throw invalidSession();
        }
        UUID nextId = Ids.newId();
        current.rotate(nextId, now);
        String next = newRefreshToken(user.getId(), current.getFamilyId(), ClientInfo.current(), now, nextId);
        return new LoginResult(accessTokens.issue(user.getId(), user.getPlatformRole().name()), next,
                MeView.of(user));
    }

    @Transactional
    public void logout(String presentedToken) {
        if (presentedToken == null) {
            return;
        }
        Instant now = Instant.now(clock);
        refreshTokens.findByTokenHash(Tokens.sha256(presentedToken)).ifPresent(t -> {
            refreshTokens.revokeFamily(t.getFamilyId(), now);
            audit.record(AuditAction.LOGOUT, t.getUserId());
        });
    }

    @Transactional
    public void requestPasswordReset(String rawEmail) {
        String email = normalizeEmail(rawEmail);
        rateLimiter.check("reset-ip", String.valueOf(ClientInfo.current().ip()), 10, Duration.ofHours(1));
        rateLimiter.check("reset-email", email, 3, Duration.ofHours(1));
        users.findByEmail(email).filter(u -> u.getStatus() == User.Status.ACTIVE).ifPresent(user -> {
            Instant now = Instant.now(clock);
            userTokens.invalidateAll(user.getId(), UserToken.Type.PASSWORD_RESET, now);
            String token = Tokens.newSecret();
            userTokens.save(new UserToken(Ids.newId(), user.getId(), UserToken.Type.PASSWORD_RESET,
                    Tokens.sha256(token), now.plus(props.security().passwordResetTtl()), now));
            outbox.enqueue(user.getEmail(), EmailTemplate.PASSWORD_RESET, user.getLocale(),
                    props.publicUrl() + "/auth/reset-password?token=" + token);
            audit.record(AuditAction.PASSWORD_RESET_REQUESTED, user.getId());
        });
    }

    @Transactional
    public void resetPassword(String token, String newPassword) {
        Instant now = Instant.now(clock);
        UserToken t = userTokens.findByTokenHashAndType(Tokens.sha256(token), UserToken.Type.PASSWORD_RESET)
                .filter(x -> x.isUsable(now))
                .orElseThrow(() -> ApiException.badRequest("invalid_token", "Link is invalid or has expired"));
        User user = users.findById(t.getUserId()).orElseThrow();
        PasswordPolicy.validate(newPassword, user.getEmail());
        t.markUsed(now);
        user.changePassword(hasher.hash(newPassword), now);
        // Clicking the link proves control of the mailbox.
        user.markEmailVerified(now);
        refreshTokens.revokeAllForUser(user.getId(), now);
        outbox.enqueue(user.getEmail(), EmailTemplate.PASSWORD_CHANGED, user.getLocale());
        audit.record(AuditAction.PASSWORD_RESET_COMPLETED, user.getId());
    }

    @Transactional
    public void changePassword(UUID userId, String currentPassword, String newPassword) {
        User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
        if (!hasher.matches(currentPassword, user.getPasswordHash())) {
            throw ApiException.badRequest("invalid_current_password", "Current password is incorrect");
        }
        PasswordPolicy.validate(newPassword, user.getEmail());
        Instant now = Instant.now(clock);
        user.changePassword(hasher.hash(newPassword), now);
        refreshTokens.revokeAllForUser(userId, now);
        outbox.enqueue(user.getEmail(), EmailTemplate.PASSWORD_CHANGED, user.getLocale());
        audit.record(AuditAction.PASSWORD_CHANGED, userId);
    }

    @Transactional
    public void revokeAllSessions(UUID userId) {
        refreshTokens.revokeAllForUser(userId, Instant.now(clock));
    }

    private void sendVerification(User user, Instant now) {
        userTokens.invalidateAll(user.getId(), UserToken.Type.EMAIL_VERIFY, now);
        String token = Tokens.newSecret();
        userTokens.save(new UserToken(Ids.newId(), user.getId(), UserToken.Type.EMAIL_VERIFY, Tokens.sha256(token),
                now.plus(props.security().emailVerificationTtl()), now));
        outbox.enqueue(user.getEmail(), EmailTemplate.VERIFY_EMAIL, user.getLocale(), user.getFullName(),
                props.publicUrl() + "/auth/verify-email?token=" + token);
    }

    private String newRefreshToken(UUID userId, UUID familyId, ClientInfo client, Instant now) {
        return newRefreshToken(userId, familyId, client, now, Ids.newId());
    }

    private String newRefreshToken(UUID userId, UUID familyId, ClientInfo client, Instant now, UUID id) {
        String token = Tokens.newSecret();
        refreshTokens.save(new RefreshToken(id, userId, familyId, Tokens.sha256(token),
                now.plus(props.security().refreshTokenTtl()), client.userAgent(), client.ip(), now));
        return token;
    }

    private String resolveLocale(String requested) {
        if (requested != null && SupportedLocales.EU_LANGUAGES.contains(requested)) {
            return requested;
        }
        return props.defaultLocale();
    }

    static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "invalid_credentials", "Invalid e-mail or password");
    }

    private static ApiException invalidSession() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "invalid_session", "Session expired. Sign in again.");
    }
}
