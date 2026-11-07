package eu.tenderintel.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Background schedules (e-mail outbox, ECB rates). Can be disabled per instance with {@code APP_SCHEDULING_ENABLED=false},
 * e.g. on API-only replicas. Scheduled jobs are idempotent, so running them on several instances is safe.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
