package eu.tenderintel.notification;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class NotificationDtos {

    private NotificationDtos() {
    }

    /** {@code tender}/{@code savedSearch} – what the notification is about (null when not applicable). */
    public record NotificationView(UUID id, String type, Instant createdAt, Instant readAt, TenderRef tender,
            SavedSearchRef savedSearch, CompanyRef company, Map<String, Object> payload) {
    }

    public record CompanyRef(UUID id, String name) {
    }

    public record TenderRef(UUID id, String title, String countryCode, Instant submissionDeadline, String status) {
    }

    public record SavedSearchRef(UUID id, String name, Map<String, Object> filters) {
    }

    public record NotificationPage(List<NotificationView> items, long totalItems, long unread, int page, int size) {
    }

    public record UnreadView(long unread) {
    }

    public record PreferenceView(String type, boolean inApp, String emailFrequency, Integer minScore) {
    }

    /** {@code instantAvailable}: the plan includes instant e-mails (otherwise they go out in the daily digest). */
    public record PreferencesView(List<PreferenceView> preferences, boolean instantAvailable, boolean emailVerified) {
    }

    public record PreferenceUpdate(
            @NotNull @Pattern(regexp = "NEW_MATCH|WATCHED_CHANGED|DEADLINE_APPROACHING|COMPETITOR_WON") String type,
            boolean inApp,
            @NotNull @Pattern(regexp = "INSTANT|DAILY|WEEKLY|OFF") String emailFrequency,
            @Min(40) @Max(100) Integer minScore) {
    }

    public record PreferencesUpdate(@NotNull @Valid List<PreferenceUpdate> preferences) {
    }

    public record UnsubscribeRequest(String token) {
    }

    public record UnsubscribeView(String organizationName) {
    }
}
