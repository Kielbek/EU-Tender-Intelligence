package eu.tenderintel.organization;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public final class OrganizationDtos {

    private OrganizationDtos() {
    }

    public record MembershipView(UUID id, String name, String slug, String planCode, OrganizationRole role) {
    }

    public record OrganizationView(UUID id, String name, String slug, String countryCode, String vatId,
            String billingEmail, String planCode, String status, Instant createdAt) {

        static OrganizationView of(Organization o) {
            return new OrganizationView(o.getId(), o.getName(), o.getSlug(), o.getCountryCode(), o.getVatId(),
                    o.getBillingEmail(), o.getPlanCode(), o.getStatus(), o.getCreatedAt());
        }
    }

    /** Partial update: null = unchanged, empty string = cleared (for optional fields). */
    public record UpdateOrganizationRequest(
            @Size(min = 2, max = 200) String name,
            @Pattern(regexp = "^$|^[A-Z]{2}$", message = "must be an ISO 3166-1 alpha-2 code") String countryCode,
            @Size(max = 32) String vatId,
            @Email @Size(max = 254) String billingEmail) {
    }

    public record MemberView(UUID userId, String email, String fullName, OrganizationRole role, Instant joinedAt) {
    }

    public record ChangeRoleRequest(@NotNull OrganizationRole role) {
    }

    public record InviteRequest(@NotBlank @Email @Size(max = 254) String email, @NotNull OrganizationRole role) {
    }

    public record InvitationView(UUID id, String email, OrganizationRole role, Instant expiresAt, Instant createdAt) {

        static InvitationView of(OrganizationInvitation i) {
            return new InvitationView(i.getId(), i.getEmail(), i.getRole(), i.getExpiresAt(), i.getCreatedAt());
        }
    }

    public record AcceptInvitationRequest(@NotBlank @Size(max = 128) String token) {
    }
}
