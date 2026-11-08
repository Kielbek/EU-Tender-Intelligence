package eu.tenderintel.notification;

import eu.tenderintel.notification.NotificationDtos.NotificationPage;
import eu.tenderintel.notification.NotificationDtos.PreferencesUpdate;
import eu.tenderintel.notification.NotificationDtos.PreferencesView;
import eu.tenderintel.notification.NotificationDtos.UnreadView;
import eu.tenderintel.notification.NotificationDtos.UnsubscribeRequest;
import eu.tenderintel.notification.NotificationDtos.UnsubscribeView;
import eu.tenderintel.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@Tag(name = "Notifications", description = "Powiadomienia w aplikacji i e-mail, preferencje, wypisanie")
class NotificationController {

    private final NotificationService notifications;
    private final NotificationScanner scanner;

    NotificationController(NotificationService notifications, NotificationScanner scanner) {
        this.notifications = notifications;
        this.scanner = scanner;
    }

    @PostMapping("/api/v1/admin/notifications/scan")
    @SecurityRequirement(name = "bearer")
    @Operation(summary = "Uruchom skaner powiadomień teraz (PLATFORM_ADMIN); 409 gdy trwa na innej instancji")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void scan() {
        if (!scanner.run()) {
            throw eu.tenderintel.common.error.ApiException.conflict("scan_running", "A scan is already running");
        }
    }

    @GetMapping("/api/v1/organizations/{orgId}/notifications")
    @SecurityRequirement(name = "bearer")
    @Operation(summary = "Powiadomienia zalogowanej osoby w organizacji (najnowsze pierwsze)")
    NotificationPage list(@PathVariable UUID orgId, @RequestParam(defaultValue = "false") boolean unread,
            @RequestParam(defaultValue = "0") @Min(0) @Max(500) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return notifications.list(orgId, CurrentUser.id(), unread, page, size);
    }

    @GetMapping("/api/v1/organizations/{orgId}/notifications/unread-count")
    @SecurityRequirement(name = "bearer")
    UnreadView unread(@PathVariable UUID orgId) {
        return new UnreadView(notifications.unreadCount(orgId, CurrentUser.id()));
    }

    @PostMapping("/api/v1/organizations/{orgId}/notifications/{id}/read")
    @SecurityRequirement(name = "bearer")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void read(@PathVariable UUID orgId, @PathVariable UUID id) {
        notifications.markRead(orgId, CurrentUser.id(), id);
    }

    @PostMapping("/api/v1/organizations/{orgId}/notifications/read-all")
    @SecurityRequirement(name = "bearer")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void readAll(@PathVariable UUID orgId) {
        notifications.markAllRead(orgId, CurrentUser.id());
    }

    @GetMapping("/api/v1/organizations/{orgId}/notification-preferences")
    @SecurityRequirement(name = "bearer")
    PreferencesView preferences(@PathVariable UUID orgId) {
        return notifications.preferences(orgId, CurrentUser.id());
    }

    @PutMapping("/api/v1/organizations/{orgId}/notification-preferences")
    @SecurityRequirement(name = "bearer")
    @Operation(summary = "Zapis preferencji; INSTANT wymaga planu z natychmiastowymi e-mailami (402)")
    PreferencesView updatePreferences(@PathVariable UUID orgId, @Valid @RequestBody PreferencesUpdate req) {
        return notifications.updatePreferences(orgId, CurrentUser.id(), req.preferences());
    }

    /**
     * Link from the e-mail. Accepts the token as a query parameter (RFC 8058 one-click POST from mail clients) or in
     * a JSON body (the unsubscribe page of the app).
     */
    @PostMapping(value = "/api/v1/notifications/unsubscribe", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Wypisanie z e-maili powiadomień (podpisany token z wiadomości, bez logowania)")
    UnsubscribeView unsubscribe(@RequestBody UnsubscribeRequest body) {
        return new UnsubscribeView(notifications.unsubscribe(body.token()));
    }

    /** RFC 8058: mail clients POST {@code List-Unsubscribe=One-Click} as a form to the URL with the token. */
    @PostMapping("/api/v1/notifications/unsubscribe")
    @Operation(summary = "Wypisanie jednym kliknięciem z klienta poczty (RFC 8058)")
    UnsubscribeView oneClick(@RequestParam String token) {
        return new UnsubscribeView(notifications.unsubscribe(token));
    }
}
