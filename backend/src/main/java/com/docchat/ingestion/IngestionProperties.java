package com.docchat.ingestion;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param workers documents processed at the same time
 * @param queueCapacity documents that can wait for a worker
 * @param maxPages largest document accepted
 * @param maxProcessingTime how long one document can take, from when a worker starts on it
 */
@Validated
@ConfigurationProperties("docchat.ingestion")
record IngestionProperties(
        @Min(1) int workers, @Min(0) int queueCapacity, @Min(1) int maxPages, @NotNull Duration maxProcessingTime) {}
