package eu.tenderintel.ai;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** AI operations for platform admins: a live connection check and usage/cost per organization. */
@RestController
@RequestMapping("/api/v1/admin/ai")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
@Tag(name = "Admin", description = "Panel administratora platformy (PLATFORM_ADMIN)")
@SecurityRequirement(name = "bearer")
class AiAdminController {

    private final ClaudeGateway claude;
    private final JdbcTemplate jdbc;

    AiAdminController(ClaudeGateway claude, JdbcTemplate jdbc) {
        this.claude = claude;
        this.jdbc = jdbc;
    }

    @PostMapping("/check")
    @Operation(summary = "Sprawdzenie klucza Anthropic i dostępności modelu (prawdziwe wywołanie API, bez kosztu tokenów)")
    ResponseEntity<Map<String, Object>> check() {
        if (!claude.configured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("ok", false, "code", "ai_not_configured"));
        }
        try {
            return ResponseEntity.ok(Map.of("ok", true, "model", claude.check()));
        } catch (ClaudeGateway.GatewayException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("ok", false, "code", e.code()));
        }
    }

    public record UsageRow(String organizationId, String organizationName, long analyses, long inputTokens,
            long outputTokens, BigDecimal costUsd) {
    }

    @GetMapping("/usage")
    @Operation(summary = "Zużycie AI i koszt per organizacja w bieżącym miesiącu (UTC)")
    List<UsageRow> usage() {
        return jdbc.query("""
                select o.id, o.name, count(u.id), coalesce(sum(u.input_tokens), 0), coalesce(sum(u.output_tokens), 0),
                       coalesce(sum(u.cost_usd), 0)
                from ai_usage u join organizations o on o.id = u.organization_id
                where u.created_at >= date_trunc('month', now() at time zone 'UTC') at time zone 'UTC'
                group by o.id, o.name order by 6 desc
                """, (rs, i) -> new UsageRow(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4),
                rs.getLong(5), rs.getBigDecimal(6)));
    }
}
