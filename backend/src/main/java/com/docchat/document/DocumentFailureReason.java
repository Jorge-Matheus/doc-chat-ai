package com.docchat.document;

/** Part of the API contract: the frontend shows a message for each value. */
public enum DocumentFailureReason {
    /** The file is damaged or is not really a PDF. */
    NOT_READABLE,
    /** The file needs a password or forbids text extraction. */
    PROTECTED,
    /** No page has text, as in a scanned PDF. */
    NO_EXTRACTABLE_TEXT,
    TOO_MANY_PAGES,
    /** The processing queue was full. */
    PROCESSING_UNAVAILABLE,
    INTERNAL_ERROR
}
