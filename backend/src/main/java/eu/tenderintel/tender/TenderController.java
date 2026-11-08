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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenders")
@Tag(name = "Tenders", description = "Wyszukiwanie i szczegóły przetargów")
@SecurityRequirement(name = "bearer")
class TenderController {

    private final TenderQueries queries;
    private final TenderExportService exports;
    private final eu.tenderintel.ratelimit.RateLimiter rateLimiter;
    private final eu.tenderintel.audit.AuditService audit;

    TenderController(TenderQueries queries, TenderExportService exports,
            eu.tenderintel.ratelimit.RateLimiter rateLimiter, eu.tenderintel.audit.AuditService audit) {
        this.queries = queries;
        this.exports = exports;
        this.rateLimiter = rateLimiter;
        this.audit = audit;
    }

    @GetMapping("/export")
    @Operation(summary = "Eksport wyników wyszukiwania do CSV lub XLSX",
            description = "Te same filtry co wyszukiwanie; maks. 5000 wierszy (nagłówek X-Total-Count podaje liczbę "
                    + "wszystkich wyników). Limity planów abonamentowych – Etap 6.")
    org.springframework.http.ResponseEntity<byte[]> export(@Valid @ModelAttribute TenderSearchCriteria criteria,
            @RequestParam(defaultValue = "csv") @jakarta.validation.constraints.Pattern(regexp = "csv|xlsx") String format,
            @RequestParam(defaultValue = "en") String lang) throws java.io.IOException {
        java.util.UUID userId = eu.tenderintel.security.CurrentUser.id();
        rateLimiter.check("export", userId.toString(), 30, java.time.Duration.ofHours(1));
        String l = TenderQueries.lang(lang);
        long total = queries.count(criteria);
        java.util.List<TenderDtos.TenderSummary> rows = queries.list(criteria, l, TenderExportService.MAX_ROWS);
        audit.record(eu.tenderintel.audit.AuditAction.DATA_EXPORTED, userId, null, "tenders", null,
                java.util.Map.of("format", format, "rows", rows.size(), "total", total));
        String stamp = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
        boolean xlsx = "xlsx".equals(format);
        // At most MAX_ROWS rows are already in memory, so the file is built before responding: a failure becomes a
        // proper error response instead of a truncated download.
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        if (xlsx) {
            exports.writeXlsx(rows, l, out);
        } else {
            exports.writeCsv(rows, l, out);
        }
        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"tenders-" + stamp + (xlsx ? ".xlsx" : ".csv") + "\"")
                .header("X-Total-Count", String.valueOf(total))
                .header("X-Export-Limit", String.valueOf(TenderExportService.MAX_ROWS))
                .contentType(xlsx ? org.springframework.http.MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                        : new org.springframework.http.MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .body(out.toByteArray());
    }

    @GetMapping
    @Operation(summary = "Wyszukiwanie przetargów",
            description = "Filtry wielowartościowe przez powtórzenie parametru (country=DE&country=PL). "
                    + "cpv: kod lub prefiks – 45000000 obejmuje cały dział 45. Wartości filtrowane w EUR "
                    + "(przeliczone kursem EBC z dnia publikacji). Daty w ISO 8601 (UTC).")
    PageResponse<TenderSummary> search(@Valid @ModelAttribute TenderSearchCriteria criteria,
            @RequestParam(defaultValue = "en") String lang) {
        return queries.search(criteria, lang);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Szczegóły przetargu: zamawiający, CPV z nazwami w języku lang, części, wyniki, źródła")
    TenderDetail get(@PathVariable UUID id, @RequestParam(defaultValue = "en") String lang) {
        return queries.get(id, lang);
    }

    @GetMapping("/{id}/versions")
    @Operation(summary = "Historia zmian ogłoszenia (sprostowania, zmiany terminów, anulowanie, wynik)")
    List<VersionView> versions(@PathVariable UUID id) {
        return queries.versions(id);
    }
}
