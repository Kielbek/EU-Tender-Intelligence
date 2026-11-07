package eu.tenderintel.organization;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "organizations")
public class Organization {

    @Id
    private UUID id;
    private String name;
    private String slug;
    private String countryCode;
    private String vatId;
    private String billingEmail;
    private String planCode = "FREE";
    private String status = "ACTIVE";
    @Column(insertable = false, updatable = false)
    private Instant createdAt;
    private Instant updatedAt;
    @Version
    private int version;

    protected Organization() {
    }

    Organization(UUID id, String name, String slug, Instant now) {
        this.id = id;
        this.name = name;
        this.slug = slug;
        this.updatedAt = now;
    }

    void update(String name, String countryCode, String vatId, String billingEmail, Instant now) {
        if (name != null) {
            this.name = name;
        }
        if (countryCode != null) {
            this.countryCode = countryCode.isBlank() ? null : countryCode;
        }
        if (vatId != null) {
            this.vatId = vatId.isBlank() ? null : vatId;
        }
        if (billingEmail != null) {
            this.billingEmail = billingEmail.isBlank() ? null : billingEmail;
        }
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getSlug() { return slug; }
    public String getCountryCode() { return countryCode; }
    public String getVatId() { return vatId; }
    public String getBillingEmail() { return billingEmail; }
    public String getPlanCode() { return planCode; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
