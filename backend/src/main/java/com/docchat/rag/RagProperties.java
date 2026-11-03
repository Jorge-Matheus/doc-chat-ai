package com.docchat.rag;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param topK how many passages the model reads
 * @param minSimilarity passages less similar than this to the question, from 0 to 1, are ignored
 */
@Validated
@ConfigurationProperties("docchat.rag")
record RagProperties(@Min(1) @Max(20) int topK, double minSimilarity) {}
