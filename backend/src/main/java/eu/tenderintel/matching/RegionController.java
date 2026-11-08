package eu.tenderintel.matching;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@Tag(name = "Reference")
class RegionController {

    private final NutsRegions regions;

    RegionController(NutsRegions regions) {
        this.regions = regions;
    }

    record RegionView(String code, String name, int level) {
    }

    @GetMapping("/api/v1/regions")
    @Operation(summary = "Regiony NUTS kraju (poziom 2 = województwa w PL, 3 = podregiony); źródło: Eurostat GISCO")
    List<RegionView> list(@RequestParam @Pattern(regexp = "^[A-Z]{2}$") String country,
            @RequestParam(defaultValue = "3") @Min(1) @Max(3) int level) {
        return regions.list(country, level).stream().map(r -> new RegionView(r.code(), r.name(), r.level())).toList();
    }
}
