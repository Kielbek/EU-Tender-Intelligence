package eu.tenderintel.billing;

import eu.tenderintel.audit.AuditAction;
import eu.tenderintel.audit.AuditService;
import eu.tenderintel.common.error.ApiException;
import eu.tenderintel.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform administration of plans: contracts outside Stripe (e.g. ENTERPRISE) are assigned manually; going back to
 * {@code STRIPE} hands the plan back to the organization's subscriptions.
 */
@RestController
@Tag(name = "Admin")
@SecurityRequirement(name = "bearer")
class BillingAdminController {

    private final JdbcTemplate jdbc;
    private final StripeWebhookService webhooks;
    private final AuditService audit;

    BillingAdminController(JdbcTemplate jdbc, StripeWebhookService webhooks, AuditService audit) {
        this.jdbc = jdbc;
        this.webhooks = webhooks;
        this.audit = audit;
    }

    record OrganizationPlanView(UUID id, String name, String planCode, String planSource, long members,
            String subscriptionStatus, String stripeCustomerId) {
    }

    record PlanAssignment(@NotNull @Pattern(regexp = "FREE|STARTER|PRO|BUSINESS|ENTERPRISE|AUTO") String planCode) {
    }

    @GetMapping("/api/v1/admin/organizations")
    @Operation(summary = "Organizacje z planem, źródłem planu i statusem subskrypcji")
    List<OrganizationPlanView> organizations() {
        return jdbc.query("""
                select o.id, o.name, o.plan_code, o.plan_source,
                       (select count(*) from organization_members m where m.organization_id = o.id),
                       (select s.status from subscriptions s where s.organization_id = o.id
                        order by s.updated_at desc limit 1),
                       o.stripe_customer_id
                from organizations o order by o.created_at desc limit 500
                """, (rs, i) -> new OrganizationPlanView(rs.getObject(1, UUID.class), rs.getString(2),
                rs.getString(3), rs.getString(4), rs.getLong(5), rs.getString(6), rs.getString(7)));
    }

    /** {@code AUTO}: the plan follows the Stripe subscriptions again. */
    @PutMapping("/api/v1/admin/organizations/{id}/plan")
    @Transactional
    @Operation(summary = "Ręczne przypisanie planu (umowa poza Stripe) albo AUTO – plan wg subskrypcji Stripe")
    OrganizationPlanView assign(@PathVariable UUID id, @Valid @RequestBody PlanAssignment req) {
        Map<String, Object> org = jdbc.query("select plan_code from organizations where id = ? for update",
                rs -> rs.next() ? Map.<String, Object>of("plan", rs.getString(1)) : null, id);
        if (org == null) {
            throw ApiException.notFound("Organization");
        }
        if ("AUTO".equals(req.planCode())) {
            jdbc.update("update organizations set plan_source = 'DEFAULT', version = version + 1 where id = ?", id);
            webhooks.recomputePlan(id);
        } else {
            jdbc.update("update organizations set plan_code = ?, plan_source = 'MANUAL', updated_at = now(), "
                    + "version = version + 1 where id = ?", req.planCode(), id);
        }
        audit.record(AuditAction.PLAN_CHANGED, CurrentUser.id(), id, "organization", id,
                Map.of("from", org.get("plan"), "to", req.planCode(), "source", "ADMIN"));
        return organizations().stream().filter(o -> o.id().equals(id)).findFirst().orElseThrow();
    }
}
