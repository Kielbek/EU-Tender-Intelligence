package eu.tenderintel.security;

import eu.tenderintel.common.error.ApiException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Access to the authenticated user's id (the JWT subject). */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static UUID id() {
        return optionalId().orElseThrow(
                () -> new ApiException(HttpStatus.UNAUTHORIZED, "unauthenticated", "Authentication required"));
    }

    public static Optional<UUID> optionalId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            return Optional.of(UUID.fromString(jwt.getToken().getSubject()));
        }
        return Optional.empty();
    }
}
