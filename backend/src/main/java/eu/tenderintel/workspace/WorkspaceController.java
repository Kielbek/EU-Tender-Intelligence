package eu.tenderintel.workspace;

import eu.tenderintel.security.CurrentUser;
import eu.tenderintel.workspace.WorkspaceDtos.RenameRequest;
import eu.tenderintel.workspace.WorkspaceDtos.SaveTenderRequest;
import eu.tenderintel.workspace.WorkspaceDtos.SavedSearchRequest;
import eu.tenderintel.workspace.WorkspaceDtos.SavedSearchView;
import eu.tenderintel.workspace.WorkspaceDtos.SavedTenderView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations/{orgId}")
@Tag(name = "Workspace", description = "Obserwowane przetargi (zespół) i zapisane wyszukiwania (użytkownik)")
@SecurityRequirement(name = "bearer")
class WorkspaceController {

    private final WatchlistService watchlist;
    private final SavedSearchService searches;

    WorkspaceController(WatchlistService watchlist, SavedSearchService searches) {
        this.watchlist = watchlist;
        this.searches = searches;
    }

    @GetMapping("/saved-tenders")
    @Operation(summary = "Obserwowane przetargi organizacji (opcjonalnie filtr status)")
    List<SavedTenderView> savedTenders(@PathVariable UUID orgId,
            @RequestParam(required = false) @Pattern(regexp = WorkspaceDtos.STATUSES) String status,
            @RequestParam(defaultValue = "en") String lang) {
        return watchlist.list(orgId, CurrentUser.id(), status, lang);
    }

    @GetMapping("/saved-tenders/{tenderId}")
    SavedTenderView savedTender(@PathVariable UUID orgId, @PathVariable UUID tenderId,
            @RequestParam(defaultValue = "en") String lang) {
        return watchlist.get(orgId, CurrentUser.id(), tenderId, lang);
    }

    @PutMapping("/saved-tenders/{tenderId}")
    @Operation(summary = "Dodanie do obserwowanych lub zmiana etapu / notatek (idempotentne)")
    SavedTenderView save(@PathVariable UUID orgId, @PathVariable UUID tenderId,
            @Valid @RequestBody SaveTenderRequest req, @RequestParam(defaultValue = "en") String lang) {
        return watchlist.save(orgId, CurrentUser.id(), tenderId, req, lang);
    }

    @DeleteMapping("/saved-tenders/{tenderId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unsave(@PathVariable UUID orgId, @PathVariable UUID tenderId) {
        watchlist.remove(orgId, CurrentUser.id(), tenderId);
    }

    @GetMapping("/saved-searches")
    List<SavedSearchView> savedSearches(@PathVariable UUID orgId) {
        return searches.list(orgId, CurrentUser.id());
    }

    @PostMapping("/saved-searches")
    @ResponseStatus(HttpStatus.CREATED)
    SavedSearchView createSearch(@PathVariable UUID orgId, @Valid @RequestBody SavedSearchRequest req) {
        return searches.create(orgId, CurrentUser.id(), req.name(), req.filters());
    }

    @PatchMapping("/saved-searches/{id}")
    SavedSearchView renameSearch(@PathVariable UUID orgId, @PathVariable UUID id,
            @Valid @RequestBody RenameRequest req) {
        return searches.update(orgId, CurrentUser.id(), id, req.name(), null);
    }

    @PutMapping("/saved-searches/{id}")
    SavedSearchView replaceSearch(@PathVariable UUID orgId, @PathVariable UUID id,
            @Valid @RequestBody SavedSearchRequest req) {
        return searches.update(orgId, CurrentUser.id(), id, req.name(), req.filters());
    }

    @PostMapping("/saved-searches/{id}/run")
    SavedSearchView markRun(@PathVariable UUID orgId, @PathVariable UUID id) {
        return searches.markRun(orgId, CurrentUser.id(), id);
    }

    @DeleteMapping("/saved-searches/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteSearch(@PathVariable UUID orgId, @PathVariable UUID id) {
        searches.delete(orgId, CurrentUser.id(), id);
    }
}
