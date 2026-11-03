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
class AnswerService {

    private final TextEmbedder embedder;
    private final TextGenerator generator;
    private final ChunkSearchRepository chunkSearch;
    private final RagProperties properties;

    AnswerService(
            TextEmbedder embedder,
            TextGenerator generator,
            ChunkSearchRepository chunkSearch,
            RagProperties properties) {
        this.embedder = embedder;
        this.generator = generator;
        this.chunkSearch = chunkSearch;
        this.properties = properties;
    }

    record Answer(boolean answered, String text, List<Citation> citations) {

        static final Answer NOT_FOUND = new Answer(false, null, List.of());
    }

    record Citation(int number, UUID documentId, String filename, int pageNumber, String excerpt) {}

    Answer ask(UUID ownerId, String question, List<UUID> documentIds) {
        float[] questionEmbedding = embedder.embed(question);
        List<RetrievedChunk> chunks =
                chunkSearch.findClosest(ownerId, documentIds, questionEmbedding, properties.topK()).stream()
                        .filter(chunk -> chunk.similarity() >= properties.minSimilarity())
                        .toList();
        // Without relevant passages there is nothing to ground an answer on, so the model is not asked.
        if (chunks.isEmpty()) {
            return Answer.NOT_FOUND;
        }

        String reply = generator.generate(AnswerPrompt.SYSTEM, AnswerPrompt.userMessage(question, chunks));
        AnswerParser.ParsedAnswer parsed = AnswerParser.parse(reply, chunks.size());
        if (parsed.isNotFound()) {
            return Answer.NOT_FOUND;
        }

        List<Citation> citations = new ArrayList<>();
        for (int source : parsed.sources()) {
            RetrievedChunk chunk = chunks.get(source - 1);
            citations.add(new Citation(
                    citations.size() + 1,
                    chunk.documentId(),
                    chunk.filename(),
                    chunk.pageNumber(),
                    chunk.content()));
        }
        return new Answer(true, parsed.text(), citations);
    }
}
