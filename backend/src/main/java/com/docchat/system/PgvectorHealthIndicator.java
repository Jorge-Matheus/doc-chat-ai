package com.docchat.system;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Publishes the pgvector check as the {@code pgvector} health component. The readiness group
 * includes it, because the application cannot serve semantic search without the extension.
 */
@Component
class PgvectorHealthIndicator implements HealthIndicator {

    private final SystemStatusService systemStatusService;

    PgvectorHealthIndicator(SystemStatusService systemStatusService) {
        this.systemStatusService = systemStatusService;
    }

    @Override
    public Health health() {
        return systemStatusService.vectorExtensionStatus() == ComponentStatus.UP
                ? Health.up().build()
                : Health.down().build();
    }
}
