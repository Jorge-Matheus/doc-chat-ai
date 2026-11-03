package com.docchat.ingestion;

import com.docchat.document.DocumentFailureReason;

/** The file itself is the problem, so this is an expected outcome and not a system error. */
class PdfExtractionException extends RuntimeException {

    private final DocumentFailureReason reason;

    PdfExtractionException(DocumentFailureReason reason) {
        super(reason.name());
        this.reason = reason;
    }

    PdfExtractionException(DocumentFailureReason reason, Throwable cause) {
        super(reason.name(), cause);
        this.reason = reason;
    }

    DocumentFailureReason reason() {
        return reason;
    }
}
