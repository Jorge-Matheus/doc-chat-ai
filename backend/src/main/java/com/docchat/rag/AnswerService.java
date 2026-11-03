package com.docchat.rag;

import com.docchat.ai.TextEmbedder;
import com.docchat.ai.TextGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/** Answers a question from the user's own documents, citing the pages it used. */
@Service
@EnableConfigurationProperties(RagProperties.class)
public class AnswerService {

    /** How many earlier turns are enough to understand a follow-up question. */
    public static final int HISTORY_TURNS = 3;

    private final TextEmbedder embedder;
    private final TextGenerator generator;
    private final QuestionRewriter rewriter;
    private final ChunkSearchRepository chunkSearch;
    private final RagProperties properties;

    AnswerService(
            TextEmbedder embedder,
            TextGenerator generator,
            QuestionRewriter rewriter,
            ChunkSearchRepository chunkSearch,
            RagProperties properties) {
        this.embedder = embedder;
        this.generator = generator;
        this.rewriter = rewriter;
        this.chunkSearch = chunkSearch;
        this.properties = properties;
    }

    /** @param answer null when the documents did not contain the answer */
    public record PreviousTurn(String question, String answer) {}

    /**
     * @param interpretedQuestion what was searched for, or null if that is the question as it was asked
     * @param text null when the documents do not contain the answer
     */
    public record Answer(String interpretedQuestion, String text, List<Citation> citations) {

        private static Answer notFound(String interpretedQuestion) {
            return new Answer(interpretedQuestion, null, List.of());
        }
    }

    public record Citation(int number, UUID documentId, String filename, int pageNumber, int chunkIndex) {}

    /** @param history the most recent turns of the conversation, oldest first */
    public Answer answer(UUID ownerId, String question, List<UUID> documentIds, List<PreviousTurn> history) {
        String standalone = rewriter.rewrite(question, history);
        String interpreted = standalone.equals(question) ? null : standalone;

        float[] questionEmbedding = embedder.embed(standalone);
        List<RetrievedChunk> chunks =
                chunkSearch.findClosest(ownerId, documentIds, questionEmbedding, properties.topK()).stream()
                        .filter(chunk -> chunk.similarity() >= properties.minSimilarity())
                        .toList();
        // Without relevant passages there is nothing to ground an answer on, so the model is not asked.
        if (chunks.isEmpty()) {
            return Answer.notFound(interpreted);
        }

        String reply = generator.generate(AnswerPrompt.SYSTEM, AnswerPrompt.userMessage(standalone, chunks));
        AnswerParser.ParsedAnswer parsed = AnswerParser.parse(reply, chunks.size());
        if (parsed.isNotFound()) {
            return Answer.notFound(interpreted);
        }

        List<Citation> citations = new ArrayList<>();
        for (int source : parsed.sources()) {
            RetrievedChunk chunk = chunks.get(source - 1);
            citations.add(new Citation(
                    citations.size() + 1,
                    chunk.documentId(),
                    chunk.filename(),
                    chunk.pageNumber(),
                    chunk.chunkIndex()));
        }
        return new Answer(interpreted, parsed.text(), citations);
    }
}
