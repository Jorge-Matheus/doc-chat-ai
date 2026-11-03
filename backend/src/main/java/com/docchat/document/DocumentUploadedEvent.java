package com.docchat.document;

import java.util.UUID;

/** Lets the ingestion module react to uploads without this module depending on it. */
public record DocumentUploadedEvent(UUID documentId) {}
