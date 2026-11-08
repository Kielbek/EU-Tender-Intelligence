package eu.tenderintel.market;

import eu.tenderintel.common.error.ApiException;
import eu.tenderintel.market.MarketDtos.AuthorityHit;
import eu.tenderintel.market.MarketDtos.AuthorityProfile;
import eu.tenderintel.market.MarketDtos.CompanyHit;
import eu.tenderintel.market.MarketDtos.CompanyProfile;
import eu.tenderintel.market.MarketDtos.CompetitorsView;
import eu.tenderintel.market.MarketDtos.TenderMarket;
import eu.tenderintel.market.MarketDtos.WatchRequest;
import eu.tenderintel.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
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
@Validated
@RequestMapping("/api/v1")
@Tag(name = "Market", description = "Wyniki przetargów, ceny, zamawiający i konkurenci")
@SecurityRequirement(name = "bearer")
class MarketController {

    private final MarketService market;
    private final CompetitorService competitors;
    private final MarketRefreshJob refresh;

    MarketController(MarketService market, CompetitorService competitors, MarketRefreshJob refresh) {
        this.market = market;
        this.competitors = competitors;
        this.refresh = refresh;
    }

    @GetMapping("/organizations/{orgId}/market/tenders/{tenderId}")
    @Operation(summary = "Podobne rozstrzygnięte przetargi: zwycięzcy, liczba ofert, cena względem wartości szacunkowej")
    TenderMarket tender(@PathVariable UUID orgId, @PathVariable UUID tenderId,
            @RequestParam(defaultValue = "en") String lang) {
        return market.forTender(orgId, CurrentUser.id(), tenderId, lang);
    }

    @GetMapping("/organizations/{orgId}/market/authorities/{id}")
    @Operation(summary = "Profil zamawiającego")
    AuthorityProfile authority(@PathVariable UUID orgId, @PathVariable UUID id,
            @RequestParam(defaultValue = "en") String lang) {
        return market.authorityProfile(orgId, CurrentUser.id(), id, lang);
    }

    @GetMapping("/organizations/{orgId}/market/companies/{id}")
    @Operation(summary = "Profil firmy (wykonawcy): wygrane, branże, zamawiający")
    CompanyProfile company(@PathVariable UUID orgId, @PathVariable UUID id,
            @RequestParam(defaultValue = "en") String lang) {
        return market.companyProfile(orgId, CurrentUser.id(), id, lang);
    }

    @GetMapping("/organizations/{orgId}/market/companies")
    @Operation(summary = "Wyszukiwanie firm po nazwie lub numerze identyfikacyjnym (np. NIP)")
    List<CompanyHit> searchCompanies(@PathVariable UUID orgId, @RequestParam @Size(max = 200) String q) {
        return market.searchCompanies(orgId, CurrentUser.id(), q);
    }

    @GetMapping("/organizations/{orgId}/market/authorities")
    @Operation(summary = "Wyszukiwanie zamawiających po nazwie lub numerze identyfikacyjnym")
    List<AuthorityHit> searchAuthorities(@PathVariable UUID orgId, @RequestParam @Size(max = 200) String q) {
        return market.searchAuthorities(orgId, CurrentUser.id(), q);
    }

    @GetMapping("/organizations/{orgId}/competitors")
    CompetitorsView competitors(@PathVariable UUID orgId) {
        return competitors.list(orgId, CurrentUser.id());
    }

    @PutMapping("/organizations/{orgId}/competitors/{operatorId}")
    @Operation(summary = "Obserwuj konkurenta (limit wg planu, 402 competitor_limit_reached)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void watch(@PathVariable UUID orgId, @PathVariable UUID operatorId,
            @Valid @RequestBody(required = false) WatchRequest req) {
        competitors.watch(orgId, CurrentUser.id(), operatorId, req == null ? null : req.note());
    }

    @DeleteMapping("/organizations/{orgId}/competitors/{operatorId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unwatch(@PathVariable UUID orgId, @PathVariable UUID operatorId) {
        competitors.unwatch(orgId, CurrentUser.id(), operatorId);
    }

    @PostMapping("/admin/market/refresh")
    @Operation(summary = "Przelicz statystyki wyników teraz (PLATFORM_ADMIN)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void refresh() {
        if (!refresh.refresh()) {
            throw ApiException.conflict("refresh_running", "A refresh is already running");
        }
    }
}
