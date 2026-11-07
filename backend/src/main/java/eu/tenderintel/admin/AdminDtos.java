package eu.tenderintel.admin;

import java.time.Instant;
import java.util.UUID;

public final class AdminDtos {

    private AdminDtos() {
    }

    public record AdminUserView(UUID id, String email, String fullName, String platformRole, String status,
            boolean emailVerified, Instant lockedUntil, Instant lastLoginAt, Instant createdAt) {
    }

    public record AuditLogView(UUID id, Instant occurredAt, UUID actorUserId, UUID organizationId, String action,
            String targetType, String targetId, String ip, String correlationId, Object details) {
    }

    public record DataSourceView(UUID id, String code, String name, String countryCode, String adapterType,
            String status, String homepageUrl, String termsUrl, Instant termsVerifiedAt, String scheduleCron,
            String notes, Instant lastSuccessAt, Instant lastErrorAt, String lastError) {
    }

    public record IngestionJobView(UUID id, String dataSource, String type, String status, int fetchedCount,
            int createdCount, int updatedCount, int skippedCount, int errorCount, Object errors, String triggeredBy,
            Instant startedAt, Instant finishedAt, Instant createdAt) {
    }

    public record JobStarted(UUID jobId) {
    }

    public record EmailOutboxStats(long pending, long sent, long failed, boolean smtpConfigured) {
    }
}
