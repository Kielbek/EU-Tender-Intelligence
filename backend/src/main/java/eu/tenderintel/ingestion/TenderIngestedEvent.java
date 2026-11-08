package eu.tenderintel.ingestion;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published after a notice changed the canonical state of a tender. Consumers (matching, notifications – Stages 5-6)
 * listen with {@code @TransactionalEventListener} so they only see committed data.
 */
public record TenderIngestedEvent(UUID tenderId, boolean created, ChangeKind changeKind, String status,
        List<String> changedFields, Instant occurredAt) {
}
