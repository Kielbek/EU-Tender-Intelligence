package eu.tenderintel.company;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class CompanyDtos {

    private CompanyDtos() {
    }

    public record CompanyProfileView(UUID id, String name, String registrationCountry, String industry,
            String description, String website, List<String> countriesOfInterest, List<String> contractTypes,
            String companySize, Integer employees, BigDecimal annualRevenue, String revenueCurrency,
            Integer yearsOfExperience, BigDecimal minContractValueEur, BigDecimal maxContractValueEur,
            String technicalResources, String staffResources, String financialCapacity, List<String> cpvCodes,
            Map<String, String> cpvLabels, List<CertificationView> certifications, List<ReferenceView> references,
            Instant updatedAt) {
    }

    public record CompanyProfileRequest(
            @NotBlank @Size(max = 200) String name,
            @Pattern(regexp = "^[A-Z]{2}$") String registrationCountry,
            @Size(max = 200) String industry,
            @Size(max = 5000) String description,
            @Size(max = 300) @Pattern(regexp = "^$|^https?://.+") String website,
            @NotNull @Size(max = 60) List<@Pattern(regexp = "^[A-Z]{2}$") String> countriesOfInterest,
            @NotNull @Size(max = 3) List<@Pattern(regexp = "^(WORKS|SUPPLIES|SERVICES)$") String> contractTypes,
            @Pattern(regexp = "^(MICRO|SMALL|MEDIUM|LARGE)$") String companySize,
            @Min(0) @Max(10_000_000) Integer employees,
            @DecimalMin("0") BigDecimal annualRevenue,
            @Pattern(regexp = "^[A-Z]{3}$") String revenueCurrency,
            @Min(0) @Max(300) Integer yearsOfExperience,
            @DecimalMin("0") BigDecimal minContractValueEur,
            @DecimalMin("0") BigDecimal maxContractValueEur,
            @Size(max = 5000) String technicalResources,
            @Size(max = 5000) String staffResources,
            @Size(max = 5000) String financialCapacity,
            @NotNull @Size(max = 50) List<@Pattern(regexp = "^\\d{8}$") String> cpvCodes) {
    }

    public record CertificationView(UUID id, String name, String issuer, LocalDate validUntil) {
    }

    public record CertificationRequest(@NotBlank @Size(max = 200) String name, @Size(max = 200) String issuer,
            LocalDate validUntil) {
    }

    public record ReferenceView(UUID id, String title, String client, String countryCode, BigDecimal value,
            String currency, Integer yearCompleted, String cpvCode, String description) {
    }

    public record ReferenceRequest(
            @NotBlank @Size(max = 300) String title,
            @Size(max = 200) String client,
            @Pattern(regexp = "^[A-Z]{2}$") String countryCode,
            @DecimalMin("0") BigDecimal value,
            @Pattern(regexp = "^[A-Z]{3}$") String currency,
            @Min(1950) @Max(2100) Integer yearCompleted,
            @Pattern(regexp = "^\\d{8}$") String cpvCode,
            @Size(max = 2000) String description) {
    }
}
