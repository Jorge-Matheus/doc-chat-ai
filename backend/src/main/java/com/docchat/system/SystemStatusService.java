package com.docchat.system;

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

    public SystemStatusService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public SystemStatus currentStatus() {
        try {
            List<String> versions = jdbcTemplate.queryForList(VECTOR_EXTENSION_QUERY, String.class);
            ComponentStatus vectorExtension =
                    versions.isEmpty() ? ComponentStatus.DOWN : ComponentStatus.UP;
            return SystemStatus.of(ComponentStatus.UP, vectorExtension);
        } catch (DataAccessException ex) {
            // Reporting the failure is this method's purpose. Only the type is logged because
            // the driver's message can contain connection details.
            log.warn("Database status check failed: {}", ex.getClass().getSimpleName());
            return SystemStatus.of(ComponentStatus.DOWN, ComponentStatus.UNKNOWN);
        }
    }
}
