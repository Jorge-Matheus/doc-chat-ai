package com.docchat.ingestion;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits pages into overlapping passages small enough for precise retrieval. Passages never cross
 * a page, so each one belongs to exactly one page number. The cost is that a sentence spanning two
 * pages is cut in two.
 */
final class TextChunker {

    static final int MAX_CHARACTERS = 1000;
    static final int OVERLAP_CHARACTERS = 150;

    private TextChunker() {}

    record PageChunk(int pageNumber, int chunkIndex, String text) {}

    /** @param pages the text of each page, in order, where index 0 is page 1 */
    static List<PageChunk> split(List<String> pages) {
        List<PageChunk> chunks = new ArrayList<>();
        for (int page = 0; page < pages.size(); page++) {
            splitPage(page + 1, pages.get(page).strip(), chunks);
        }
        return chunks;
    }

    private static void splitPage(int pageNumber, String text, List<PageChunk> chunks) {
        int start = 0;
        int chunkIndex = 0;
        while (start < text.length()) {
            int end = endOfChunk(text, start);
            String chunk = text.substring(start, end).strip();
            if (!chunk.isEmpty()) {
                chunks.add(new PageChunk(pageNumber, chunkIndex++, chunk));
            }
            if (end == text.length()) {
                return;
            }
            start = startOfNextChunk(text, start, end);
        }
    }

    /** Ends at a sentence if possible, then at a word, and only cuts a word as a last resort. */
    private static int endOfChunk(String text, int start) {
        int limit = start + MAX_CHARACTERS;
        if (limit >= text.length()) {
            return text.length();
        }
        int searchFrom = start + MAX_CHARACTERS / 2;
        for (int i = limit; i > searchFrom; i--) {
            if (isSentenceEnd(text.charAt(i - 1)) && Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        for (int i = limit; i > searchFrom; i--) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return Character.isHighSurrogate(text.charAt(limit - 1)) ? limit - 1 : limit;
    }

    private static boolean isSentenceEnd(char character) {
        return character == '.' || character == '!' || character == '?' || character == ';' || character == ':';
    }

    /** Goes back by the overlap, then forward to the start of a word, and always advances. */
    private static int startOfNextChunk(String text, int start, int end) {
        int next = Math.max(end - OVERLAP_CHARACTERS, start + 1);
        while (next < end && !Character.isWhitespace(text.charAt(next))) {
            next++;
        }
        while (next < text.length() && Character.isWhitespace(text.charAt(next))) {
            next++;
        }
        return next;
    }
}
