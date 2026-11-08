package eu.tenderintel.workspace;

import eu.tenderintel.tender.TenderDtos.TenderSummary;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class WorkspaceDtos {

    private WorkspaceDtos() {
    }

    public static final String STATUSES = "WATCHING|PREPARING|SUBMITTED|WON|LOST|DROPPED";

    /** Watch-list entry shared by the whole organization (team pipeline). */
    public record SavedTenderView(UUID tenderId, String status, String notes, String createdByName, Instant createdAt,
            Instant updatedAt, TenderSummary tender) {
    }

    public record SaveTenderRequest(@Pattern(regexp = STATUSES) String status, @Size(max = 5000) String notes) {
    }

    public record SavedSearchView(UUID id, String name, Map<String, Object> filters, Instant createdAt,
            Instant updatedAt, Instant lastRunAt) {
    }

    /** {@code filters}: the same parameters as GET /api/v1/tenders (without page/size). */
    public record SavedSearchRequest(@NotBlank @Size(max = 120) String name, @NotNull Map<String, Object> filters) {
    }

    public record RenameRequest(@NotBlank @Size(max = 120) String name) {
    }
}
