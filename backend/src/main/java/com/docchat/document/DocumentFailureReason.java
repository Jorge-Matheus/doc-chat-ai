package com.docchat.document;

import java.util.List;
import java.util.stream.Stream;

/** Part of the API contract: the frontend shows a message for each value. */
public enum DocumentFailureReason {
    /** The file is damaged or is not really a PDF. */
    NOT_READABLE(false),
    /** The file needs a password or forbids text extraction. */
    PROTECTED(false),
    /** No page has text, as in a scanned PDF. */
    NO_EXTRACTABLE_TEXT(false),
    TOO_MANY_PAGES(false),
    /** The processing queue was full. */
    PROCESSING_UNAVAILABLE(true),
    /** The embedding model could not be reached. */
    EMBEDDING_UNAVAILABLE(true),
    /** Processing passed the time limit. */
    PROCESSING_TIMED_OUT(true),
    INTERNAL_ERROR(true);

    private final boolean temporary;

    DocumentFailureReason(boolean temporary) {
        this.temporary = temporary;
    }

    /** The file itself is not the problem, so trying again can work. */
    public boolean isTemporary() {
        return temporary;
    }

    static List<String> temporaryNames() {
        return Stream.of(values()).filter(DocumentFailureReason::isTemporary).map(Enum::name).toList();
    }
}
