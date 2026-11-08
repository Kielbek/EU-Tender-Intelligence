package eu.tenderintel.document;

import java.time.Instant;
import java.util.UUID;

public final class DocumentDtos {

    private DocumentDtos() {
    }

    /**
     * A tender document. {@code scope} is PUBLIC for documents fetched from the notice's public link (visible to every
     * organization) and ORGANIZATION for files uploaded by the caller's organization (private).
     */
    public record DocumentView(UUID id, String fileName, String mimeType, Long sizeBytes, String source, String scope,
            String fetchStatus, String extractionStatus, Integer pageCount, Integer textChars, String error,
            Instant createdAt) {
    }

    /** Outcome of fetching the documents link published in the notice. */
    public record FetchResult(String status, String detail, java.util.List<DocumentView> documents) {
    }
}
