package eu.tenderintel.reference;

import eu.tenderintel.reference.ReferenceDtos.CountryView;
import eu.tenderintel.reference.ReferenceDtos.CpvDetailView;
import eu.tenderintel.reference.ReferenceDtos.CpvView;
import eu.tenderintel.reference.ReferenceDtos.CurrencyView;
import eu.tenderintel.reference.ReferenceDtos.ExchangeRateView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Reference data", description = "Kraje, waluty, kursy EBC, słownik CPV (publiczne)")
class ReferenceController {

    private final ReferenceQueries queries;

    ReferenceController(ReferenceQueries queries) {
        this.queries = queries;
    }

    @GetMapping("/countries")
    ResponseEntity<List<CountryView>> countries() {
        return cached(queries.countries(), Duration.ofHours(24));
    }

    @GetMapping("/currencies")
    ResponseEntity<List<CurrencyView>> currencies() {
        return cached(queries.currencies(), Duration.ofHours(24));
    }

    @GetMapping("/currencies/rates/latest")
    @Operation(summary = "Najnowsze kursy referencyjne EBC (jednostek waluty za 1 EUR)")
    ResponseEntity<List<ExchangeRateView>> latestRates() {
        return cached(queries.latestRates(), Duration.ofHours(1));
    }

    @GetMapping("/cpv")
    @Operation(summary = "Wyszukiwanie kodów CPV",
            description = "query: prefiks kodu (cyfry) lub fragment nazwy; parent: dzieci danego kodu; "
                    + "bez parametrów – działy (poziom 1). lang: kod języka UE (domyślnie en).")
    ResponseEntity<List<CpvView>> searchCpv(@RequestParam(required = false) String query,
            @RequestParam(required = false) @Pattern(regexp = "^\\d{8}$") String parent,
            @RequestParam(defaultValue = "en") String lang,
            @RequestParam(defaultValue = "20") int limit) {
        return cached(queries.searchCpv(query, parent, lang, limit), Duration.ofMinutes(10));
    }

    @GetMapping("/cpv/{code}")
    ResponseEntity<CpvDetailView> cpv(@PathVariable @Pattern(regexp = "^\\d{8}$") String code,
            @RequestParam(defaultValue = "en") String lang) {
        return cached(queries.cpv(code, lang), Duration.ofHours(1));
    }

    private static <T> ResponseEntity<T> cached(T body, Duration maxAge) {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(maxAge).cachePublic()).body(body);
    }
}
