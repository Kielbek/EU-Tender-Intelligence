package eu.tenderintel.matching;

import eu.tenderintel.common.api.PageResponse;
import eu.tenderintel.matching.MatchingService.MatchView;
import eu.tenderintel.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations/{orgId}/matches")
@Tag(name = "Matching", description = "Dopasowanie otwartych przetargów do profilu firmy (0–100, to nie szansa wygranej)")
@SecurityRequirement(name = "bearer")
class MatchingController {

    private final MatchingService matching;

    MatchingController(MatchingService matching) {
        this.matching = matching;
    }

    @GetMapping
    @Operation(summary = "Otwarte przetargi dopasowane do profilu, od najlepszych")
    PageResponse<MatchView> list(@PathVariable UUID orgId,
            @RequestParam(defaultValue = "60") @Min(40) @Max(100) int minScore,
            @RequestParam(defaultValue = "0") @Min(0) @Max(499) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "en") String lang) {
        return matching.list(orgId, CurrentUser.id(), minScore, page, size, lang);
    }

    @GetMapping("/{tenderId}")
    @Operation(summary = "Wyjaśnienie dopasowania konkretnego przetargu (liczone na żywo)")
    MatchView explain(@PathVariable UUID orgId, @PathVariable UUID tenderId,
            @RequestParam(defaultValue = "en") String lang) {
        return matching.explain(orgId, CurrentUser.id(), tenderId, lang);
    }
}
