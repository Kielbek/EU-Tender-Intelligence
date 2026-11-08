package eu.tenderintel.system;

import eu.tenderintel.config.AppProperties;
import eu.tenderintel.system.SystemStatus.Application;
import eu.tenderintel.system.SystemStatus.IntegrationStatus;
import eu.tenderintel.system.SystemStatus.Readiness;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class SystemStatusService {

    private final AppProperties props;
    private final DependencyProbes probes;
    private final ObjectProvider<BuildProperties> build;
    private final Clock clock;

    SystemStatusService(AppProperties props, DependencyProbes probes, ObjectProvider<BuildProperties> build,
            Clock clock) {
        this.props = props;
        this.probes = probes;
        this.build = build;
        this.clock = clock;
    }

    public SystemStatus status() {
        BuildProperties b = build.getIfAvailable();
        Application app = new Application("EU Tender Intelligence", b != null ? b.getVersion() : "dev",
                props.environment(), props.defaultLocale());
        return new SystemStatus(app, List.of(probes.database(), probes.redis()), integrations(), Instant.now(clock));
    }

    /**
     * Integration readiness. {@code implemented} flags are flipped to {@code true} only in the stage that ships and
     * tests the corresponding client – never before.
     */
    List<IntegrationStatus> integrations() {
        AppProperties.Integrations i = props.integrations();
        return List.of(
                // TED Search API is anonymous – no credentials needed.
                integration("TED_SEARCH_API", true, true, 3),
                integration("OBJECT_STORAGE_S3", false,
                        all(i.s3Endpoint(), i.s3AccessKey(), i.s3SecretKey(), i.s3Bucket()), 5),
                integration("LLM_PROVIDER", false, all(i.llmProvider(), i.llmApiKey()), 5),
                integration("EMAIL_SMTP", true, all(i.smtpHost()), 2),
                integration("STRIPE", false, all(i.stripeSecretKey(), i.stripeWebhookSecret()), 6));
    }

    static IntegrationStatus integration(String code, boolean implemented, boolean configured, int stage) {
        Readiness readiness = !implemented ? Readiness.NOT_IMPLEMENTED
                : configured ? Readiness.READY : Readiness.PENDING_CONFIGURATION;
        return new IntegrationStatus(code, readiness, configured, stage);
    }

    private static boolean all(String... values) {
        for (String v : values) {
            if (!StringUtils.hasText(v)) {
                return false;
            }
        }
        return true;
    }
}
