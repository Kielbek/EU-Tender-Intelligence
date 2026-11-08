package eu.tenderintel.billing;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Primary;

/**
 * Stands in for the Stripe API in integration tests: hosted pages return fake URLs, and the state that webhooks
 * re-read (subscriptions, invoices) is whatever the test put here. The real SDK gateway is tested against
 * stripe-mock in {@code StripeSdkGatewayTest}.
 */
@TestComponent
@Primary
public class FakeStripeGateway implements StripeGateway {

    public final Map<String, SubscriptionInfo> subscriptions = new ConcurrentHashMap<>();
    public final Map<String, InvoiceInfo> invoices = new ConcurrentHashMap<>();
    public final List<String> customersCreated = new ArrayList<>();
    public final List<CheckoutRequest> checkouts = new ArrayList<>();

    @Override
    public boolean configured() {
        return true;
    }

    @Override
    public String createCustomer(UUID orgId, String name, String email, String locale) {
        String id = "cus_test_" + orgId.toString().replace("-", "").substring(20);
        customersCreated.add(id);
        return id;
    }

    @Override
    public String createCheckoutSession(CheckoutRequest request) {
        checkouts.add(request);
        return "https://checkout.stripe.test/c/pay/" + request.orgId();
    }

    @Override
    public String createPortalSession(String customerId, String returnUrl, String locale) {
        return "https://billing.stripe.test/p/session/" + customerId;
    }

    @Override
    public PriceInfo price(String priceId) {
        boolean yearly = priceId.endsWith("_y");
        long amount = priceId.startsWith("price_starter") ? (yearly ? 249000 : 24900)
                : priceId.startsWith("price_pro") ? (yearly ? 549000 : 54900) : (yearly ? 1249000 : 124900);
        return new PriceInfo(priceId, amount, "pln", yearly ? "year" : "month", 1, "exclusive", true);
    }

    @Override
    public SubscriptionInfo subscription(String subscriptionId) {
        SubscriptionInfo s = subscriptions.get(subscriptionId);
        if (s == null) {
            throw new IllegalStateException("No fake subscription " + subscriptionId);
        }
        return s;
    }

    @Override
    public InvoiceInfo invoice(String invoiceId) {
        InvoiceInfo i = invoices.get(invoiceId);
        if (i == null) {
            throw new IllegalStateException("No fake invoice " + invoiceId);
        }
        return i;
    }
}
