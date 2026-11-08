package eu.tenderintel.billing;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.List;

public final class BillingDtos {

    private BillingDtos() {
    }

    /** {@code limits}: null = unlimited. {@code prices}: as configured in Stripe (empty when billing is not set up). */
    public record PlanView(String code, boolean purchasable, Limits limits, List<PriceView> prices) {
    }

    public record Limits(Integer seats, int savedSearchesPerUser, int aiAnalysesPerMonth, Integer exportsPerMonth,
            boolean instantEmails, int marketHistoryMonths, int watchedCompetitors) {

        static Limits of(Plan p) {
            return new Limits(p.seats(), p.savedSearchesPerUser(), p.aiAnalysesPerMonth(), p.exportsPerMonth(),
                    p.instantEmails(), p.marketHistoryMonths(), p.watchedCompetitors());
        }
    }

    /** {@code unitAmount} in minor units (grosze/cents); {@code taxBehavior}: exclusive = net price + VAT. */
    public record PriceView(String interval, long unitAmount, String currency, String taxBehavior) {
    }

    public record PlansView(boolean configured, List<PlanView> plans) {
    }

    public record BillingView(boolean configured, String plan, String planSource, boolean canManage,
            boolean hasCustomer, SubscriptionView subscription, UsageView usage, List<InvoiceView> invoices) {
    }

    public record SubscriptionView(String plan, String interval, String status, Instant currentPeriodEnd,
            boolean cancelAtPeriodEnd, Instant cancelAt, Instant trialEnd) {
    }

    public record UsageView(Meter seats, Meter savedSearches, Meter aiAnalyses, Meter exports, boolean instantEmails) {
    }

    public record Meter(long used, Integer limit) {
    }

    public record InvoiceView(String number, String status, String currency, long amountDue, long amountPaid,
            Long tax, Instant periodStart, Instant periodEnd, Instant paidAt, String hostedInvoiceUrl, String pdfUrl,
            Instant createdAt) {
    }

    public record CheckoutRequest(@NotNull @Pattern(regexp = "STARTER|PRO|BUSINESS") String plan,
            @NotNull @Pattern(regexp = "MONTH|YEAR") String interval) {
    }

    public record RedirectView(String url) {
    }
}
