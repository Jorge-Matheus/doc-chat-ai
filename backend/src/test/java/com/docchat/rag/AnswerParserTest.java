package com.docchat.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.docchat.rag.AnswerParser.ParsedAnswer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class AnswerParserTest {

    @Test
    void keepsCitationsThatPointToGivenSources() {
        ParsedAnswer parsed = AnswerParser.parse("The notice is 60 days [1].", 3);

        assertThat(parsed.text()).isEqualTo("The notice is 60 days [1].");
        assertThat(parsed.sources()).containsExactly(1);
    }

    @Test
    void numbersCitationsInReadingOrder() {
        ParsedAnswer parsed = AnswerParser.parse("First [3]. Second [1]. First again [3].", 3);

        assertThat(parsed.text()).isEqualTo("First [1]. Second [2]. First again [1].");
        assertThat(parsed.sources()).containsExactly(3, 1);
    }

    @Test
    void splitsCitationsThatShareBrackets() {
        ParsedAnswer parsed = AnswerParser.parse("Both agree [2, 1].", 2);

        assertThat(parsed.text()).isEqualTo("Both agree [1][2].");
        assertThat(parsed.sources()).containsExactly(2, 1);
    }

    @Test
    void removesCitationsOfSourcesTheModelWasNotGiven() {
        ParsedAnswer parsed = AnswerParser.parse("True [1]. Invented [7].", 2);

        assertThat(parsed.text()).isEqualTo("True [1]. Invented.");
        assertThat(parsed.sources()).containsExactly(1);
    }

    @Test
    void leavesLargeNumbersInBracketsAlone() {
        ParsedAnswer parsed = AnswerParser.parse("The law of [2023] applies [1].", 2);

        assertThat(parsed.text()).isEqualTo("The law of [2023] applies [1].");
        assertThat(parsed.sources()).containsExactly(1);
    }

    @Test
    void survivesAbsurdlyLongNumbers() {
        ParsedAnswer parsed = AnswerParser.parse("Odd [99999999999999999999] but true [1].", 1);

        assertThat(parsed.sources()).containsExactly(1);
    }

    @Test
    void removesAModelsThinking() {
        ParsedAnswer parsed = AnswerParser.parse("<think>\nI recall [2].\n</think>\nThe answer [1].", 2);

        assertThat(parsed.text()).isEqualTo("The answer [1].");
        assertThat(parsed.sources()).containsExactly(1);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(
            strings = {
                "",
                "   ",
                "NOT_FOUND",
                " NOT_FOUND\n",
                "Sorry. NOT_FOUND",
                "An answer that cites nothing.",
                "An answer citing only an invented source [9].",
                "A reply with a zero [0].",
                "<think>Only thinking [1].</think>"
            })
    void treatsRepliesWithoutAVerifiableSourceAsNotFound(String reply) {
        assertThat(AnswerParser.parse(reply, 3).isNotFound()).isTrue();
    }

    @Test
    void handlesSymbolsInTheReplyLiterally() {
        ParsedAnswer parsed = AnswerParser.parse("It costs $50 and C:\\x applies [1].", 1);

        assertThat(parsed.text()).isEqualTo("It costs $50 and C:\\x applies [1].");
    }
}
