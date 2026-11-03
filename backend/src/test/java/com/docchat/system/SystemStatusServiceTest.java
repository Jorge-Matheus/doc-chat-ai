package com.docchat.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.docchat.ai.AiAvailability;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;

class SystemStatusServiceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final AiAvailability aiAvailability = mock(AiAvailability.class);
    private final SystemStatusService service = new SystemStatusService(jdbcTemplate, aiAvailability);

    @Test
    void reportsUpWhenEverythingIsAvailable() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class))).thenReturn(List.of("0.8.7"));
        when(aiAvailability.isAvailable()).thenReturn(true);

        assertThat(service.currentStatus())
                .isEqualTo(new SystemStatus(
                        ComponentStatus.UP, ComponentStatus.UP, ComponentStatus.UP, ComponentStatus.UP));
    }

    @Test
    void reportsDownWhenTheExtensionIsMissing() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class))).thenReturn(List.of());
        when(aiAvailability.isAvailable()).thenReturn(true);

        assertThat(service.currentStatus())
                .isEqualTo(new SystemStatus(
                        ComponentStatus.DOWN, ComponentStatus.UP, ComponentStatus.DOWN, ComponentStatus.UP));
    }

    @Test
    void reportsDownWhenTheDatabaseIsUnreachable() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class)))
                .thenThrow(new CannotGetJdbcConnectionException("connection refused"));
        when(aiAvailability.isAvailable()).thenReturn(true);

        assertThat(service.currentStatus())
                .isEqualTo(new SystemStatus(
                        ComponentStatus.DOWN, ComponentStatus.DOWN, ComponentStatus.UNKNOWN, ComponentStatus.UP));
    }

    @Test
    void reportsDownWhenTheAiServiceIsUnavailable() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class))).thenReturn(List.of("0.8.7"));
        when(aiAvailability.isAvailable()).thenReturn(false);

        assertThat(service.currentStatus())
                .isEqualTo(new SystemStatus(
                        ComponentStatus.DOWN, ComponentStatus.UP, ComponentStatus.UP, ComponentStatus.DOWN));
    }

    @Test
    void checkingTheExtensionDoesNotContactTheAiService() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class))).thenReturn(List.of("0.8.7"));

        assertThat(service.vectorExtensionStatus()).isEqualTo(ComponentStatus.UP);

        verifyNoInteractions(aiAvailability);
    }
}
