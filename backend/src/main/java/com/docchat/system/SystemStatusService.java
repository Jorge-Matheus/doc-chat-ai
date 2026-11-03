package com.docchat.system;

import com.docchat.ai.AiAvailability;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SystemStatusService {

    private static final Logger log = LoggerFactory.getLogger(SystemStatusService.class);

    private static final String VECTOR_EXTENSION_QUERY =
            "SELECT extversion FROM pg_extension WHERE extname = 'vector'";

    private final JdbcTemplate jdbcTemplate;
    private final AiAvailability aiAvailability;

    public SystemStatusService(JdbcTemplate jdbcTemplate, AiAvailability aiAvailability) {
        this.jdbcTemplate = jdbcTemplate;
        this.aiAvailability = aiAvailability;
    }

    public SystemStatus currentStatus() {
        DatabaseState databaseState = checkDatabase();
        ComponentStatus ai = aiAvailability.isAvailable() ? ComponentStatus.UP : ComponentStatus.DOWN;
        return SystemStatus.of(databaseState.database(), databaseState.vectorExtension(), ai);
    }

    /** Does not check the AI service, which readiness deliberately does not depend on. */
    public ComponentStatus vectorExtensionStatus() {
        return checkDatabase().vectorExtension();
    }

    private DatabaseState checkDatabase() {
        try {
            List<String> versions = jdbcTemplate.queryForList(VECTOR_EXTENSION_QUERY, String.class);
            ComponentStatus vectorExtension =
                    versions.isEmpty() ? ComponentStatus.DOWN : ComponentStatus.UP;
            return new DatabaseState(ComponentStatus.UP, vectorExtension);
        } catch (DataAccessException ex) {
            // Reporting the failure is this method's purpose. Only the type is logged because
            // the driver's message can contain connection details.
            log.warn("Database status check failed: {}", ex.getClass().getSimpleName());
            return new DatabaseState(ComponentStatus.DOWN, ComponentStatus.UNKNOWN);
        }
    }

    private record DatabaseState(ComponentStatus database, ComponentStatus vectorExtension) {}
}
