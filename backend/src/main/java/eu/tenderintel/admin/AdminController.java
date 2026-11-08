package eu.tenderintel.admin;

import eu.tenderintel.admin.AdminDtos.AdminUserView;
import eu.tenderintel.admin.AdminDtos.AuditLogView;
import eu.tenderintel.admin.AdminDtos.DataSourceView;
import eu.tenderintel.admin.AdminDtos.EmailOutboxStats;
import eu.tenderintel.admin.AdminDtos.IngestionJobView;
import eu.tenderintel.admin.AdminDtos.JobStarted;
import eu.tenderintel.common.api.PageResponse;
import eu.tenderintel.ingestion.ted.TedIngestionJobs;
import eu.tenderintel.reference.ReferenceImportJobs;
import eu.tenderintel.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Platform administration. URL-level rule (/api/v1/admin/**) plus method-level check – defence in depth. */
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
@Tag(name = "Admin", description = "Panel administratora platformy (PLATFORM_ADMIN)")
@SecurityRequirement(name = "bearer")
class AdminController {

    private final AdminService admin;
    private final ReferenceImportJobs referenceJobs;
    private final TedIngestionJobs tedJobs;

    AdminController(AdminService admin, ReferenceImportJobs referenceJobs, TedIngestionJobs tedJobs) {
        this.admin = admin;
        this.referenceJobs = referenceJobs;
        this.tedJobs = tedJobs;
    }

    @GetMapping("/users")
    PageResponse<AdminUserView> users(@RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return admin.users(q, page, size);
    }

    @PostMapping("/users/{id}/lock")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Zablokowanie konta (unieważnia sesje)")
    void lock(@PathVariable UUID id) {
        admin.lock(id, CurrentUser.id());
    }

    @PostMapping("/users/{id}/unlock")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unlock(@PathVariable UUID id) {
        admin.unlock(id, CurrentUser.id());
    }

    @GetMapping("/audit-logs")
    PageResponse<AuditLogView> auditLogs(@RequestParam(required = false) String action,
            @RequestParam(required = false) UUID actorUserId,
            @RequestParam(required = false) UUID organizationId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return admin.auditLogs(action, actorUserId, organizationId, page, size);
    }

    @GetMapping("/data-sources")
    List<DataSourceView> dataSources() {
        return admin.dataSources();
    }

    @GetMapping("/ingestion-jobs")
    PageResponse<IngestionJobView> jobs(@RequestParam(required = false) String source,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return admin.jobs(source, page, size);
    }

    @PostMapping("/reference/cpv/import")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Import słownika CPV z Urzędu Publikacji UE (zadanie w tle)")
    JobStarted importCpv() {
        return new JobStarted(referenceJobs.startCpvImport("admin", CurrentUser.id()));
    }

    @PostMapping("/reference/exchange-rates/import")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Import kursów EBC (domyślnie ostatnie 90 dni)")
    JobStarted importRates(@RequestParam(defaultValue = "true") boolean last90Days) {
        return new JobStarted(referenceJobs.startExchangeRatesImport("admin", CurrentUser.id(), last90Days));
    }

    @PostMapping("/ted/ingest")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Import ogłoszeń TED z zakresu dat publikacji (zadanie w tle, maks. 31 dni)")
    JobStarted ingestTed(@RequestParam @org.springframework.format.annotation.DateTimeFormat(
                    iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate from,
            @RequestParam @org.springframework.format.annotation.DateTimeFormat(
                    iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate to) {
        return new JobStarted(tedJobs.startWindow(from, to, "admin:" + CurrentUser.id()));
    }

    @PostMapping("/ingestion-jobs/{id}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Ponowienie zadania importu TED dla tego samego zakresu dat")
    JobStarted retryJob(@PathVariable UUID id) {
        return new JobStarted(tedJobs.retry(id));
    }

    @GetMapping("/email-outbox")
    EmailOutboxStats emailOutbox() {
        return admin.emailOutboxStats();
    }
}
