package com.docchat.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.docchat.ingestion.TextChunker.PageChunk;
import java.util.List;
import org.junit.jupiter.api.Test;

class TextChunkerTest {

    @Test
    void keepsAShortPageAsOneChunkWithItsPageNumber() {
        assertThat(TextChunker.split(List.of("Short page.")))
                .containsExactly(new PageChunk(1, 0, "Short page."));
    }

    @Test
    void skipsBlankPagesWithoutShiftingPageNumbers() {
        assertThat(TextChunker.split(List.of("First.", "   ", "", "Fourth.")))
                .containsExactly(new PageChunk(1, 0, "First."), new PageChunk(4, 0, "Fourth."));
    }

    @Test
    void producesNothingWithoutText() {
        assertThat(TextChunker.split(List.of())).isEmpty();
        assertThat(TextChunker.split(List.of("", " \n "))).isEmpty();
    }

    @Test
    void neverExceedsTheLimit() {
        List<PageChunk> chunks = TextChunker.split(List.of("Some sentence here. ".repeat(500)));

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.text().length()).isLessThanOrEqualTo(TextChunker.MAX_CHARACTERS));
    }

    @Test
    void numbersChunksWithinEachPage() {
        String longPage = "Some sentence here. ".repeat(200);

        List<PageChunk> chunks = TextChunker.split(List.of(longPage, longPage));

        assertThat(chunks.stream().filter(chunk -> chunk.pageNumber() == 2).map(PageChunk::chunkIndex).toList())
                .startsWith(0, 1, 2);
    }

    @Test
    void endsChunksAtSentencesWhenPossible() {
        List<PageChunk> chunks = TextChunker.split(List.of("Some sentence here. ".repeat(200)));

        assertThat(chunks.getFirst().text()).endsWith(".");
    }

    @Test
    void consecutiveChunksOverlap() {
        List<PageChunk> chunks = TextChunker.split(List.of("Some sentence here. ".repeat(200)));

        String endOfFirst = chunks.get(0).text().substring(chunks.get(0).text().length() - 40);
        assertThat(chunks.get(1).text()).contains(endOfFirst);
    }

    @Test
    void losesNoWords() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            text.append("word").append(i).append(i % 12 == 11 ? ". " : " ");
        }

        String joined = String.join(" ", TextChunker.split(List.of(text.toString())).stream().map(PageChunk::text).toList());

        for (int i = 0; i < 2000; i++) {
            assertThat(joined).contains("word" + i);
        }
    }

    @Test
    void cutsTextWithoutSpacesAndStillAdvances() {
        List<PageChunk> chunks = TextChunker.split(List.of("x".repeat(5000)));

        assertThat(chunks).hasSizeGreaterThan(4);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.text().length()).isLessThanOrEqualTo(TextChunker.MAX_CHARACTERS));
    }

    @Test
    void doesNotSplitACharacterOutsideTheBasicPlane() {
        List<PageChunk> chunks = TextChunker.split(List.of("😀".repeat(3000)));

        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.text()).doesNotContainPattern("\\p{Cs}"));
    }
}
