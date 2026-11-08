package eu.tenderintel.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import eu.tenderintel.common.error.ApiException;
import eu.tenderintel.config.AppProperties;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The real SDK gateway against stripe-mock (Stripe's official mock server, which validates every request against
 * Stripe's OpenAPI specification). This proves the requests are well-formed for the pinned API version; it does not
 * replace a test against a Stripe account in test mode.
 */
@Testcontainers
class StripeSdkGatewayTest {

    @Container
    static final GenericContainer<?> STRIPE_MOCK = new GenericContainer<>("stripe/stripe-mock:latest")
            .withExposedPorts(12111);

    static StripeSdkGateway gateway;

    @BeforeAll
    static void gateway() {
        AppProperties props = Mockito.mock(AppProperties.class);
        Mockito.when(props.stripe()).thenReturn(new AppProperties.Stripe("sk_test_123", "whsec_123",
                "http://" + STRIPE_MOCK.getHost() + ":" + STRIPE_MOCK.getMappedPort(12111), true,
                "price_1", null, null, null, null, null, Duration.ofMinutes(5)));
        gateway = new StripeSdkGateway(props);
    }

    @Test
    void createsCustomersAndHostedPages() {
        assertThat(gateway.configured()).isTrue();
        UUID org = UUID.randomUUID();
        String customer = gateway.createCustomer(org, "Firma Testowa sp. z o.o.", "faktury@firma.test", "pl");
        assertThat(customer).startsWith("cus_");

        String checkout = gateway.createCheckoutSession(new StripeGateway.CheckoutRequest(org, UUID.randomUUID(),
                customer, "price_1", Plan.STARTER, "MONTH", "pl", "https://app.test/billing?checkout=success",
                "https://app.test/billing?checkout=cancelled", true));
        assertThat(checkout).startsWith("https://");

        String portal = gateway.createPortalSession(customer, "https://app.test/billing", "pl");
        assertThat(portal).startsWith("https://");
    }

    @Test
    void readsPricesSubscriptionsAndInvoices() {
        StripeGateway.PriceInfo price = gateway.price("price_1");
        assertThat(price.id()).isNotBlank();
        assertThat(price.currency()).isNotBlank();

        StripeGateway.SubscriptionInfo sub = gateway.subscription("sub_1");
        assertThat(sub.id()).startsWith("sub_");
        assertThat(sub.status()).isNotBlank();
        assertThat(sub.customerId()).isNotBlank();

        StripeGateway.InvoiceInfo inv = gateway.invoice("in_1");
        assertThat(inv.id()).startsWith("in_");
        assertThat(inv.currency()).isNotBlank();
    }

    @Test
    void stripeErrorsBecomeAGenericProviderError() {
        AppProperties props = Mockito.mock(AppProperties.class);
        // Nothing listens on port 9: network failure after the SDK's retries.
        Mockito.when(props.stripe()).thenReturn(new AppProperties.Stripe("sk_test_123", "whsec_123",
                "http://127.0.0.1:9", true, "price_1", null, null, null, null, null, Duration.ofMinutes(5)));
        StripeSdkGateway broken = new StripeSdkGateway(props);
        assertThatThrownBy(() -> broken.price("price_1")).isInstanceOf(ApiException.class)
                .hasMessageContaining("payment provider");
    }

    @Test
    void withoutAKeyNothingIsCalled() {
        AppProperties props = Mockito.mock(AppProperties.class);
        Mockito.when(props.stripe()).thenReturn(new AppProperties.Stripe(null, null, null, true, null, null, null,
                null, null, null, Duration.ofMinutes(5)));
        StripeSdkGateway none = new StripeSdkGateway(props);
        assertThat(none.configured()).isFalse();
        assertThatThrownBy(() -> none.price("price_1")).isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo("billing_not_configured");
    }

    @Test
    void keysAreMaskedInLoggedMessages() {
        assertThat(StripeSdkGateway.maskKeys("Authorization was 'Bearer sk_test_abc123XYZ' and whsec_9f8e"))
                .isEqualTo("Authorization was 'Bearer sk_test_***' and whsec_***");
        assertThat(StripeSdkGateway.maskKeys("Invalid API Key provided: sk_live_51Hxyz")).endsWith("sk_live_***");
    }
}
