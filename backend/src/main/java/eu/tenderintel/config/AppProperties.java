package eu.tenderintel.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
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
        @Valid @NotNull Cors cors,
        @Valid @NotNull Integrations integrations) {

    public record Cors(@NotNull List<String> allowedOrigins) {
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
