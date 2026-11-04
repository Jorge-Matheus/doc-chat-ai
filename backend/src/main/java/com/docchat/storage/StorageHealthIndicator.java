package com.docchat.storage;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** Publishes the {@code storage} health component, which the readiness group includes. */
@Component
class StorageHealthIndicator implements HealthIndicator {

    private final FileStorage fileStorage;

    StorageHealthIndicator(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    @Override
    public Health health() {
        return fileStorage.isAvailable() ? Health.up().build() : Health.down().build();
    }
}
