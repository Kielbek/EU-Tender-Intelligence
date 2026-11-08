package eu.tenderintel.ai;

import eu.tenderintel.ai.AnalysisService.AnalysisView;
import eu.tenderintel.ai.AnalysisService.QuotaView;
import eu.tenderintel.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations/{orgId}")
@Tag(name = "AI analysis", description = "Analiza dokumentacji przetargowej (Claude) z cytatami ze źródeł")
@SecurityRequirement(name = "bearer")
class AnalysisController {

    private final AnalysisService service;

    AnalysisController(AnalysisService service) {
        this.service = service;
    }

    @GetMapping("/ai/quota")
    @Operation(summary = "Limit analiz AI w bieżącym miesiącu (plan organizacji)")
    QuotaView quota(@PathVariable UUID orgId) {
        return service.quota(orgId, CurrentUser.id());
    }

    @PostMapping("/tenders/{tenderId}/analyses")
    @Operation(summary = "Uruchomienie analizy (202) lub zwrot istniejącej dla tych samych dokumentów (200, bez zużycia limitu)")
    ResponseEntity<AnalysisView> request(@PathVariable UUID orgId, @PathVariable UUID tenderId,
            @RequestParam(defaultValue = "en") String lang) {
        AnalysisView v = service.request(orgId, CurrentUser.id(), tenderId, lang);
        boolean running = "PENDING".equals(v.status()) || "RUNNING".equals(v.status());
        return ResponseEntity.status(running ? HttpStatus.ACCEPTED : HttpStatus.OK).body(v);
    }

    @GetMapping("/tenders/{tenderId}/analyses/latest")
    @Operation(summary = "Ostatnia analiza przetargu w danym języku; 404 analysis_missing")
    AnalysisView latest(@PathVariable UUID orgId, @PathVariable UUID tenderId,
            @RequestParam(defaultValue = "en") String lang) {
        return service.latest(orgId, CurrentUser.id(), tenderId, lang);
    }
}
