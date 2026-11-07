package eu.tenderintel.system;

import eu.tenderintel.system.SystemStatus.DependencyStatus;
import eu.tenderintel.system.SystemStatus.Health;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Lightweight checks of infrastructure dependencies. Failures are logged with details server-side; the API response
 * only carries a generic message.
 */
@Component
class DependencyProbes {

    private static final Logger log = LoggerFactory.getLogger(DependencyProbes.class);

    private final JdbcTemplate jdbc;
    private final RedisConnectionFactory redis;

    DependencyProbes(JdbcTemplate jdbc, RedisConnectionFactory redis) {
        this.jdbc = jdbc;
        this.redis = redis;
    }

    DependencyStatus database() {
        try {
            String pgvector = jdbc.query("select extversion from pg_extension where extname = 'vector'",
                    rs -> rs.next() ? rs.getString(1) : null);
            Integer euCountries = jdbc.queryForObject("select count(*) from countries where is_eu", Integer.class);
            String detail = "pgvector " + (pgvector != null ? pgvector : "missing") + ", EU countries: " + euCountries;
            return new DependencyStatus("postgresql", pgvector != null ? Health.UP : Health.DOWN, detail);
        } catch (RuntimeException ex) {
            log.warn("Database probe failed", ex);
            return new DependencyStatus("postgresql", Health.DOWN, "unreachable");
        }
    }

    DependencyStatus redis() {
        try (RedisConnection connection = redis.getConnection()) {
            String pong = connection.ping();
            return new DependencyStatus("redis", "PONG".equalsIgnoreCase(pong) ? Health.UP : Health.DOWN, null);
        } catch (RuntimeException ex) {
            log.warn("Redis probe failed", ex);
            return new DependencyStatus("redis", Health.DOWN, "unreachable");
        }
    }
}
