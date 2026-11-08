package eu.tenderintel.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import eu.tenderintel.IntegrationTest;
import eu.tenderintel.TestApi;
import eu.tenderintel.TestApi.Session;
import eu.tenderintel.tender.TenderFixtures;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class BillingIntegrationTest {

    static final String WEBHOOK_SECRET = "whsec_test_only_secret";

    @Autowired
    MockMvc mvc;
    @Autowired
    TestApi api;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    FakeStripeGateway stripe;

    private String org(Session s) throws Exception {
        return api.readJson(mvc.perform(get("/api/v1/organizations").with(s.bearer())).andReturn().getResponse())
                .get(0).get("id").asString();
    }

    /** Stripe's scheme: {@code t=<unix>,v1=hex(HMAC-SHA256(secret, t + "." + payload))}. */
    static String sign(String payload, long timestamp, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String sig = HexFormat.of().formatHex(mac.doFinal((timestamp + "." + payload)
                .getBytes(StandardCharsets.UTF_8)));
        return "t=" + timestamp + ",v1=" + sig;
    }

    ResultActions webhook(String eventId, String type, String objectJson) throws Exception {
        String payload = """
                {"id":"%s","object":"event","type":"%s","created":%d,"livemode":false,
                 "data":{"object":%s}}""".formatted(eventId, type, Instant.now().getEpochSecond(), objectJson);
        return mvc.perform(post("/api/v1/webhooks/stripe").contentType(MediaType.APPLICATION_JSON).content(payload)
                .header("Stripe-Signature", sign(payload, Instant.now().getEpochSecond(), WEBHOOK_SECRET)));
    }

    StripeGateway.SubscriptionInfo subscription(String id, String customer, String status, String price,
            boolean cancelAtPeriodEnd) {
        Instant now = Instant.now();
        return new StripeGateway.SubscriptionInfo(id, customer, status, price, now, now.plusSeconds(30 * 86400),
                cancelAtPeriodEnd, null, null, null, null);
    }

    String customerOf(String org) {
        return jdbc.queryForObject("select stripe_customer_id from organizations where id = ?::uuid", String.class,
                org);
    }

    String plan(String org) {
        return jdbc.queryForObject("select plan_code || '/' || plan_source from organizations where id = ?::uuid",
                String.class, org);
    }

    @Test
    void plansArePublicAndPricedFromStripe() throws Exception {
        mvc.perform(get("/api/v1/billing/plans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.plans[0].code").value("FREE"))
                .andExpect(jsonPath("$.plans[0].purchasable").value(false))
                .andExpect(jsonPath("$.plans[0].limits.seats").value(1))
                .andExpect(jsonPath("$.plans[1].code").value("STARTER"))
                .andExpect(jsonPath("$.plans[1].purchasable").value(true))
                .andExpect(jsonPath("$.plans[1].prices[0].interval").value("MONTH"))
                .andExpect(jsonPath("$.plans[1].prices[0].unitAmount").value(24900))
                .andExpect(jsonPath("$.plans[1].prices[0].currency").value("pln"))
                .andExpect(jsonPath("$.plans[1].prices[1].interval").value("YEAR"))
                // PRO has only a monthly price configured.
                .andExpect(jsonPath("$.plans[2].prices.length()").value(1))
                .andExpect(jsonPath("$.plans[4].code").value("ENTERPRISE"))
                .andExpect(jsonPath("$.plans[4].purchasable").value(false))
                .andExpect(jsonPath("$.plans[4].limits.seats").doesNotExist());
    }

    @Test
    void checkoutIsForAdminsAndReusesTheCustomer() throws Exception {
        Session alice = api.registerVerifyLogin("alice-" + System.nanoTime() + "@billing.test");
        String org = org(alice);
        String url = "/api/v1/organizations/" + org + "/billing/checkout";

        mvc.perform(post(url).with(alice.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plan\":\"ENTERPRISE\",\"interval\":\"MONTH\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(url).with(alice.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plan\":\"PRO\",\"interval\":\"YEAR\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("price_not_configured"));
        int before = stripe.customersCreated.size();
        mvc.perform(post(url).with(alice.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plan\":\"STARTER\",\"interval\":\"MONTH\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://checkout.stripe.test/c/pay/" + org));
        mvc.perform(post(url).with(alice.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plan\":\"STARTER\",\"interval\":\"YEAR\"}"))
                .andExpect(status().isOk());
        assertThat(stripe.customersCreated.size() - before).isEqualTo(1);
        StripeGateway.CheckoutRequest last = stripe.checkouts.get(stripe.checkouts.size() - 1);
        assertThat(last.priceId()).isEqualTo("price_starter_y");
        assertThat(last.customerId()).isEqualTo(customerOf(org));
        assertThat(last.successUrl()).isEqualTo("http://localhost:4200/billing?checkout=success");
        assertThat(last.automaticTax()).isTrue();
        // A redirect back from Checkout grants nothing: only the webhook does.
        assertThat(plan(org)).isEqualTo("FREE/DEFAULT");

        // Another organization's admin cannot start checkout here.
        Session mallory = api.registerVerifyLogin("mallory-" + System.nanoTime() + "@billing.test");
        mvc.perform(post(url).with(mallory.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plan\":\"STARTER\",\"interval\":\"MONTH\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/organizations/" + org + "/billing/portal").with(alice.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://billing.stripe.test/p/session/" + customerOf(org)));
    }

    @Test
    void webhooksMustBeSignedByStripe() throws Exception {
        String payload = "{\"id\":\"evt_x\",\"type\":\"customer.subscription.updated\",\"data\":{\"object\":{}}}";
        long now = Instant.now().getEpochSecond();
        mvc.perform(post("/api/v1/webhooks/stripe").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_signature"));
        mvc.perform(post("/api/v1/webhooks/stripe").contentType(MediaType.APPLICATION_JSON).content(payload)
                        .header("Stripe-Signature", sign(payload, now, "whsec_wrong")))
                .andExpect(status().isBadRequest());
        // Replay of an old (correctly signed) delivery.
        mvc.perform(post("/api/v1/webhooks/stripe").contentType(MediaType.APPLICATION_JSON).content(payload)
                        .header("Stripe-Signature", sign(payload, now - 3600, WEBHOOK_SECRET)))
                .andExpect(status().isBadRequest());
        // Tampered body with a signature of the original.
        mvc.perform(post("/api/v1/webhooks/stripe").contentType(MediaType.APPLICATION_JSON)
                        .content(payload.replace("evt_x", "evt_y"))
                        .header("Stripe-Signature", sign(payload, now, WEBHOOK_SECRET)))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from stripe_events where event_id in ('evt_x', 'evt_y')",
                Integer.class)).isZero();
    }

    @Test
    void subscriptionLifecycleDrivesThePlanAndLimits() throws Exception {
        Session owner = api.registerVerifyLogin("owner-" + System.nanoTime() + "@billing.test");
        String org = org(owner);
        // FREE: one seat – the owner.
        mvc.perform(post("/api/v1/organizations/" + org + "/invitations").with(owner.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"colleague@billing.test\",\"role\":\"TEAM_MEMBER\"}"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("seat_limit_reached"))
                .andExpect(jsonPath("$.limit").value(1));

        mvc.perform(post("/api/v1/organizations/" + org + "/billing/checkout").with(owner.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"plan\":\"STARTER\",\"interval\":\"MONTH\"}"))
                .andExpect(status().isOk());
        String customer = customerOf(org);
        String sub = "sub_" + UUID.randomUUID().toString().replace("-", "");
        String evt = "evt_" + UUID.randomUUID().toString().replace("-", "");

        // Created but not paid yet: no plan.
        stripe.subscriptions.put(sub, subscription(sub, customer, "incomplete", "price_starter_m", false));
        webhook(evt + "1", "customer.subscription.created", "{\"id\":\"" + sub + "\",\"object\":\"subscription\"}")
                .andExpect(status().isOk());
        assertThat(plan(org)).isEqualTo("FREE/DEFAULT");

        // Paid: STARTER. The payload is not trusted – the state comes from Stripe.
        stripe.subscriptions.put(sub, subscription(sub, customer, "active", "price_starter_m", false));
        webhook(evt + "2", "customer.subscription.updated", "{\"id\":\"" + sub + "\",\"status\":\"canceled\"}")
                .andExpect(status().isOk());
        assertThat(plan(org)).isEqualTo("STARTER/STRIPE");
        // Delivered again: processed once.
        webhook(evt + "2", "customer.subscription.updated", "{\"id\":\"" + sub + "\"}").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'PLAN_CHANGED' "
                + "and organization_id = ?::uuid", Integer.class, org)).isEqualTo(1);

        mvc.perform(get("/api/v1/organizations/" + org + "/billing").with(owner.bearer()))
                .andExpect(jsonPath("$.plan").value("STARTER"))
                .andExpect(jsonPath("$.subscription.status").value("active"))
                .andExpect(jsonPath("$.subscription.interval").value("MONTH"))
                .andExpect(jsonPath("$.usage.seats.used").value(1))
                .andExpect(jsonPath("$.usage.seats.limit").value(3))
                .andExpect(jsonPath("$.usage.instantEmails").value(true))
                .andExpect(jsonPath("$.canManage").value(true));
        // A second subscription cannot be bought; changes go through the portal.
        mvc.perform(post("/api/v1/organizations/" + org + "/billing/checkout").with(owner.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"plan\":\"PRO\",\"interval\":\"MONTH\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("subscription_exists"));
        // Now there are seats for colleagues.
        mvc.perform(post("/api/v1/organizations/" + org + "/invitations").with(owner.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"colleague@billing.test\",\"role\":\"TEAM_MEMBER\"}"))
                .andExpect(status().isCreated());

        // Upgrade in the portal → PRO.
        stripe.subscriptions.put(sub, subscription(sub, customer, "active", "price_pro_m", false));
        webhook(evt + "3", "customer.subscription.updated", "{\"id\":\"" + sub + "\"}").andExpect(status().isOk());
        assertThat(plan(org)).isEqualTo("PRO/STRIPE");

        // Payment problems keep the plan while Stripe retries.
        stripe.subscriptions.put(sub, subscription(sub, customer, "past_due", "price_pro_m", false));
        webhook(evt + "4", "customer.subscription.updated", "{\"id\":\"" + sub + "\"}").andExpect(status().isOk());
        assertThat(plan(org)).isEqualTo("PRO/STRIPE");

        // Account deletion would leave Stripe charging: blocked while the subscription runs.
        mvc.perform(delete("/api/v1/me").with(owner.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + TestApi.PASSWORD + "\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("cancel_subscription_first"));

        // Ended → FREE; existing invitations and members stay, new ones are blocked again.
        stripe.subscriptions.put(sub, subscription(sub, customer, "canceled", "price_pro_m", false));
        webhook(evt + "5", "customer.subscription.deleted", "{\"id\":\"" + sub + "\"}").andExpect(status().isOk());
        assertThat(plan(org)).isEqualTo("FREE/DEFAULT");
        mvc.perform(post("/api/v1/organizations/" + org + "/invitations").with(owner.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"second@billing.test\",\"role\":\"TEAM_MEMBER\"}"))
                .andExpect(status().isPaymentRequired());
    }

    @Test
    void unknownPricesAndForeignCustomersGrantNothing() throws Exception {
        Session owner = api.registerVerifyLogin("price-" + System.nanoTime() + "@billing.test");
        String org = org(owner);
        mvc.perform(post("/api/v1/organizations/" + org + "/billing/checkout").with(owner.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"plan\":\"STARTER\",\"interval\":\"MONTH\"}"));
        String customer = customerOf(org);
        String sub = "sub_" + UUID.randomUUID().toString().replace("-", "");
        stripe.subscriptions.put(sub, subscription(sub, customer, "active", "price_someone_elses", false));
        webhook("evt_" + UUID.randomUUID(), "customer.subscription.created", "{\"id\":\"" + sub + "\"}")
                .andExpect(status().isOk());
        assertThat(plan(org)).isEqualTo("FREE/DEFAULT");

        String foreign = "sub_" + UUID.randomUUID().toString().replace("-", "");
        stripe.subscriptions.put(foreign, subscription(foreign, "cus_unknown", "active", "price_pro_m", false));
        webhook("evt_" + UUID.randomUUID(), "customer.subscription.created", "{\"id\":\"" + foreign + "\"}")
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from subscriptions where stripe_subscription_id = ?",
                Integer.class, foreign)).isZero();
    }

    @Test
    void failedPaymentIsRecordedAndAdminsAreEmailed() throws Exception {
        String email = "payer-" + System.nanoTime() + "@billing.test";
        Session owner = api.registerVerifyLogin(email);
        String org = org(owner);
        mvc.perform(post("/api/v1/organizations/" + org + "/billing/checkout").with(owner.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"plan\":\"STARTER\",\"interval\":\"MONTH\"}"));
        String customer = customerOf(org);
        String inv = "in_" + UUID.randomUUID().toString().replace("-", "");
        Instant now = Instant.now();
        stripe.invoices.put(inv, new StripeGateway.InvoiceInfo(inv, customer, "sub_x", "ETI-0001", "open", "pln",
                30627, 0, 5727L, "https://invoice.stripe.test/i/" + inv, "https://invoice.stripe.test/pdf/" + inv,
                now, now.plusSeconds(86400 * 30), null));
        webhook("evt_" + UUID.randomUUID(), "invoice.payment_failed", "{\"id\":\"" + inv + "\"}")
                .andExpect(status().isOk());
        String body = jdbc.queryForObject("select body_text from email_outbox where to_address = ? "
                + "and template = 'BILLING_PAYMENT_FAILED'", String.class, email);
        assertThat(body).contains("306,27").contains("http://localhost:4200/billing");

        // Paid on retry.
        stripe.invoices.put(inv, new StripeGateway.InvoiceInfo(inv, customer, "sub_x", "ETI-0001", "paid", "pln",
                30627, 30627, 5727L, "https://invoice.stripe.test/i/" + inv, "https://invoice.stripe.test/pdf/" + inv,
                now, now.plusSeconds(86400 * 30), now));
        webhook("evt_" + UUID.randomUUID(), "invoice.paid", "{\"id\":\"" + inv + "\"}").andExpect(status().isOk());
        mvc.perform(get("/api/v1/organizations/" + org + "/billing").with(owner.bearer()))
                .andExpect(jsonPath("$.invoices.length()").value(1))
                .andExpect(jsonPath("$.invoices[0].number").value("ETI-0001"))
                .andExpect(jsonPath("$.invoices[0].status").value("paid"))
                .andExpect(jsonPath("$.invoices[0].amountPaid").value(30627))
                .andExpect(jsonPath("$.invoices[0].tax").value(5727))
                .andExpect(jsonPath("$.invoices[0].currency").value("PLN"));
    }

    @Test
    void manualContractPlansAreNotOverwrittenByStripe() throws Exception {
        Session owner = api.registerVerifyLogin("contract-" + System.nanoTime() + "@billing.test");
        String org = org(owner);
        Session admin = adminSession();
        mvc.perform(put("/api/v1/admin/organizations/" + org + "/plan").with(admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"planCode\":\"ENTERPRISE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.planSource").value("MANUAL"));
        mvc.perform(put("/api/v1/admin/organizations/" + org + "/plan").with(owner.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"planCode\":\"ENTERPRISE\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/organizations/" + org + "/billing/checkout").with(owner.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"plan\":\"STARTER\",\"interval\":\"MONTH\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("manual_plan"));
        jdbc.update("update organizations set stripe_customer_id = ? where id = ?::uuid", "cus_contract_" + org, org);
        String sub = "sub_" + UUID.randomUUID().toString().replace("-", "");
        stripe.subscriptions.put(sub, subscription(sub, "cus_contract_" + org, "canceled", "price_starter_m", false));
        webhook("evt_" + UUID.randomUUID(), "customer.subscription.deleted", "{\"id\":\"" + sub + "\"}")
                .andExpect(status().isOk());
        assertThat(plan(org)).isEqualTo("ENTERPRISE/MANUAL");
        mvc.perform(put("/api/v1/admin/organizations/" + org + "/plan").with(admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"planCode\":\"AUTO\"}"))
                .andExpect(status().isOk());
        assertThat(plan(org)).isEqualTo("FREE/DEFAULT");
    }

    @Test
    void savedSearchAndExportLimitsFollowThePlan() throws Exception {
        TenderFixtures.insert(jdbc);
        Session user = api.registerVerifyLogin("limits-" + System.nanoTime() + "@billing.test");
        String org = org(user);
        String base = "/api/v1/organizations/" + org + "/saved-searches";
        for (int i = 0; i < 3; i++) {
            mvc.perform(post(base).with(user.bearer()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"S" + i + "\",\"filters\":{\"country\":[\"PL\"]}}"))
                    .andExpect(status().isCreated());
        }
        mvc.perform(post(base).with(user.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"S4\",\"filters\":{\"country\":[\"PL\"]}}"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("saved_search_limit_reached"))
                .andExpect(jsonPath("$.limit").value(3)).andExpect(jsonPath("$.used").value(3));

        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/api/v1/tenders/export").param("orgId", org).with(user.bearer()))
                    .andExpect(status().isOk());
        }
        mvc.perform(get("/api/v1/tenders/export").param("orgId", org).with(user.bearer()))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("export_limit_reached"));
        // Exports are counted per organization; a stranger cannot spend (or read) them.
        Session other = api.registerVerifyLogin("other-" + System.nanoTime() + "@billing.test");
        mvc.perform(get("/api/v1/tenders/export").param("orgId", org).with(other.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/organizations/" + org + "/billing").with(user.bearer()))
                .andExpect(jsonPath("$.usage.exports.used").value(3))
                .andExpect(jsonPath("$.usage.exports.limit").value(3))
                .andExpect(jsonPath("$.usage.savedSearches.used").value(3));
    }

    private Session adminSession() throws Exception {
        String email = "platform-" + System.nanoTime() + "@billing.test";
        api.registerVerifyLogin(email);
        jdbc.update("update users set platform_role = 'PLATFORM_ADMIN' where email = ?", email);
        return api.login(email, TestApi.PASSWORD);
    }
}
