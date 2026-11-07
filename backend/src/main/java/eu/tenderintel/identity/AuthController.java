package eu.tenderintel.identity;

import eu.tenderintel.common.error.ApiException;
import eu.tenderintel.config.AppProperties;
import eu.tenderintel.config.SecurityConfig;
import eu.tenderintel.identity.AuthDtos.AuthResponse;
import eu.tenderintel.identity.AuthDtos.EmailRequest;
import eu.tenderintel.identity.AuthDtos.LoginRequest;
import eu.tenderintel.identity.AuthDtos.RegisterRequest;
import eu.tenderintel.identity.AuthDtos.ResetPasswordRequest;
import eu.tenderintel.identity.AuthDtos.TokenRequest;
import eu.tenderintel.identity.AuthService.LoginResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth", description = "Rejestracja, logowanie, odświeżanie sesji, weryfikacja e-mail, reset hasła")
class AuthController {

    static final String REFRESH_COOKIE = "eti_refresh";
    static final String REFRESH_COOKIE_PATH = "/api/v1/auth";

    private final AuthService auth;
    private final AppProperties props;

    AuthController(AuthService auth, AppProperties props) {
        this.auth = auth;
        this.props = props;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Rejestracja konta",
            description = "Zawsze 202 (także gdy e-mail istnieje – wtedy właściciel dostaje e-mail informacyjny). "
                    + "Konto trzeba potwierdzić linkiem z e-maila. Tworzy też organizację użytkownika.")
    void register(@Valid @RequestBody RegisterRequest req) {
        auth.register(req);
    }

    @PostMapping("/verify-email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void verifyEmail(@Valid @RequestBody TokenRequest req) {
        auth.verifyEmail(req.token());
    }

    @PostMapping("/verify-email/resend")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void resendVerification(@Valid @RequestBody EmailRequest req) {
        auth.resendVerification(req.email());
    }

    @PostMapping("/login")
    @Operation(summary = "Logowanie",
            description = "Zwraca access token (Bearer, 15 min); refresh token ustawiany jest w ciasteczku HttpOnly.")
    ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest req) {
        return withSession(auth.login(req.email(), req.password()));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Odświeżenie sesji (rotacja refresh tokenu)",
            description = "Wymaga ciasteczka eti_refresh oraz nagłówka X-ETI-CSRF: 1.")
    ResponseEntity<AuthResponse> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
            @RequestHeader(name = SecurityConfig.CSRF_HEADER, required = false) String csrf) {
        requireCsrfHeader(csrf);
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid_session", "Session expired. Sign in again.");
        }
        try {
            return withSession(auth.refresh(refreshToken));
        } catch (ApiException ex) {
            if (ex.status() == HttpStatus.UNAUTHORIZED) {
                // Clear the dead cookie together with the error.
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .header(HttpHeaders.SET_COOKIE, clearCookie().toString())
                        .build();
            }
            throw ex;
        }
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
            @RequestHeader(name = SecurityConfig.CSRF_HEADER, required = false) String csrf) {
        requireCsrfHeader(csrf);
        auth.logout(refreshToken);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, clearCookie().toString()).build();
    }

    @PostMapping("/password/forgot")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Wysłanie linku do resetu hasła (zawsze 202)")
    void forgotPassword(@Valid @RequestBody EmailRequest req) {
        auth.requestPasswordReset(req.email());
    }

    @PostMapping("/password/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Ustawienie nowego hasła; unieważnia wszystkie sesje")
    void resetPassword(@Valid @RequestBody ResetPasswordRequest req) {
        auth.resetPassword(req.token(), req.newPassword());
    }

    private ResponseEntity<AuthResponse> withSession(LoginResult result) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE, result.refreshToken())
                .httpOnly(true)
                .secure(props.security().refreshCookieSecure())
                .sameSite("Strict")
                .path(REFRESH_COOKIE_PATH)
                .maxAge(props.security().refreshTokenTtl())
                .build();
        AuthResponse body = new AuthResponse(result.accessToken().value(), "Bearer",
                result.accessToken().expiresAt(), result.user());
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }

    private ResponseCookie clearCookie() {
        return ResponseCookie.from(REFRESH_COOKIE, "").httpOnly(true).secure(props.security().refreshCookieSecure())
                .sameSite("Strict").path(REFRESH_COOKIE_PATH).maxAge(Duration.ZERO).build();
    }

    private static void requireCsrfHeader(String value) {
        if (!"1".equals(value)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "csrf_header_missing",
                    "Header " + SecurityConfig.CSRF_HEADER + ": 1 is required");
        }
    }
}
