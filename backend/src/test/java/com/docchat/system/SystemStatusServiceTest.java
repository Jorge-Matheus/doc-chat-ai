package com.docchat.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;

class SystemStatusServiceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final SystemStatusService service = new SystemStatusService(jdbcTemplate);

    @Test
    void reportsUpWhenTheExtensionIsInstalled() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class))).thenReturn(List.of("0.8.7"));

        assertThat(service.currentStatus())
                .isEqualTo(new SystemStatus(ComponentStatus.UP, ComponentStatus.UP, ComponentStatus.UP));
    }

    @Test
    void reportsDownWhenTheExtensionIsMissing() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class))).thenReturn(List.of());

        assertThat(service.currentStatus())
                .isEqualTo(new SystemStatus(ComponentStatus.DOWN, ComponentStatus.UP, ComponentStatus.DOWN));
    }

    @Test
    void reportsDownWhenTheDatabaseIsUnreachable() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class)))
                .thenThrow(new CannotGetJdbcConnectionException("connection refused"));

        assertThat(service.currentStatus())
                .isEqualTo(new SystemStatus(ComponentStatus.DOWN, ComponentStatus.DOWN, ComponentStatus.UNKNOWN));
    }
}
