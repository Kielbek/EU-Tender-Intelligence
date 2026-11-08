package eu.tenderintel.document;

import eu.tenderintel.common.error.ApiException;
import eu.tenderintel.document.DocumentDtos.DocumentView;
import eu.tenderintel.document.DocumentDtos.FetchResult;
import eu.tenderintel.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/organizations/{orgId}/tenders/{tenderId}/documents")
@Tag(name = "Tender documents", description = "Dokumenty przetargu: publiczne (z linku w ogłoszeniu) i wgrane przez organizację")
@SecurityRequirement(name = "bearer")
class DocumentController {

    private final DocumentService service;

    DocumentController(DocumentService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Dokumenty publiczne i dokumenty wgrane przez organizację")
    List<DocumentView> list(@PathVariable UUID orgId, @PathVariable UUID tenderId) {
        return service.list(orgId, CurrentUser.id(), tenderId);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Wgranie dokumentu (PDF, DOCX, DOC, ODT, RTF, TXT, XLSX, XLS, ODS lub ZIP) – prywatny dla organizacji")
    List<DocumentView> upload(@PathVariable UUID orgId, @PathVariable UUID tenderId,
            @RequestPart("file") MultipartFile file) {
        try {
            return service.upload(orgId, CurrentUser.id(), tenderId, file.getOriginalFilename(), file.getBytes());
        } catch (IOException e) {
            throw ApiException.badRequest("upload_failed", "The file could not be read");
        }
    }

    @PostMapping("/fetch-public")
    @Operation(summary = "Pobranie dokumentów z linku w ogłoszeniu – tylko bezpośredni, publiczny plik (bez logowania i CAPTCHA)")
    FetchResult fetchPublic(@PathVariable UUID orgId, @PathVariable UUID tenderId) {
        return service.fetchPublic(orgId, CurrentUser.id(), tenderId);
    }

    @DeleteMapping("/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Usunięcie dokumentu wgranego przez organizację")
    void delete(@PathVariable UUID orgId, @PathVariable UUID tenderId, @PathVariable UUID documentId) {
        service.delete(orgId, CurrentUser.id(), tenderId, documentId);
    }
}
