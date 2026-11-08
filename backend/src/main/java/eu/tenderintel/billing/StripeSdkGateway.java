package eu.tenderintel.billing;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Price;
import com.stripe.net.RequestOptions;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.checkout.SessionCreateParams;
import eu.tenderintel.common.error.ApiException;
import eu.tenderintel.config.AppProperties;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Stripe API through the official Java SDK. The secret key comes only from the environment; failures are logged with
 * Stripe's request id and reported to the user as {@code 502 billing_provider_error} without Stripe's message.
 */
@Component
class StripeSdkGateway implements StripeGateway {

    private static final Logger log = LoggerFactory.getLogger(StripeSdkGateway.class);

    private final AppProperties.Stripe props;
    private final StripeClient client;

    StripeSdkGateway(AppProperties props) {
        this.props = props.stripe();
        if (this.props.secretKey() == null || this.props.secretKey().isBlank()) {
            this.client = null;
        } else {
            StripeClient.StripeClientBuilder builder = StripeClient.builder().setApiKey(this.props.secretKey())
                    .setMaxNetworkRetries(2).setConnectTimeout(10_000).setReadTimeout(30_000);
            if (this.props.apiBase() != null && !this.props.apiBase().isBlank()) {
                builder.setApiBase(this.props.apiBase());
            }
            this.client = builder.build();
        }
    }

    @Override
    public boolean configured() {
        return client != null && props.configured();
    }

    private StripeClient client() {
        if (client == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "billing_not_configured",
                    "Payments are not configured");
        }
        return client;
    }

    @Override
    public String createCustomer(UUID orgId, String name, String email, String locale) {
        CustomerCreateParams.Builder params = CustomerCreateParams.builder()
                .setName(name)
                .putMetadata("organization_id", orgId.toString())
                .addPreferredLocale(locale);
        if (email != null && !email.isBlank()) {
            params.setEmail(email);
        }
        RequestOptions options = RequestOptions.builder().setIdempotencyKey("customer-" + orgId).build();
        return call("create customer", () -> client().customers().create(params.build(), options).getId());
    }

    @Override
    public String createCheckoutSession(CheckoutRequest r) {
        SessionCreateParams.Builder params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                .setCustomer(r.customerId())
                .setClientReferenceId(r.orgId().toString())
                .addLineItem(SessionCreateParams.LineItem.builder().setPrice(r.priceId()).setQuantity(1L).build())
                .setSubscriptionData(SessionCreateParams.SubscriptionData.builder()
                        .putMetadata("organization_id", r.orgId().toString())
                        .putMetadata("plan", r.plan().name())
                        .build())
                .putMetadata("organization_id", r.orgId().toString())
                .putMetadata("requested_by", r.userId().toString())
                .setBillingAddressCollection(SessionCreateParams.BillingAddressCollection.REQUIRED)
                .setTaxIdCollection(SessionCreateParams.TaxIdCollection.builder().setEnabled(true).build())
                // The address and company name entered in Checkout are saved on the customer (needed for tax).
                .setCustomerUpdate(SessionCreateParams.CustomerUpdate.builder()
                        .setAddress(SessionCreateParams.CustomerUpdate.Address.AUTO)
                        .setName(SessionCreateParams.CustomerUpdate.Name.AUTO)
                        .build())
                .setAllowPromotionCodes(true)
                .setLocale("pl".equals(r.locale()) ? SessionCreateParams.Locale.PL : SessionCreateParams.Locale.EN)
                .setSuccessUrl(r.successUrl())
                .setCancelUrl(r.cancelUrl());
        if (r.automaticTax()) {
            params.setAutomaticTax(SessionCreateParams.AutomaticTax.builder().setEnabled(true).build());
        }
        return call("create checkout session", () -> client().checkout().sessions().create(params.build()).getUrl());
    }

    @Override
    public String createPortalSession(String customerId, String returnUrl, String locale) {
        com.stripe.param.billingportal.SessionCreateParams params = com.stripe.param.billingportal.SessionCreateParams
                .builder()
                .setCustomer(customerId)
                .setReturnUrl(returnUrl)
                .setLocale("pl".equals(locale) ? com.stripe.param.billingportal.SessionCreateParams.Locale.PL
                        : com.stripe.param.billingportal.SessionCreateParams.Locale.EN)
                .build();
        return call("create portal session", () -> client().billingPortal().sessions().create(params).getUrl());
    }

    @Override
    public PriceInfo price(String priceId) {
        Price p = call("retrieve price", () -> client().prices().retrieve(priceId));
        return new PriceInfo(p.getId(), p.getUnitAmount() == null ? 0 : p.getUnitAmount(), p.getCurrency(),
                p.getRecurring() == null ? null : p.getRecurring().getInterval(),
                p.getRecurring() == null || p.getRecurring().getIntervalCount() == null ? 1
                        : p.getRecurring().getIntervalCount(),
                p.getTaxBehavior(), Boolean.TRUE.equals(p.getActive()));
    }

    @Override
    public SubscriptionInfo subscription(String subscriptionId) {
        com.stripe.model.Subscription s = call("retrieve subscription",
                () -> client().subscriptions().retrieve(subscriptionId));
        com.stripe.model.SubscriptionItem item = s.getItems() == null || s.getItems().getData() == null
                || s.getItems().getData().isEmpty() ? null : s.getItems().getData().get(0);
        return new SubscriptionInfo(s.getId(), s.getCustomer(), s.getStatus(),
                item == null || item.getPrice() == null ? null : item.getPrice().getId(),
                epoch(item == null ? null : item.getCurrentPeriodStart()),
                epoch(item == null ? null : item.getCurrentPeriodEnd()),
                Boolean.TRUE.equals(s.getCancelAtPeriodEnd()), epoch(s.getCancelAt()), epoch(s.getCanceledAt()),
                epoch(s.getTrialEnd()), s.getMetadata() == null ? null : s.getMetadata().get("organization_id"));
    }

    @Override
    public InvoiceInfo invoice(String invoiceId) {
        com.stripe.model.Invoice i = call("retrieve invoice", () -> client().invoices().retrieve(invoiceId));
        String subscription = i.getParent() == null || i.getParent().getSubscriptionDetails() == null ? null
                : i.getParent().getSubscriptionDetails().getSubscription();
        Long tax = i.getTotalTaxes() == null ? null
                : i.getTotalTaxes().stream().mapToLong(t -> t.getAmount() == null ? 0 : t.getAmount()).sum();
        return new InvoiceInfo(i.getId(), i.getCustomer(), subscription, i.getNumber(), i.getStatus(),
                i.getCurrency(), nz(i.getAmountDue()), nz(i.getAmountPaid()), tax, i.getHostedInvoiceUrl(),
                i.getInvoicePdf(), epoch(i.getPeriodStart()), epoch(i.getPeriodEnd()),
                epoch(i.getStatusTransitions() == null ? null : i.getStatusTransitions().getPaidAt()));
    }

    private static java.time.Instant epoch(Long seconds) {
        return seconds == null ? null : java.time.Instant.ofEpochSecond(seconds);
    }

    private static long nz(Long v) {
        return v == null ? 0 : v;
    }

    private <T> T call(String what, StripeCall<T> call) {
        try {
            return call.run();
        } catch (StripeException e) {
            // The message helps to fix configuration; anything that looks like a key is masked before logging.
            log.error("Stripe {} failed: {} (request {}, code {}): {}", what, e.getClass().getSimpleName(),
                    e.getRequestId(), e.getCode(), maskKeys(e.getMessage()));
            throw new ApiException(HttpStatus.BAD_GATEWAY, "billing_provider_error",
                    "The payment provider did not accept the request");
        }
    }

    static String maskKeys(String message) {
        return message == null ? null
                : message.replaceAll("\\b(sk|rk|pk|whsec)_(test_|live_)?[A-Za-z0-9]+", "$1_$2***");
    }

    @FunctionalInterface
    private interface StripeCall<T> {
        T run() throws StripeException;
    }
}
