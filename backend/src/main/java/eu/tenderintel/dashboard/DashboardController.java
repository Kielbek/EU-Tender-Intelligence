package eu.tenderintel.dashboard;

import eu.tenderintel.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Dashboard")
@SecurityRequirement(name = "bearer")
class DashboardController {

    private final DashboardService dashboard;

    DashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping("/api/v1/organizations/{orgId}/dashboard")
    @Operation(summary = "Panel główny: liczby, obserwowane terminy, branże, kraje, wykres ogłoszeń, ostatnie zmiany")
    DashboardService.Summary summary(@PathVariable UUID orgId, @RequestParam(defaultValue = "en") String lang) {
        return dashboard.summary(orgId, CurrentUser.id(), lang);
    }
}
