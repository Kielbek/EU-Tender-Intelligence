package eu.tenderintel.billing;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.net.Webhook;
import eu.tenderintel.audit.AuditAction;
import eu.tenderintel.audit.AuditService;
import eu.tenderintel.common.error.ApiException;
import eu.tenderintel.common.id.Ids;
import eu.tenderintel.config.AppProperties;
import eu.tenderintel.mail.EmailOutbox;
import eu.tenderintel.mail.EmailTemplate;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.text.NumberFormat;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Applies Stripe webhooks. Every request is verified with the endpoint's signing secret (and a timestamp tolerance
 * against replays), each event id is processed once, and the subscription or invoice is re-read from Stripe instead
 * of trusting the payload – events can arrive late or out of order. Processing and the "seen" mark share one
 * transaction: if anything fails, nothing is recorded and Stripe retries the delivery.
 */
@Service
public class StripeWebhookService {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookService.class);
    /** Higher plan wins if an organization ever has two entitled subscriptions. */
    private static final List<String> PLAN_RANK = List.of("BUSINESS", "PRO", "STARTER");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final StripeGateway stripe;
    private final BillingService billing;
    private final EmailOutbox outbox;
    private final AuditService audit;
    private final JsonMapper json;
    private final AppProperties props;

    StripeWebhookService(JdbcTemplate jdbc, TransactionTemplate tx, StripeGateway stripe, BillingService billing,
            EmailOutbox outbox, AuditService audit, JsonMapper json, AppProperties props) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.stripe = stripe;
        this.billing = billing;
        this.outbox = outbox;
        this.audit = audit;
        this.json = json;
        this.props = props;
    }

    /** Returns what happened (for logs and tests): processed, duplicate or ignored. */
    public String handle(String payload, String signatureHeader) {
        String secret = props.stripe().webhookSecret();
        if (secret == null || secret.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "billing_not_configured",
                    "Stripe webhooks are not configured");
        }
        try {
            Webhook.Signature.verifyHeader(payload, signatureHeader == null ? "" : signatureHeader, secret,
                    props.stripe().webhookTolerance().toSeconds());
        } catch (SignatureVerificationException e) {
            log.warn("Rejected Stripe webhook: invalid signature");
            throw ApiException.badRequest("invalid_signature", "Invalid Stripe signature");
        }
        JsonNode event = json.readTree(payload);
        String eventId = event.path("id").asString("");
        String type = event.path("type").asString("");
        if (eventId.isEmpty() || type.isEmpty()) {
            throw ApiException.badRequest("invalid_event", "Not a Stripe event");
        }
        JsonNode object = event.path("data").path("object");
        String result = tx.execute(status -> {
            int fresh = jdbc.update("insert into stripe_events (event_id, type) values (?, ?) on conflict do nothing",
                    eventId, type);
            if (fresh == 0) {
                return "duplicate";
            }
            return switch (type) {
                case "checkout.session.completed" -> checkoutCompleted(object);
                case "customer.subscription.created", "customer.subscription.updated",
                        "customer.subscription.deleted", "customer.subscription.paused",
                        "customer.subscription.resumed" -> syncSubscription(text(object, "id"));
                case "invoice.finalized", "invoice.paid", "invoice.payment_succeeded", "invoice.payment_failed",
                        "invoice.voided", "invoice.marked_uncollectible" ->
                        syncInvoice(text(object, "id"), "invoice.payment_failed".equals(type));
                default -> "ignored";
            };
        });
        log.info("Stripe event {} ({}): {}", eventId, type, result);
        return result;
    }

    /** Links the Stripe customer to the organization (normally done already when Checkout was started). */
    private String checkoutCompleted(JsonNode session) {
        UUID orgId = uuid(text(session, "client_reference_id"));
        String customer = text(session, "customer");
        if (orgId == null || customer == null) {
            return "ignored";
        }
        int linked = jdbc.update("""
                update organizations set stripe_customer_id = ?, updated_at = now(), version = version + 1
                where id = ? and stripe_customer_id is null
                """, customer, orgId);
        String subscription = text(session, "subscription");
        if (subscription != null) {
            syncSubscription(subscription);
        }
        return linked > 0 ? "processed (customer linked)" : "processed";
    }

    String syncSubscription(String subscriptionId) {
        if (subscriptionId == null) {
            return "ignored";
        }
        StripeGateway.SubscriptionInfo s = stripe.subscription(subscriptionId);
        Optional<UUID> org = organizationFor(s.customerId(), s.metadataOrganizationId());
        if (org.isEmpty()) {
            log.warn("Stripe subscription {} belongs to no known organization (customer {})", s.id(), s.customerId());
            return "ignored (unknown customer)";
        }
        UUID orgId = org.get();
        lockOrganization(orgId);
        Optional<String[]> plan = s.priceId() == null ? Optional.empty() : billing.planForPrice(s.priceId());
        if (plan.isEmpty()) {
            log.warn("Stripe subscription {} uses price {} that is not configured – no plan granted", s.id(),
                    s.priceId());
        }
        jdbc.update("""
                insert into subscriptions (id, organization_id, stripe_subscription_id, stripe_customer_id,
                    stripe_price_id, plan_code, billing_interval, status, current_period_start, current_period_end,
                    cancel_at_period_end, cancel_at, canceled_at, trial_end, stripe_updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                on conflict (stripe_subscription_id) do update set
                    stripe_price_id = excluded.stripe_price_id, plan_code = excluded.plan_code,
                    billing_interval = excluded.billing_interval, status = excluded.status,
                    current_period_start = excluded.current_period_start,
                    current_period_end = excluded.current_period_end,
                    cancel_at_period_end = excluded.cancel_at_period_end, cancel_at = excluded.cancel_at,
                    canceled_at = excluded.canceled_at, trial_end = excluded.trial_end,
                    stripe_updated_at = now(), updated_at = now()
                """, Ids.newId(), orgId, s.id(), s.customerId(), s.priceId(), plan.map(p -> p[0]).orElse(null),
                plan.map(p -> p[1]).orElse(null), s.status(), ts(s.currentPeriodStart()), ts(s.currentPeriodEnd()),
                s.cancelAtPeriodEnd(), ts(s.cancelAt()), ts(s.canceledAt()), ts(s.trialEnd()));
        recomputePlan(orgId);
        return "processed";
    }

    String syncInvoice(String invoiceId, boolean paymentFailed) {
        if (invoiceId == null) {
            return "ignored";
        }
        StripeGateway.InvoiceInfo i = stripe.invoice(invoiceId);
        Optional<UUID> org = organizationFor(i.customerId(), null);
        if (org.isEmpty()) {
            log.warn("Stripe invoice {} belongs to no known organization (customer {})", i.id(), i.customerId());
            return "ignored (unknown customer)";
        }
        UUID orgId = org.get();
        jdbc.update("""
                insert into payments (id, organization_id, stripe_invoice_id, stripe_subscription_id, invoice_number,
                    status, currency, amount_due, amount_paid, tax_amount, hosted_invoice_url, invoice_pdf_url,
                    period_start, period_end, paid_at, stripe_updated_at)
                values (?, ?, ?, ?, ?, ?, upper(?), ?, ?, ?, ?, ?, ?, ?, ?, now())
                on conflict (stripe_invoice_id) do update set
                    stripe_subscription_id = excluded.stripe_subscription_id, invoice_number = excluded.invoice_number,
                    status = excluded.status, amount_due = excluded.amount_due, amount_paid = excluded.amount_paid,
                    tax_amount = excluded.tax_amount, hosted_invoice_url = excluded.hosted_invoice_url,
                    invoice_pdf_url = excluded.invoice_pdf_url, period_start = excluded.period_start,
                    period_end = excluded.period_end, paid_at = excluded.paid_at, stripe_updated_at = now()
                """, Ids.newId(), orgId, i.id(), i.subscriptionId(), i.number(), i.status(), i.currency(),
                i.amountDue(), i.amountPaid(), i.tax(), i.hostedInvoiceUrl(), i.pdfUrl(), ts(i.periodStart()),
                ts(i.periodEnd()), ts(i.paidAt()));
        if (paymentFailed) {
            notifyPaymentFailed(orgId, i);
        }
        return "processed";
    }

    /** The organization's plan follows its best entitled subscription; manual (contract) plans are left alone. */
    void recomputePlan(UUID orgId) {
        Map<String, Object> org = jdbc.queryForMap("select plan_code, plan_source from organizations where id = ?",
                orgId);
        if ("MANUAL".equals(org.get("plan_source"))) {
            return;
        }
        List<String> plans = jdbc.queryForList("""
                select plan_code from subscriptions
                where organization_id = ? and plan_code is not null and status = any (?)
                """, String.class, orgId, BillingService.ENTITLED_STATUSES.toArray(String[]::new));
        String best = PLAN_RANK.stream().filter(plans::contains).findFirst().orElse("FREE");
        String source = "FREE".equals(best) ? "DEFAULT" : "STRIPE";
        String previous = (String) org.get("plan_code");
        if (!best.equals(previous) || !source.equals(org.get("plan_source"))) {
            jdbc.update("update organizations set plan_code = ?, plan_source = ?, updated_at = now(), "
                    + "version = version + 1 where id = ?", best, source, orgId);
            audit.record(AuditAction.PLAN_CHANGED, null, orgId, "organization", orgId,
                    Map.of("from", previous, "to", best, "source", source));
        }
    }

    private void notifyPaymentFailed(UUID orgId, StripeGateway.InvoiceInfo invoice) {
        String orgName = jdbc.queryForObject("select name from organizations where id = ?", String.class, orgId);
        List<Map<String, Object>> admins = jdbc.queryForList("""
                select u.email::text as email, u.locale from organization_members m join users u on u.id = m.user_id
                where m.organization_id = ? and m.role = 'COMPANY_ADMIN' and u.status = 'ACTIVE'
                """, orgId);
        for (Map<String, Object> admin : admins) {
            String locale = (String) admin.get("locale");
            outbox.enqueue((String) admin.get("email"), EmailTemplate.BILLING_PAYMENT_FAILED, locale, orgName,
                    money(invoice.amountDue(), invoice.currency(), locale), props.publicUrl() + "/billing");
        }
        audit.record(AuditAction.PAYMENT_FAILED, null, orgId, "invoice", invoice.id(),
                Map.of("amountDue", invoice.amountDue(), "currency", String.valueOf(invoice.currency())));
    }

    static String money(long minor, String currency, String locale) {
        NumberFormat f = NumberFormat.getCurrencyInstance(Locale.forLanguageTag(locale));
        try {
            Currency c = Currency.getInstance(currency.toUpperCase(Locale.ROOT));
            f.setCurrency(c);
            return f.format(BigDecimal.valueOf(minor, c.getDefaultFractionDigits()));
        } catch (RuntimeException e) {
            return BigDecimal.valueOf(minor, 2) + " " + currency;
        }
    }

    /** By the customer id stored when Checkout was started; the subscription metadata must agree. */
    private Optional<UUID> organizationFor(String customerId, String metadataOrgId) {
        if (customerId == null) {
            return Optional.empty();
        }
        List<UUID> byCustomer = jdbc.queryForList("select id from organizations where stripe_customer_id = ?",
                UUID.class, customerId);
        if (!byCustomer.isEmpty()) {
            UUID orgId = byCustomer.get(0);
            if (metadataOrgId != null && !metadataOrgId.equals(orgId.toString())) {
                log.warn("Stripe customer {} is linked to {} but metadata names {}", customerId, orgId, metadataOrgId);
            }
            return Optional.of(orgId);
        }
        return Optional.empty();
    }

    private void lockOrganization(UUID orgId) {
        jdbc.queryForList("select id from organizations where id = ? for update", UUID.class, orgId);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isString()) {
            return v.asString();
        }
        // Expanded objects ({"id": ...}) instead of ids.
        return v.isObject() && v.path("id").isString() ? v.path("id").asString() : null;
    }

    private static UUID uuid(String s) {
        try {
            return s == null ? null : UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}
