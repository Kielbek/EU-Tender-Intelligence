package eu.tenderintel.system;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
@Tag(name = "System", description = "Status platformy i gotowość integracji")
class SystemStatusController {

    private final SystemStatusService service;

    SystemStatusController(SystemStatusService service) {
        this.service = service;
    }

    @GetMapping("/status")
    @Operation(summary = "Status platformy",
            description = "Wersja aplikacji, stan bazy i Redis oraz gotowość integracji zewnętrznych "
                    + "(READY / PENDING_CONFIGURATION / NOT_IMPLEMENTED). Nie zawiera danych wrażliwych.")
    ResponseEntity<SystemStatus> status() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.status());
    }
}
