package eu.tenderintel.system;

import eu.tenderintel.config.AppProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public facts for the terms of service and privacy policy pages (who runs the service, which processors are used). */
@RestController
@Tag(name = "System", description = "Status i informacje publiczne")
class LegalInfoController {

    private final AppProperties props;

    LegalInfoController(AppProperties props) {
        this.props = props;
    }

    /** {@code code} is translated in the UI; {@code name} is the provider's name when it is configured. */
    public record Processor(String code, String name) {
    }

    public record LegalInfo(boolean configured, String operatorName, String operatorAddress, String operatorTaxId,
            String operatorEmail, String termsVersion, List<Processor> processors) {
    }

    @GetMapping("/api/v1/system/legal")
    @Operation(summary = "Dane operatora i lista podmiotów przetwarzających (do regulaminu i polityki prywatności)")
    LegalInfo legal() {
        AppProperties.Legal l = props.legal();
        List<Processor> processors = new ArrayList<>();
        processors.add(new Processor("HOSTING", blankToNull(l.hostingProvider())));
        processors.add(new Processor("EMAIL", blankToNull(l.emailProvider())));
        if (props.ai().configured()) {
            processors.add(new Processor("AI", "Anthropic PBC"));
        }
        if (props.stripe().configured()) {
            // Stripe processes payment and invoicing data as an independent controller for card data.
            processors.add(new Processor("PAYMENTS", "Stripe Payments Europe, Ltd."));
        }
        return new LegalInfo(l.configured(), blankToNull(l.operatorName()), blankToNull(l.operatorAddress()),
                blankToNull(l.operatorTaxId()), blankToNull(l.operatorEmail()), props.termsVersion(), processors);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
