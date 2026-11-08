package eu.tenderintel.billing;

import java.time.Instant;
import java.util.UUID;

/**
 * Calls to the Stripe API (outgoing). Incoming state changes arrive only through signed webhooks. The production
 * implementation uses the official SDK ({@link StripeSdkGateway}); tests replace it.
 */
public interface StripeGateway {

    boolean configured();

    /** Creates the customer for an organization. Idempotent per organization (Stripe idempotency key). */
    String createCustomer(UUID orgId, String name, String email, String locale);

    /** Hosted Checkout page for a new subscription; returns its URL. */
    String createCheckoutSession(CheckoutRequest request);

    /** Hosted Customer Portal (change plan or card, cancel, invoices); returns its URL. */
    String createPortalSession(String customerId, String returnUrl, String locale);

    /** A configured price as defined in Stripe (amount in minor units). */
    PriceInfo price(String priceId);

    /** Current state of a subscription, fetched from Stripe (webhook payloads may arrive out of order). */
    SubscriptionInfo subscription(String subscriptionId);

    /** Current state of an invoice, fetched from Stripe. */
    InvoiceInfo invoice(String invoiceId);

    record SubscriptionInfo(String id, String customerId, String status, String priceId, Instant currentPeriodStart,
            Instant currentPeriodEnd, boolean cancelAtPeriodEnd, Instant cancelAt, Instant canceledAt,
            Instant trialEnd, String metadataOrganizationId) {
    }

    /** Amounts in minor units. */
    record InvoiceInfo(String id, String customerId, String subscriptionId, String number, String status,
            String currency, long amountDue, long amountPaid, Long tax, String hostedInvoiceUrl, String pdfUrl,
            Instant periodStart, Instant periodEnd, Instant paidAt) {
    }

    record CheckoutRequest(UUID orgId, UUID userId, String customerId, String priceId, Plan plan, String interval,
            String locale, String successUrl, String cancelUrl, boolean automaticTax) {
    }

    record PriceInfo(String id, long unitAmount, String currency, String interval, long intervalCount,
            String taxBehavior, boolean active) {
    }
}
