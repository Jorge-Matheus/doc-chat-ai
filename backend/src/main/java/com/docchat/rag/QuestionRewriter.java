package com.docchat.rag;

import com.docchat.ai.TextGenerator;
import com.docchat.rag.AnswerService.PreviousTurn;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Turns a follow-up such as "and the penalty?" into a question that can be searched on its own. If
 * the reply does not look like a question, the original is used, because a follow-up that finds
 * nothing is better than a search for something the user did not ask.
 */
@Component
class QuestionRewriter {

    private static final Pattern THINKING = Pattern.compile("<think>.*?</think>", Pattern.DOTALL);
    /** A reply longer than this is an explanation, not a question of at most 2000 characters. */
    private static final int MAX_CHARACTERS = 3000;

    private final TextGenerator generator;

    QuestionRewriter(TextGenerator generator) {
        this.generator = generator;
    }

    /** @param history the most recent turns, oldest first */
    String rewrite(String question, List<PreviousTurn> history) {
        if (history.isEmpty()) {
            return question;
        }
        String reply = generator.generate(RewritePrompt.SYSTEM, RewritePrompt.userMessage(question, history));
        if (reply == null) {
            return question;
        }
        String rewritten = unquoted(THINKING.matcher(reply).replaceAll("").strip());
        boolean usable = !rewritten.isEmpty()
                && rewritten.length() <= MAX_CHARACTERS
                && rewritten.lines().count() == 1
                && !rewritten.contains(AnswerPrompt.NOT_FOUND);
        return usable ? rewritten : question;
    }

    private static String unquoted(String text) {
        boolean quoted = text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"");
        return quoted ? text.substring(1, text.length() - 1).strip() : text;
    }
}
