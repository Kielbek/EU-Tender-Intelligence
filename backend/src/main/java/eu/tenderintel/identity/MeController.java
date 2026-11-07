package eu.tenderintel.identity;

import eu.tenderintel.audit.AuditAction;
import eu.tenderintel.audit.AuditService;
import eu.tenderintel.common.error.ApiException;
import eu.tenderintel.identity.AuthDtos.ChangePasswordRequest;
import eu.tenderintel.identity.AuthDtos.MeView;
import eu.tenderintel.identity.AuthDtos.UpdateProfileRequest;
import eu.tenderintel.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "Me", description = "Profil zalogowanego użytkownika")
@SecurityRequirement(name = "bearer")
class MeController {

    private final UserRepository users;
    private final AuthService auth;
    private final AuditService audit;
    private final Clock clock;

    MeController(UserRepository users, AuthService auth, AuditService audit, Clock clock) {
        this.users = users;
        this.auth = auth;
        this.audit = audit;
        this.clock = clock;
    }

    @GetMapping
    @Transactional(readOnly = true)
    MeView me() {
        return MeView.of(load());
    }

    @PatchMapping
    @Transactional
    @Operation(summary = "Aktualizacja imienia i nazwiska, języka (kod języka UE) i strefy czasowej")
    MeView update(@Valid @RequestBody UpdateProfileRequest req) {
        if (req.locale() != null && !SupportedLocales.EU_LANGUAGES.contains(req.locale())) {
            throw ApiException.badRequest("unsupported_locale", "Locale must be an official EU language code");
        }
        if (req.timezone() != null) {
            try {
                ZoneId.of(req.timezone());
            } catch (DateTimeException e) {
                throw ApiException.badRequest("invalid_timezone", "Unknown time zone");
            }
        }
        User user = load();
        user.updateProfile(req.fullName() == null ? null : req.fullName().trim(), req.locale(), req.timezone(),
                Instant.now(clock));
        audit.record(AuditAction.PROFILE_UPDATED, user.getId(), null, "user", user.getId(), Map.of());
        return MeView.of(user);
    }

    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Zmiana hasła; wylogowuje wszystkie sesje (refresh tokeny)")
    void changePassword(@Valid @RequestBody ChangePasswordRequest req) {
        auth.changePassword(CurrentUser.id(), req.currentPassword(), req.newPassword());
    }

    @PostMapping("/sessions/revoke-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Wylogowanie ze wszystkich urządzeń")
    void revokeAllSessions() {
        auth.revokeAllSessions(CurrentUser.id());
    }

    private User load() {
        return users.findById(CurrentUser.id()).orElseThrow(() -> ApiException.notFound("User"));
    }
}
