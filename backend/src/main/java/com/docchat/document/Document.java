package com.docchat.document;

import java.time.Instant;
import java.util.UUID;

public record Document(
        UUID id,
        UUID ownerId,
        String filename,
        long sizeBytes,
        DocumentStatus status,
        DocumentFailureReason failureReason,
        Integer pageCount,
        Instant createdAt) {

    /** The file name on disk comes from the id, never from the name the client sent. */
    public static String storageKey(UUID documentId) {
        return documentId + ".pdf";
    }
}
