package com.docchat.rag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the model's raw reply into text with verified citations. An answer that cites no valid
 * source counts as not found: every answer shown to a user must be traceable to a page.
 */
final class AnswerParser {

    private static final Pattern THINKING = Pattern.compile("<think>.*?</think>", Pattern.DOTALL);
    private static final Pattern MARKER = Pattern.compile("\\[(\\d+(?:\\s*,\\s*\\d+)*)]");
    /** Larger numbers in brackets are not citations, such as a year quoted from a document. */
    private static final int LARGEST_POSSIBLE_MARKER = 99;

    private AnswerParser() {}

    /** @param sources the positions of the cited passages in the prompt, starting at 1 */
    record ParsedAnswer(String text, List<Integer> sources) {

        static final ParsedAnswer NOT_FOUND = new ParsedAnswer("", List.of());

        boolean isNotFound() {
            return sources.isEmpty();
        }
    }

    /**
     * Rewrites citations so they count from 1 in reading order, and removes those pointing to
     * passages the model was not given.
     */
    static ParsedAnswer parse(String reply, int sourceCount) {
        if (reply == null) {
            return ParsedAnswer.NOT_FOUND;
        }
        String text = THINKING.matcher(reply).replaceAll("").strip();
        if (text.isEmpty() || text.contains(AnswerPrompt.NOT_FOUND)) {
            return ParsedAnswer.NOT_FOUND;
        }

        Map<Integer, Integer> numberBySource = new LinkedHashMap<>();
        Matcher markers = MARKER.matcher(text);
        StringBuilder rewritten = new StringBuilder();
        while (markers.find()) {
            String replacement = rewriteMarker(markers, sourceCount, numberBySource);
            markers.appendReplacement(rewritten, Matcher.quoteReplacement(replacement));
        }
        markers.appendTail(rewritten);

        if (numberBySource.isEmpty()) {
            return ParsedAnswer.NOT_FOUND;
        }
        return new ParsedAnswer(tidy(rewritten.toString()), new ArrayList<>(numberBySource.keySet()));
    }

    private static String rewriteMarker(Matcher marker, int sourceCount, Map<Integer, Integer> numberBySource) {
        StringBuilder replacement = new StringBuilder();
        for (String part : marker.group(1).split("\\s*,\\s*")) {
            int source = parseNumber(part);
            if (source > LARGEST_POSSIBLE_MARKER) {
                return marker.group();
            }
            if (source >= 1 && source <= sourceCount) {
                int number = numberBySource.computeIfAbsent(source, unused -> numberBySource.size() + 1);
                replacement.append('[').append(number).append(']');
            }
        }
        return replacement.toString();
    }

    private static int parseNumber(String digits) {
        return digits.length() > 6 ? Integer.MAX_VALUE : Integer.parseInt(digits);
    }

    /** Removing a citation can leave a space before punctuation. */
    private static String tidy(String text) {
        return text.replaceAll(" +([.,;:!?])", "$1").replaceAll(" {2,}", " ").strip();
    }
}
