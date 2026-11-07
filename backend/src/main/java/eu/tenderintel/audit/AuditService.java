package eu.tenderintel.audit;

import eu.tenderintel.common.id.Ids;
import eu.tenderintel.common.web.ClientInfo;
import eu.tenderintel.common.web.CorrelationIdFilter;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Append-only audit trail. Runs in its own transaction so that events such as failed logins are recorded even when
 * the surrounding business transaction rolls back. Never put secrets (passwords, tokens) into {@code details}.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    AuditService(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditAction action, UUID actorUserId, UUID organizationId, String targetType, Object targetId,
            Map<String, ?> details) {
        ClientInfo client = ClientInfo.current();
        try {
            jdbc.update("""
                    insert into audit_logs (id, actor_user_id, organization_id, action, target_type, target_id, ip,
                                            user_agent, correlation_id, details)
                    values (?, ?, ?, ?, ?, ?, ?::inet, ?, ?, ?::jsonb)
                    """, Ids.newId(), actorUserId, organizationId, action.name(), targetType,
                    targetId == null ? null : targetId.toString(), client.ip(), client.userAgent(),
                    CorrelationIdFilter.current(), json.writeValueAsString(details == null ? Map.of() : details));
        } catch (RuntimeException ex) {
            // Auditing must not break the user-facing operation, but a failure is an operational alert.
            log.error("Failed to write audit event {}", action, ex);
        }
    }

    public void record(AuditAction action, UUID actorUserId) {
        record(action, actorUserId, null, null, null, Map.of());
    }
}
