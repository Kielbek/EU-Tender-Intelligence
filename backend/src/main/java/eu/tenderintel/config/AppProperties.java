package eu.tenderintel.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Application-level configuration. Every value is bound from environment variables (see {@code .env.example});
 * secrets are never given defaults in code.
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @NotBlank String environment,
        @NotBlank String defaultLocale,
        @NotBlank String publicUrl,
        @NotBlank String termsVersion,
        @Valid @NotNull Cors cors,
        @Valid @NotNull Security security,
        @Valid @NotNull Mail mail,
        @Valid @NotNull Reference reference,
        @Valid @NotNull Ted ted,
        @Valid @NotNull Integrations integrations) {

    public record Cors(@NotNull List<String> allowedOrigins) {
    }

    public record Security(
            /* Base64-encoded HMAC key, at least 256 bits. Required – the application refuses to start without it. */
            @NotBlank String jwtSecret,
            @DefaultValue("15m") Duration accessTokenTtl,
            @DefaultValue("30d") Duration refreshTokenTtl,
            @DefaultValue("true") boolean refreshCookieSecure,
            @DefaultValue("5") int maxFailedLogins,
            @DefaultValue("15m") Duration lockoutDuration,
            @DefaultValue("24h") Duration emailVerificationTtl,
            @DefaultValue("1h") Duration passwordResetTtl,
            @DefaultValue("7d") Duration invitationTtl,
            /* E-mails promoted to PLATFORM_ADMIN once they verify ownership of the address. */
            @DefaultValue List<String> platformAdminEmails) {
    }

    public record Mail(@NotBlank String from, @DefaultValue("PT10S") Duration dispatchInterval) {
    }

    public record Reference(
            @NotBlank String cpvSparqlUrl,
            @NotBlank String ecbDailyUrl,
            @NotBlank String ecbHistory90dUrl,
            @DefaultValue("false") boolean importOnStartup) {
    }

    public record Ted(
            @NotBlank String searchUrl,
            /* Fields x page size must not exceed 10 000 (TED API limit). */
            @DefaultValue("200") int pageSize,
            @DefaultValue("3") int initialBackfillDays,
            @DefaultValue("31") int maxBackfillDays,
            @DefaultValue("true") boolean scheduleEnabled,
            @DefaultValue("PT0.3S") Duration pauseBetweenPages) {
    }

    /**
     * Credentials/endpoints of external services. A blank value means "not configured"; the system status endpoint
     * reports such integrations as {@code PENDING_CONFIGURATION} instead of pretending they work.
     */
    public record Integrations(
            String llmProvider,
            String llmApiKey,
            String stripeSecretKey,
            String stripeWebhookSecret,
            String smtpHost,
            String s3Endpoint,
            String s3AccessKey,
            String s3SecretKey,
            String s3Bucket) {
    }
}
