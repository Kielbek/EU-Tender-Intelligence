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
    private final TenderMapService map;

    DashboardController(DashboardService dashboard, TenderMapService map) {
        this.dashboard = dashboard;
        this.map = map;
    }

    @GetMapping("/api/v1/organizations/{orgId}/dashboard/map")
    @Operation(summary = "Mapa otwartych przetargów: liczby per kraj (level=0), region NUTS 2 lub podregion NUTS 3; "
            + "scope=ALL – wszystkie otwarte, MATCHES – dobre dopasowania organizacji (wynik ≥ 60)")
    TenderMapService.TenderMap map(@PathVariable UUID orgId, @RequestParam(defaultValue = "0") int level,
            @RequestParam(defaultValue = "ALL") TenderMapService.Scope scope) {
        return map.map(orgId, CurrentUser.id(), level, scope);
    }

    @GetMapping("/api/v1/organizations/{orgId}/dashboard")
    @Operation(summary = "Panel główny: liczby, obserwowane terminy, branże, kraje, wykres ogłoszeń, ostatnie zmiany")
    DashboardService.Summary summary(@PathVariable UUID orgId, @RequestParam(defaultValue = "en") String lang) {
        return dashboard.summary(orgId, CurrentUser.id(), lang);
    }
}
