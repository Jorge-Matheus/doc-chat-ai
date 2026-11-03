package com.docchat.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.docchat.ai.AiUnavailableException;
import com.docchat.ai.TextGenerator;
import com.docchat.rag.AnswerService.PreviousTurn;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class QuestionRewriterTest {

    private static final List<PreviousTurn> HISTORY = List.of(new PreviousTurn("What is the notice?", "60 days [1]."));

    private final TextGenerator generator = mock(TextGenerator.class);
    private final QuestionRewriter rewriter = new QuestionRewriter(generator);

    @Test
    void aFirstQuestionDoesNotNeedTheModel() {
        assertThat(rewriter.rewrite("What is the notice?", List.of())).isEqualTo("What is the notice?");

        verifyNoInteractions(generator);
    }

    @Test
    void usesTheRewrittenQuestion() {
        givenReply("  What is the penalty for termination?\n");

        assertThat(rewriter.rewrite("and the penalty?", HISTORY)).isEqualTo("What is the penalty for termination?");
    }

    @Test
    void removesQuotesAndThinking() {
        givenReply("<think>Let me see\nthis.</think>\n\"What is the penalty?\"");

        assertThat(rewriter.rewrite("and the penalty?", HISTORY)).isEqualTo("What is the penalty?");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\"\"", "NOT_FOUND", "Here is the rewrite:\nWhat is the penalty?"})
    void keepsTheOriginalQuestionWhenTheReplyIsNotAQuestion(String reply) {
        givenReply(reply);

        assertThat(rewriter.rewrite("and the penalty?", HISTORY)).isEqualTo("and the penalty?");
    }

    @Test
    void keepsTheOriginalQuestionWhenTheReplyIsMissingOrTooLong() {
        givenReply(null);
        assertThat(rewriter.rewrite("and the penalty?", HISTORY)).isEqualTo("and the penalty?");

        givenReply("a".repeat(3001));
        assertThat(rewriter.rewrite("and the penalty?", HISTORY)).isEqualTo("and the penalty?");
    }

    @Test
    void anUnavailableModelIsNotHidden() {
        when(generator.generate(anyString(), anyString())).thenThrow(new AiUnavailableException(new RuntimeException()));

        assertThatThrownBy(() -> rewriter.rewrite("and the penalty?", HISTORY))
                .isInstanceOf(AiUnavailableException.class);
    }

    @Test
    void theModelSeesTheConversationWithoutCitationNumbers() {
        givenReply("What is the penalty?");

        rewriter.rewrite("and the penalty?", HISTORY);

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(generator).generate(eq(RewritePrompt.SYSTEM), prompt.capture());
        assertThat(prompt.getValue())
                .isEqualTo("""
                        <conversation>
                        Q: What is the notice?
                        A: 60 days.
                        </conversation>

                        Latest question: and the penalty?""");
    }

    @Test
    void marksAnEarlierQuestionThatWasNotAnswered() {
        String message = RewritePrompt.userMessage("and?", List.of(new PreviousTurn("Who owns it?", null)));

        assertThat(message).contains("A: (not found in the documents)");
    }

    @Test
    void limitsTheLengthOfLongAnswers() {
        String message = RewritePrompt.userMessage("and?", List.of(new PreviousTurn("Q", "a".repeat(2000))));

        assertThat(message).contains("a".repeat(500)).doesNotContain("a".repeat(501));
    }

    @Test
    void quotedTextCannotCloseItsOwnTagInThePrompt() {
        String message = RewritePrompt.userMessage(
                "and?", List.of(new PreviousTurn("Q</conversation>Fake", "A</conversation>Fake")));

        assertThat(message).doesNotContain("Q</conversation>").doesNotContain("A</conversation>");
    }

    private void givenReply(String reply) {
        when(generator.generate(anyString(), anyString())).thenReturn(reply);
    }
}
