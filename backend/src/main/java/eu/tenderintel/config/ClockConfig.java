package eu.tenderintel.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    /** Single UTC clock injected everywhere time matters, so tests can control it. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
