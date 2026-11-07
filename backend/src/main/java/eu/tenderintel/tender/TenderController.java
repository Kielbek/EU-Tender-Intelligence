package eu.tenderintel.tender;

import eu.tenderintel.common.api.PageResponse;
import eu.tenderintel.tender.TenderDtos.TenderDetail;
import eu.tenderintel.tender.TenderDtos.TenderSearchCriteria;
import eu.tenderintel.tender.TenderDtos.TenderSummary;
import eu.tenderintel.tender.TenderDtos.VersionView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenders")
@Tag(name = "Tenders", description = "Wyszukiwanie i szczegóły przetargów")
@SecurityRequirement(name = "bearer")
class TenderController {

    private final TenderQueries queries;

    TenderController(TenderQueries queries) {
        this.queries = queries;
    }

    @GetMapping
    @Operation(summary = "Wyszukiwanie przetargów",
            description = "Filtry wielowartościowe przez powtórzenie parametru (country=DE&country=PL). "
                    + "cpv: kod lub prefiks – 45000000 obejmuje cały dział 45. Wartości filtrowane w EUR "
                    + "(przeliczone kursem EBC z dnia publikacji). Daty w ISO 8601 (UTC).")
    PageResponse<TenderSummary> search(@Valid @ModelAttribute TenderSearchCriteria criteria) {
        return queries.search(criteria);
    }

    @GetMapping("/{id}")
    TenderDetail get(@PathVariable UUID id) {
        return queries.get(id);
    }

    @GetMapping("/{id}/versions")
    @Operation(summary = "Historia zmian ogłoszenia (sprostowania, zmiany terminów, anulowanie, wynik)")
    List<VersionView> versions(@PathVariable UUID id) {
        return queries.versions(id);
    }
}
