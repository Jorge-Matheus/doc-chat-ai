package com.docchat.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

class PgvectorHealthIndicatorTest {

    private final SystemStatusService service = mock(SystemStatusService.class);
    private final PgvectorHealthIndicator indicator = new PgvectorHealthIndicator(service);

    @Test
    void isUpWhenTheExtensionIsAvailable() {
        when(service.currentStatus()).thenReturn(SystemStatus.of(ComponentStatus.UP, ComponentStatus.UP));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void isDownWhenTheExtensionIsMissing() {
        when(service.currentStatus()).thenReturn(SystemStatus.of(ComponentStatus.UP, ComponentStatus.DOWN));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void isDownWhenTheExtensionCannotBeChecked() {
        when(service.currentStatus()).thenReturn(SystemStatus.of(ComponentStatus.DOWN, ComponentStatus.UNKNOWN));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }
}
