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
        @Valid @NotNull Bzp bzp,
        @Valid @NotNull Ai ai,
        @Valid @NotNull Legal legal,
        @Valid @NotNull Stripe stripe,
        @Valid @NotNull Notifications notifications,
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
     * Operator details shown in the terms of service and privacy policy. Never invented: blank values are reported
     * as {@code configured = false} and the pages show a notice instead of fake company data.
     */
    public record Legal(String operatorName, String operatorAddress, String operatorTaxId, String operatorEmail,
            String hostingProvider, String emailProvider) {

        public boolean configured() {
            return notBlank(operatorName) && notBlank(operatorAddress) && notBlank(operatorEmail);
        }

        private static boolean notBlank(String s) {
            return s != null && !s.isBlank();
        }
    }

    /**
     * Polish Public Procurement Bulletin (BZP) via the e-Zamówienia API. Free, no access request needed (UZP API
     * regulations, §3 sec. 8). Pages are capped at 500 by the API; smaller pages keep memory low (notices are ~35 kB).
     */
    public record Bzp(
            @DefaultValue("https://ezamowienia.gov.pl/mo-board/api/v1/notice") @NotBlank String searchUrl,
            @DefaultValue("100") int pageSize,
            @DefaultValue("3") int initialBackfillDays,
            @DefaultValue("31") int maxBackfillDays,
            @DefaultValue("true") boolean scheduleEnabled,
            @DefaultValue("PT1.5S") Duration pauseBetweenPages) {
    }

    /**
     * Claude (Anthropic) for tender document analysis. A blank key disables AI features: endpoints answer
     * {@code 503 ai_not_configured} and the status page shows {@code PENDING_CONFIGURATION}.
     */
    public record Ai(
            String anthropicApiKey,
            @DefaultValue("claude-sonnet-5-5") @NotBlank String analysisModel,
            /* low | medium | high | xhigh | max */
            @DefaultValue("medium") @NotBlank String analysisEffort,
            @DefaultValue("32000") long analysisMaxOutputTokens,
            /* Uploaded file limit; the whole request to Claude is capped below 32 MB. */
            @DefaultValue("25") int maxUploadMb,
            /* Raw PDF bytes sent in one request (base64 adds a third; the API caps a request at 32 MB). */
            @DefaultValue("22") int maxPdfMbPerRequest,
            @DefaultValue("2") int workers,
            @DefaultValue("PT10M") Duration requestTimeout,
            /* Server-side fallback when a safety classifier declines (beta); can be switched off if the API rejects it. */
            @DefaultValue("true") boolean refusalFallback) {

        public boolean configured() {
            return anthropicApiKey != null && !anthropicApiKey.isBlank();
        }
    }

    /**
     * Credentials/endpoints of external services. A blank value means "not configured"; the system status endpoint
     * reports such integrations as {@code PENDING_CONFIGURATION} instead of pretending they work.
     */
    /**
     * Stripe Billing. Prices (amounts, currencies, tax behaviour) are created in the Stripe dashboard; only their ids
     * are configured here, and each id maps a Stripe price to a plan. Without the secret key, the webhook secret and
     * at least one price, billing is reported as {@code PENDING_CONFIGURATION} and checkout is unavailable.
     */
    public record Stripe(
            String secretKey,
            String webhookSecret,
            /* Only for tests against stripe-mock; blank = the real Stripe API. */
            String apiBase,
            /* Stripe Tax: VAT computed by Stripe from the customer's address and tax id (requires Stripe Tax). */
            @DefaultValue("true") boolean automaticTax,
            String priceStarterMonthly,
            String priceStarterYearly,
            String priceProMonthly,
            String priceProYearly,
            String priceBusinessMonthly,
            String priceBusinessYearly,
            /* Signed webhook events older than this are rejected (replay protection). */
            @DefaultValue("PT5M") Duration webhookTolerance) {

        public boolean configured() {
            return notBlank(secretKey) && notBlank(webhookSecret) && !prices().isEmpty();
        }

        /** Configured price ids: plan code + interval (MONTH/YEAR) → Stripe price id. */
        public java.util.Map<String, String> prices() {
            java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
            put(out, "STARTER:MONTH", priceStarterMonthly);
            put(out, "STARTER:YEAR", priceStarterYearly);
            put(out, "PRO:MONTH", priceProMonthly);
            put(out, "PRO:YEAR", priceProYearly);
            put(out, "BUSINESS:MONTH", priceBusinessMonthly);
            put(out, "BUSINESS:YEAR", priceBusinessYearly);
            return out;
        }

        private static void put(java.util.Map<String, String> map, String key, String value) {
            if (notBlank(value)) {
                map.put(key, value.trim());
            }
        }

        private static boolean notBlank(String s) {
            return s != null && !s.isBlank();
        }
    }

    /** Scanner that turns new matches, watch-list changes and saved-search hits into notifications. */
    public record Notifications(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("PT5M") Duration scanInterval,
            /* Local hour (user's time zone) of daily and weekly digests; weekly digests go out on Mondays. */
            @DefaultValue("7") int digestHour,
            /* Reminder for watched tenders this many days before the submission deadline. */
            @DefaultValue("3") int deadlineReminderDays,
            @DefaultValue("180") int retentionDays) {
    }

    public record Integrations(
            String smtpHost,
            String s3Endpoint,
            String s3AccessKey,
            String s3SecretKey,
            String s3Bucket) {
    }
}
