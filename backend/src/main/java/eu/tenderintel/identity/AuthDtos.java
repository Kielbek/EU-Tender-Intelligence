package eu.tenderintel.identity;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 128) String password,
            @NotBlank @Size(min = 2, max = 120) String fullName,
            @Size(min = 2, max = 200) String organizationName,
            @Pattern(regexp = "^[a-z]{2}$") String locale,
            @AssertTrue(message = "terms must be accepted") boolean acceptTerms) {
    }

    public record LoginRequest(@NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 128) String password) {
    }

    public record TokenRequest(@NotBlank @Size(max = 128) String token) {
    }

    public record EmailRequest(@NotBlank @Email @Size(max = 254) String email) {
    }

    public record ResetPasswordRequest(@NotBlank @Size(max = 128) String token,
            @NotBlank @Size(max = 128) String newPassword) {
    }

    public record ChangePasswordRequest(@NotBlank @Size(max = 128) String currentPassword,
            @NotBlank @Size(max = 128) String newPassword) {
    }

    public record UpdateProfileRequest(
            @Size(min = 2, max = 120) String fullName,
            @Pattern(regexp = "^[a-z]{2}$") String locale,
            @Size(max = 64) String timezone) {
    }

    public record MeView(UUID id, String email, String fullName, String locale, String timezone,
            String platformRole, boolean emailVerified, Instant createdAt) {

        public static MeView of(User u) {
            return new MeView(u.getId(), u.getEmail(), u.getFullName(), u.getLocale(), u.getTimezone(),
                    u.getPlatformRole().name(), u.isEmailVerified(), u.getCreatedAt());
        }
    }

    /** The refresh token is never in the body – only in the HttpOnly cookie. */
    public record AuthResponse(String accessToken, String tokenType, Instant expiresAt, MeView user) {
    }
}
