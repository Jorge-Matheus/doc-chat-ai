package com.docchat.rag;

import com.docchat.ai.AiUnavailableException;
import com.docchat.ai.TextEmbedder;
import com.docchat.ai.TextGenerator;
import com.docchat.common.web.ApiException;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Answers a question from the user's own documents, citing the pages it used. */
@Service
public class AnswerService {

    /** How many earlier turns are enough to understand a follow-up question. */
    public static final int HISTORY_TURNS = 3;

    private final TextEmbedder embedder;
    private final TextGenerator generator;
    private final QuestionRewriter rewriter;
    private final ChunkSearchRepository chunkSearch;
    private final RagProperties properties;
    private final AsyncTaskExecutor executor;
    private final ObservationRegistry observations;

    AnswerService(
            TextEmbedder embedder,
            TextGenerator generator,
            QuestionRewriter rewriter,
            ChunkSearchRepository chunkSearch,
            RagProperties properties,
            @Qualifier("questionExecutor") AsyncTaskExecutor executor,
            ObservationRegistry observations) {
        this.embedder = embedder;
        this.generator = generator;
        this.rewriter = rewriter;
        this.chunkSearch = chunkSearch;
        this.properties = properties;
        this.executor = executor;
        this.observations = observations;
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

    /**
     * A question calls the models several times, and each call has its own read timeout. The limit
     * here covers all of them, so the user gets this error and not the proxy's while the answer is
     * still being stored, which would repeat it when they ask again.
     *
     * @param history the most recent turns of the conversation, oldest first
     */
    public Answer answer(UUID ownerId, String question, List<UUID> documentIds, List<PreviousTurn> history) {
        // Tags hold only fixed words, so no question or document can reach the metrics.
        Observation observation = Observation.createNotStarted("docchat.question", observations)
                .lowCardinalityKeyValue("outcome", "failed");
        return observation.observe(() -> {
            Answer answer = answerWithinLimits(observation, ownerId, question, documentIds, history);
            observation.lowCardinalityKeyValue("outcome", answer.text() == null ? "not_found" : "answered");
            return answer;
        });
    }

    private Answer answerWithinLimits(
            Observation observation,
            UUID ownerId,
            String question,
            List<UUID> documentIds,
            List<PreviousTurn> history) {
        Future<Answer> work = start(observation, () -> findAnswer(ownerId, question, documentIds, history));
        try {
            return work.get(properties.questionTimeLimit().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            observation.lowCardinalityKeyValue("outcome", "timed_out");
            work.cancel(true);
            throw new AiUnavailableException(ex);
        } catch (InterruptedException ex) {
            work.cancel(true);
            Thread.currentThread().interrupt();
            throw new AiUnavailableException(ex);
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw new IllegalStateException(ex.getCause());
        }
    }

    private Future<Answer> start(Observation observation, Callable<Answer> findingTheAnswer) {
        try {
            return executor.submit(findingTheAnswer);
        } catch (TaskRejectedException ex) {
            observation.lowCardinalityKeyValue("outcome", "refused");
            throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "TOO_MANY_QUESTIONS",
                    "Several questions are being answered right now. Try again in a moment.");
        }
    }

    private Answer findAnswer(UUID ownerId, String question, List<UUID> documentIds, List<PreviousTurn> history) {
        // A first question needs no rewriting, and timing it would hide how long rewriting really takes.
        String standalone = history.isEmpty() ? question : stage("rewrite", () -> rewriter.rewrite(question, history));
        String interpreted = standalone.equals(question) ? null : standalone;

        float[] questionEmbedding = stage("embedding", () -> embedder.embed(standalone));
        List<RetrievedChunk> chunks =
                stage("search", () -> chunkSearch.findClosest(ownerId, documentIds, questionEmbedding, properties.topK()))
                        .stream()
                        .filter(chunk -> chunk.similarity() >= properties.minSimilarity())
                        .toList();
        // Without relevant passages there is nothing to ground an answer on, so the model is not asked.
        if (chunks.isEmpty()) {
            return Answer.notFound(interpreted);
        }

        String reply = stage(
                "generation", () -> generator.generate(AnswerPrompt.SYSTEM, AnswerPrompt.userMessage(standalone, chunks)));
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

    private <T> T stage(String name, Supplier<T> work) {
        return Observation.createNotStarted("docchat.question.stage", observations)
                .lowCardinalityKeyValue("stage", name)
                .observe(work);
    }
}
