package com.docchat.chat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A question and its answer, in the form the API returns them.
 *
 * @param interpretedQuestion what was searched for, or null if that is the question as it was asked
 * @param answered false when the documents did not contain the answer
 * @param text null when not answered
 */
record Turn(
        long id,
        String question,
        String interpretedQuestion,
        boolean answered,
        String text,
        List<TurnCitation> citations,
        Instant createdAt) {

    /**
     * @param documentId null once the document has been deleted
     * @param excerpt null once the document has been deleted
     */
    record TurnCitation(int number, UUID documentId, String filename, int pageNumber, String excerpt) {}
}
