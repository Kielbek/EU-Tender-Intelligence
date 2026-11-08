package eu.tenderintel.billing;

import eu.tenderintel.billing.BillingDtos.BillingView;
import eu.tenderintel.billing.BillingDtos.CheckoutRequest;
import eu.tenderintel.billing.BillingDtos.PlansView;
import eu.tenderintel.billing.BillingDtos.RedirectView;
import eu.tenderintel.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Billing", description = "Plany, subskrypcja Stripe, limity")
class BillingController {

    private final BillingService billing;
    private final StripeWebhookService webhooks;

    BillingController(BillingService billing, StripeWebhookService webhooks) {
        this.billing = billing;
        this.webhooks = webhooks;
    }

    @GetMapping("/api/v1/billing/plans")
    @Operation(summary = "Plany z limitami i cenami ze Stripe (publiczne)")
    PlansView plans() {
        return billing.plans();
    }

    @GetMapping("/api/v1/organizations/{orgId}/billing")
    @SecurityRequirement(name = "bearer")
    @Operation(summary = "Plan, subskrypcja, wykorzystanie limitów i faktury (faktury tylko dla administratora)")
    BillingView overview(@PathVariable UUID orgId) {
        return billing.overview(orgId, CurrentUser.id());
    }

    @PostMapping("/api/v1/organizations/{orgId}/billing/checkout")
    @SecurityRequirement(name = "bearer")
    @Operation(summary = "Stripe Checkout dla nowej subskrypcji (administrator); zwraca adres strony płatności")
    RedirectView checkout(@PathVariable UUID orgId, @Valid @RequestBody CheckoutRequest req,
            @RequestParam(defaultValue = "pl") String lang) {
        return new RedirectView(billing.checkout(orgId, CurrentUser.id(), req.plan(), req.interval(), lang(lang)));
    }

    @PostMapping("/api/v1/organizations/{orgId}/billing/portal")
    @SecurityRequirement(name = "bearer")
    @Operation(summary = "Stripe Customer Portal: zmiana planu, karty, anulowanie, faktury (administrator)")
    RedirectView portal(@PathVariable UUID orgId, @RequestParam(defaultValue = "pl") String lang) {
        return new RedirectView(billing.portal(orgId, CurrentUser.id(), lang(lang)));
    }

    @PostMapping("/api/v1/webhooks/stripe")
    @Operation(summary = "Webhook Stripe (weryfikacja podpisu Stripe-Signature, idempotencja po id zdarzenia)")
    ResponseEntity<Void> webhook(@RequestBody String payload,
            @RequestHeader(value = "Stripe-Signature", required = false) String signature) {
        webhooks.handle(payload, signature);
        return ResponseEntity.ok().build();
    }

    private static String lang(String lang) {
        return "pl".equals(lang) ? "pl" : "en";
    }
}
