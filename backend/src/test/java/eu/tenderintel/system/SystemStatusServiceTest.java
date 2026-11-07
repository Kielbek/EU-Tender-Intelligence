package eu.tenderintel.system;

import static org.assertj.core.api.Assertions.assertThat;

import eu.tenderintel.system.SystemStatus.Readiness;
import org.junit.jupiter.api.Test;

class SystemStatusServiceTest {

    @Test
    void notImplementedIntegrationIsNeverReportedAsReady() {
        assertThat(SystemStatusService.integration("X", false, true, 3).status()).isEqualTo(Readiness.NOT_IMPLEMENTED);
    }

    @Test
    void implementedButUnconfiguredIntegrationIsPendingConfiguration() {
        assertThat(SystemStatusService.integration("X", true, false, 3).status())
                .isEqualTo(Readiness.PENDING_CONFIGURATION);
    }

    @Test
    void implementedAndConfiguredIntegrationIsReady() {
        assertThat(SystemStatusService.integration("X", true, true, 3).status()).isEqualTo(Readiness.READY);
    }
}
