package eu.tenderintel.company;

import eu.tenderintel.company.CompanyDtos.CertificationRequest;
import eu.tenderintel.company.CompanyDtos.CertificationView;
import eu.tenderintel.company.CompanyDtos.CompanyProfileRequest;
import eu.tenderintel.company.CompanyDtos.CompanyProfileView;
import eu.tenderintel.company.CompanyDtos.ReferenceRequest;
import eu.tenderintel.company.CompanyDtos.ReferenceView;
import eu.tenderintel.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations/{orgId}/company-profile")
@Tag(name = "Company profile", description = "Profil firmy (prywatny dla organizacji) – podstawa dopasowania przetargów")
@SecurityRequirement(name = "bearer")
class CompanyProfileController {

    private final CompanyProfileService service;

    CompanyProfileController(CompanyProfileService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Profil firmy (członkowie organizacji); 404 profile_missing, jeśli nie utworzono")
    CompanyProfileView get(@PathVariable UUID orgId, @RequestParam(defaultValue = "en") String lang) {
        return service.get(orgId, CurrentUser.id(), lang);
    }

    @PutMapping
    @Operation(summary = "Utworzenie lub zmiana profilu (COMPANY_ADMIN); przelicza dopasowania")
    CompanyProfileView save(@PathVariable UUID orgId, @Valid @RequestBody CompanyProfileRequest req,
            @RequestParam(defaultValue = "en") String lang) {
        return service.save(orgId, CurrentUser.id(), req, lang);
    }

    @PostMapping("/certifications")
    @ResponseStatus(HttpStatus.CREATED)
    CertificationView addCertification(@PathVariable UUID orgId, @Valid @RequestBody CertificationRequest req) {
        return service.addCertification(orgId, CurrentUser.id(), req);
    }

    @DeleteMapping("/certifications/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteCertification(@PathVariable UUID orgId, @PathVariable UUID id) {
        service.deleteCertification(orgId, CurrentUser.id(), id);
    }

    @PostMapping("/references")
    @ResponseStatus(HttpStatus.CREATED)
    ReferenceView addReference(@PathVariable UUID orgId, @Valid @RequestBody ReferenceRequest req) {
        return service.addReference(orgId, CurrentUser.id(), req);
    }

    @DeleteMapping("/references/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteReference(@PathVariable UUID orgId, @PathVariable UUID id) {
        service.deleteReference(orgId, CurrentUser.id(), id);
    }
}
