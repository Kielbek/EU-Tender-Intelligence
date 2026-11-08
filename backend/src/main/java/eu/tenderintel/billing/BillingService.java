package eu.tenderintel.billing;

import eu.tenderintel.ai.AnalysisService;
import eu.tenderintel.audit.AuditAction;
import eu.tenderintel.audit.AuditService;
import eu.tenderintel.billing.BillingDtos.BillingView;
import eu.tenderintel.billing.BillingDtos.InvoiceView;
import eu.tenderintel.billing.BillingDtos.Limits;
import eu.tenderintel.billing.BillingDtos.Meter;
import eu.tenderintel.billing.BillingDtos.PlanView;
import eu.tenderintel.billing.BillingDtos.PlansView;
import eu.tenderintel.billing.BillingDtos.PriceView;
import eu.tenderintel.billing.BillingDtos.SubscriptionView;
import eu.tenderintel.billing.BillingDtos.UsageView;
import eu.tenderintel.common.error.ApiException;
import eu.tenderintel.config.AppProperties;
import eu.tenderintel.organization.OrganizationAccess;
import eu.tenderintel.organization.OrganizationRole;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Plans, the organization's subscription and the hosted Stripe pages. The plan itself changes only through verified
 * webhooks ({@link StripeWebhookService}) – a redirect back from Checkout does not grant anything.
 */
@Service
public class BillingService {

    /** Subscription statuses that keep the paid plan (past_due: Stripe is still retrying the payment). */
    static final List<String> ENTITLED_STATUSES = List.of("active", "trialing", "past_due");
    private static final long PRICE_CACHE_MILLIS = 60 * 60_000;
    private static final Logger log = LoggerFactory.getLogger(BillingService.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final StripeGateway stripe;
    private final OrganizationAccess access;
    private final Entitlements entitlements;
    private final AnalysisService analyses;
    private final AuditService audit;
    private final AppProperties props;
    private final Map<String, CachedPrice> priceCache = new ConcurrentHashMap<>();

    BillingService(JdbcTemplate jdbc, TransactionTemplate tx, StripeGateway stripe, OrganizationAccess access,
            Entitlements entitlements, AnalysisService analyses, AuditService audit, AppProperties props) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.stripe = stripe;
        this.access = access;
        this.entitlements = entitlements;
        this.analyses = analyses;
        this.audit = audit;
        this.props = props;
    }

    // --- plans --------------------------------------------------------------------------------------------------

    public PlansView plans() {
        boolean configured = stripe.configured();
        List<PlanView> out = new ArrayList<>();
        for (Plan plan : Plan.values()) {
            List<PriceView> prices = new ArrayList<>();
            if (configured && plan.purchasable()) {
                for (String interval : List.of("MONTH", "YEAR")) {
                    String id = props.stripe().prices().get(plan.name() + ":" + interval);
                    if (id != null) {
                        price(id).filter(p -> matches(id, interval, p)).ifPresent(p -> prices.add(new PriceView(
                                interval, p.unitAmount(), p.currency(), p.taxBehavior())));
                    }
                }
            }
            out.add(new PlanView(plan.name(), plan.purchasable() && !prices.isEmpty(), Limits.of(plan), prices));
        }
        return new PlansView(configured, out);
    }

    private Optional<StripeGateway.PriceInfo> price(String id) {
        CachedPrice cached = priceCache.get(id);
        if (cached != null && System.currentTimeMillis() - cached.at() < PRICE_CACHE_MILLIS) {
            return Optional.of(cached.price());
        }
        try {
            StripeGateway.PriceInfo p = stripe.price(id);
            if (!p.active()) {
                log.warn("Configured Stripe price {} is archived – not offered", id);
                return Optional.empty();
            }
            priceCache.put(id, new CachedPrice(p, System.currentTimeMillis()));
            return Optional.of(p);
        } catch (ApiException e) {
            return cached == null ? Optional.empty() : Optional.of(cached.price());
        }
    }

    /** A misconfigured price (e.g. a monthly price set as the yearly one) is not offered rather than mis-sold. */
    private boolean matches(String id, String interval, StripeGateway.PriceInfo p) {
        if (!interval.equalsIgnoreCase(p.interval()) || p.intervalCount() != 1) {
            log.warn("Stripe price {} is configured as {} but bills every {} {} – not offered", id, interval,
                    p.intervalCount(), p.interval());
            return false;
        }
        if (props.stripe().automaticTax() && "unspecified".equals(p.taxBehavior())) {
            log.warn("Stripe price {} has no tax behavior; Stripe Tax needs it set (exclusive = net price + VAT)", id);
        }
        return true;
    }

    /** Plan and interval of a configured Stripe price, empty for prices this app does not know. */
    Optional<String[]> planForPrice(String priceId) {
        return props.stripe().prices().entrySet().stream().filter(e -> e.getValue().equals(priceId))
                .map(e -> e.getKey().split(":")).findFirst();
    }

    // --- organization overview ----------------------------------------------------------------------------------

    public BillingView overview(UUID orgId, UUID userId) {
        boolean admin = access.requireMember(orgId, userId).getRole() == OrganizationRole.COMPANY_ADMIN;
        Map<String, Object> org = jdbc.queryForMap(
                "select plan_code, plan_source, stripe_customer_id from organizations where id = ?", orgId);
        Plan plan = Plan.of((String) org.get("plan_code"));
        UsageView usage = new UsageView(
                new Meter(entitlements.seatsUsed(orgId), plan.seats()),
                new Meter(entitlements.savedSearchesUsed(orgId, userId), plan.savedSearchesPerUser()),
                new Meter(analyses.quota(orgId, userId).used(), plan.aiAnalysesPerMonth()),
                new Meter(entitlements.exportsUsed(orgId), plan.exportsPerMonth()),
                plan.instantEmails());
        return new BillingView(stripe.configured(), plan.name(), (String) org.get("plan_source"), admin,
                org.get("stripe_customer_id") != null, currentSubscription(orgId).orElse(null), usage,
                admin ? invoices(orgId) : List.of());
    }

    Optional<SubscriptionView> currentSubscription(UUID orgId) {
        // The entitled one first; otherwise the most recent (e.g. canceled, incomplete) for context.
        return jdbc.query("""
                select plan_code, billing_interval, status, current_period_end, cancel_at_period_end, cancel_at,
                       trial_end
                from subscriptions where organization_id = ?
                order by (status = any (?)) desc, updated_at desc limit 1
                """, rs -> rs.next() ? Optional.of(new SubscriptionView(rs.getString(1), rs.getString(2),
                rs.getString(3), instant(rs, 4), rs.getBoolean(5), instant(rs, 6), instant(rs, 7)))
                : Optional.<SubscriptionView>empty(), orgId, ENTITLED_STATUSES.toArray(String[]::new));
    }

    private List<InvoiceView> invoices(UUID orgId) {
        return jdbc.query("""
                select invoice_number, status, currency, amount_due, amount_paid, tax_amount, period_start,
                       period_end, paid_at, hosted_invoice_url, invoice_pdf_url, created_at
                from payments where organization_id = ? and status <> 'draft'
                order by created_at desc limit 24
                """, (rs, i) -> new InvoiceView(rs.getString(1), rs.getString(2), rs.getString(3).trim(),
                rs.getLong(4), rs.getLong(5), (Long) rs.getObject(6), instant(rs, 7), instant(rs, 8), instant(rs, 9),
                rs.getString(10), rs.getString(11), instant(rs, 12)), orgId);
    }

    // --- hosted pages -------------------------------------------------------------------------------------------

    /** Starts Stripe Checkout for a new subscription (administrators only). */
    public String checkout(UUID orgId, UUID userId, String planCode, String interval, String locale) {
        access.requireAdmin(orgId, userId);
        requireConfigured();
        Plan plan = Plan.parse(planCode).filter(Plan::purchasable)
                .orElseThrow(() -> ApiException.badRequest("invalid_plan", "This plan cannot be bought online"));
        String priceId = props.stripe().prices().get(plan.name() + ":" + interval);
        if (priceId == null) {
            throw ApiException.badRequest("price_not_configured", "No price is configured for this plan and period");
        }
        if (currentSubscription(orgId).filter(s -> ENTITLED_STATUSES.contains(s.status())).isPresent()) {
            // Changing an existing subscription happens in the Customer Portal (proration handled by Stripe).
            throw ApiException.conflict("subscription_exists", "The organization already has a subscription");
        }
        if ("MANUAL".equals(jdbc.queryForObject("select plan_source from organizations where id = ?", String.class,
                orgId))) {
            throw ApiException.conflict("manual_plan", "The plan is managed by contract; contact support");
        }
        String customerId = ensureCustomer(orgId, userId, locale);
        String base = props.publicUrl();
        String url = stripe.createCheckoutSession(new StripeGateway.CheckoutRequest(orgId, userId, customerId, priceId,
                plan, interval, locale, base + "/billing?checkout=success", base + "/billing?checkout=cancelled",
                props.stripe().automaticTax()));
        audit.record(AuditAction.BILLING_CHECKOUT_STARTED, userId, orgId, "plan", plan.name(),
                Map.of("interval", interval));
        return url;
    }

    /** Customer Portal: change plan or card, cancel, download invoices (administrators only). */
    public String portal(UUID orgId, UUID userId, String locale) {
        access.requireAdmin(orgId, userId);
        requireConfigured();
        String customerId = jdbc.queryForObject("select stripe_customer_id from organizations where id = ?",
                String.class, orgId);
        if (customerId == null) {
            throw ApiException.conflict("no_billing_account", "The organization has no billing account yet");
        }
        String url = stripe.createPortalSession(customerId, props.publicUrl() + "/billing", locale);
        audit.record(AuditAction.BILLING_PORTAL_OPENED, userId, orgId, "organization", orgId, Map.of());
        return url;
    }

    /**
     * The Stripe customer of an organization, created once. The organization row is locked while checking, and
     * Stripe's idempotency key makes a retried creation return the same customer.
     */
    private String ensureCustomer(UUID orgId, UUID userId, String locale) {
        return tx.execute(status -> {
            Map<String, Object> org = jdbc.queryForMap("""
                    select o.name, o.stripe_customer_id, coalesce(o.billing_email::text, u.email::text) as email
                    from organizations o, users u where o.id = ? and u.id = ? for update of o
                    """, orgId, userId);
            String existing = (String) org.get("stripe_customer_id");
            if (existing != null) {
                return existing;
            }
            String id = stripe.createCustomer(orgId, (String) org.get("name"), (String) org.get("email"), locale);
            jdbc.update("update organizations set stripe_customer_id = ?, updated_at = now(), version = version + 1 "
                    + "where id = ?", id, orgId);
            return id;
        });
    }

    private void requireConfigured() {
        if (!stripe.configured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "billing_not_configured",
                    "Payments are not configured");
        }
    }

    static Instant instant(ResultSet rs, int col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    static boolean entitled(String status) {
        return ENTITLED_STATUSES.contains(status);
    }

    private record CachedPrice(StripeGateway.PriceInfo price, long at) {
    }
}
