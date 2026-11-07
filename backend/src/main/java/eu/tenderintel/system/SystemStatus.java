package eu.tenderintel.system;

import java.time.Instant;
import java.util.List;

/** Public, non-sensitive snapshot of platform health used by the frontend status screen and uptime checks. */
public record SystemStatus(
        Application application,
        List<DependencyStatus> dependencies,
        List<IntegrationStatus> integrations,
        Instant checkedAt) {

    public record Application(String name, String version, String environment, String defaultLocale) {
    }

    public enum Health { UP, DOWN }

    public record DependencyStatus(String name, Health status, String detail) {
    }

    /**
     * {@code READY} – implemented and configured; {@code PENDING_CONFIGURATION} – implemented, credentials missing;
     * {@code NOT_IMPLEMENTED} – planned for {@code plannedStage}, not available yet.
     */
    public enum Readiness { READY, PENDING_CONFIGURATION, NOT_IMPLEMENTED }

    public record IntegrationStatus(String code, Readiness status, boolean configured, int plannedStage) {
    }
}
