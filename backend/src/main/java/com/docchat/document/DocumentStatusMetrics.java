package com.docchat.document;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * How many documents are in each state. The count runs on a schedule and not when the metrics are
 * read, so reading them never waits for the database, which is when they are most useful.
 */
@Component
class DocumentStatusMetrics {

    private static final Logger log = LoggerFactory.getLogger(DocumentStatusMetrics.class);

    private final DocumentRepository repository;
    private final Map<DocumentStatus, AtomicLong> counts = new EnumMap<>(DocumentStatus.class);

    DocumentStatusMetrics(DocumentRepository repository, MeterRegistry registry) {
        this.repository = repository;
        for (DocumentStatus status : DocumentStatus.values()) {
            counts.put(
                    status,
                    registry.gauge(
                            "docchat.documents",
                            Tags.of("status", status.name().toLowerCase(Locale.ROOT)),
                            new AtomicLong()));
        }
    }

    @Scheduled(fixedDelayString = "${docchat.documents.metrics-interval}")
    void refresh() {
        try {
            Map<DocumentStatus, Long> current = repository.countByStatus();
            counts.forEach((status, count) -> count.set(current.getOrDefault(status, 0L)));
        } catch (DataAccessException ex) {
            // The last known counts stay, and the database's own state is reported elsewhere.
            log.debug("Could not count documents: {}", ex.getClass().getSimpleName());
        }
    }
}
