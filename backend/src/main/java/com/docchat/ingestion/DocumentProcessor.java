package com.docchat.ingestion;

import com.docchat.ai.AiUnavailableException;
import com.docchat.ai.TextEmbedder;
import com.docchat.common.logging.KeepLoggingContext;
import com.docchat.document.Document;
import com.docchat.document.DocumentFailureReason;
import com.docchat.document.DocumentRepository;
import com.docchat.document.DocumentUploadedEvent;
import com.docchat.document.EmbeddedChunk;
import com.docchat.storage.FileStorage;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

/**
 * Extracts, splits and embeds the text of uploaded documents in the background.
 *
 * <p>A document only leaves {@code PROCESSING} for a reason that belongs to the document or to the
 * models. When the application is stopping or the database is unavailable, nothing is recorded,
 * because neither says anything about the document, and it is processed again later.
 */
@Component
class DocumentProcessor {

    private static final Logger log = LoggerFactory.getLogger(DocumentProcessor.class);
    private static final String OUTCOME_READY = "ready";
    private static final String OUTCOME_INTERRUPTED = "interrupted";

    private final DocumentRepository documentRepository;
    private final FileStorage fileStorage;
    private final PdfTextExtractor extractor;
    private final TextEmbedder embedder;
    private final TaskExecutor executor;
    private final IngestionProperties properties;
    private final ObservationRegistry observations;

    /** Documents waiting for a worker or being processed, which must not be queued a second time. */
    private final Set<UUID> inProgress = ConcurrentHashMap.newKeySet();

    private volatile boolean stopping;

    DocumentProcessor(
            DocumentRepository documentRepository,
            FileStorage fileStorage,
            PdfTextExtractor extractor,
            TextEmbedder embedder,
            @Qualifier("documentProcessingExecutor") TaskExecutor executor,
            IngestionProperties properties,
            ObservationRegistry observations) {
        this.documentRepository = documentRepository;
        this.fileStorage = fileStorage;
        this.extractor = extractor;
        this.embedder = embedder;
        this.executor = executor;
        this.properties = properties;
        this.observations = observations;
    }

    @EventListener
    void onDocumentUploaded(DocumentUploadedEvent event) {
        if (!enqueue(event.documentId())) {
            log.warn("Processing queue is full, failing document {}", event.documentId());
            fail(event.documentId(), DocumentFailureReason.PROCESSING_UNAVAILABLE);
        }
    }

    @EventListener(ContextClosedEvent.class)
    void onApplicationStopping() {
        stopping = true;
    }

    /**
     * Picks up documents marked as processing that no worker has. After a restart that is all of
     * them. Later it finds those whose outcome could not be saved while the database was down.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(
            initialDelayString = "${docchat.ingestion.resume-interval}",
            fixedDelayString = "${docchat.ingestion.resume-interval}")
    void resumeInterruptedDocuments() {
        try {
            for (UUID documentId : documentRepository.findIdsInProcessing()) {
                // With the queue full, the rest stay as they are and are tried again next time.
                if (!enqueue(documentId)) {
                    return;
                }
            }
        } catch (DataAccessException ex) {
            log.warn("Could not look for interrupted documents: {}", ex.getClass().getSimpleName());
        }
    }

    /** @return false if the queue is full */
    private boolean enqueue(UUID documentId) {
        if (!inProgress.add(documentId)) {
            return true;
        }
        try {
            executor.execute(() -> process(documentId));
            return true;
        } catch (TaskRejectedException ex) {
            inProgress.remove(documentId);
            return false;
        }
    }

    void process(UUID documentId) {
        try (MDC.MDCCloseable inLogs = MDC.putCloseable("documentId", documentId.toString())) {
            // It may have been deleted while it waited in the queue.
            if (documentRepository.isProcessing(documentId)) {
                processWithinTimeLimit(documentId);
            }
        } catch (DataAccessException ex) {
            log.warn(
                    "Could not start on document {}, trying again later: {}",
                    documentId,
                    ex.getClass().getSimpleName());
        } finally {
            inProgress.remove(documentId);
        }
    }

    /**
     * Runs on its own thread because a file can keep PDFBox busy in a way an interruption does not
     * stop. The worker is then free for the next document, and if the abandoned thread ever finishes,
     * saving its result does nothing because the document has already failed.
     */
    private void processWithinTimeLimit(UUID documentId) {
        // Interrupted, unless it ends in one of the ways below. Tags hold only fixed words, so no file
        // name or text can reach the metrics.
        Observation processing = Observation.createNotStarted("docchat.document.processing", observations)
                .lowCardinalityKeyValue("outcome", OUTCOME_INTERRUPTED)
                .start();
        FutureTask<String> job = new FutureTask<>(() -> extractAndStore(documentId));
        Thread.ofPlatform()
                .daemon()
                .name("document-job-" + documentId)
                .start(new KeepLoggingContext().decorate(job));
        try {
            String outcome = job.get(properties.maxProcessingTime().toMillis(), TimeUnit.MILLISECONDS);
            processing.lowCardinalityKeyValue("outcome", outcome);
        } catch (TimeoutException ex) {
            log.warn("Document {} passed the time limit of {}", documentId, properties.maxProcessingTime());
            // Recorded first, so the failure the interruption causes in the job cannot replace this reason.
            processing.lowCardinalityKeyValue("outcome", fail(documentId, DocumentFailureReason.PROCESSING_TIMED_OUT));
            job.cancel(true);
        } catch (InterruptedException ex) {
            // The application is stopping, and the document is processed again at the next startup.
            job.cancel(true);
            Thread.currentThread().interrupt();
        } catch (ExecutionException ex) {
            log.error("Could not process document {}", documentId, ex);
            processing.lowCardinalityKeyValue("outcome", fail(documentId, DocumentFailureReason.INTERNAL_ERROR));
        } finally {
            processing.stop();
        }
    }

    /** @return how it ended, for the metrics */
    private String extractAndStore(UUID documentId) {
        try (InputStream pdf = fileStorage.open(Document.storageKey(documentId))) {
            List<String> pages = stage("extraction", () -> extractor.extractPages(pdf));
            List<EmbeddedChunk> chunks = stage("embedding", () -> embedChunks(pages));
            stage("saving", () -> {
                documentRepository.completeProcessing(documentId, pages, chunks);
                return null;
            });
            log.info("Document {} processed: {} pages, {} passages", documentId, pages.size(), chunks.size());
            return OUTCOME_READY;
        } catch (PdfExtractionException ex) {
            log.info("Document {} rejected: {}", documentId, ex.reason());
            return fail(documentId, ex.reason());
        } catch (AiUnavailableException ex) {
            log.warn("Could not embed document {}: {}", documentId, ex.getCause().toString());
            return fail(documentId, DocumentFailureReason.EMBEDDING_UNAVAILABLE);
        } catch (DataAccessResourceFailureException | TransactionException ex) {
            // Saving runs in a transaction, and without a connection that fails with its own exceptions.
            log.warn(
                    "Database unavailable for document {}, trying again later: {}",
                    documentId,
                    ex.getClass().getSimpleName());
            return OUTCOME_INTERRUPTED;
        } catch (IOException | RuntimeException | OutOfMemoryError ex) {
            log.error("Could not process document {}", documentId, ex);
            return fail(documentId, DocumentFailureReason.INTERNAL_ERROR);
        }
    }

    /** @return how it ended, for the metrics */
    private String fail(UUID documentId, DocumentFailureReason reason) {
        if (stopping) {
            log.info("Document {} was interrupted by the shutdown and will be processed again", documentId);
            return OUTCOME_INTERRUPTED;
        }
        try {
            documentRepository.failProcessing(documentId, reason);
            return reason.name().toLowerCase(Locale.ROOT);
        } catch (DataAccessException ex) {
            log.warn(
                    "Could not record the failure of document {}, trying again later: {}",
                    documentId,
                    ex.getClass().getSimpleName());
            return OUTCOME_INTERRUPTED;
        }
    }

    private <T> T stage(String name, Supplier<T> work) {
        return Observation.createNotStarted("docchat.document.stage", observations)
                .lowCardinalityKeyValue("stage", name)
                .observe(work);
    }

    private List<EmbeddedChunk> embedChunks(List<String> pages) {
        List<TextChunker.PageChunk> chunks = TextChunker.split(pages);
        List<float[]> vectors =
                embedder.embedAll(chunks.stream().map(TextChunker.PageChunk::text).toList());
        List<EmbeddedChunk> embedded = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            TextChunker.PageChunk chunk = chunks.get(i);
            embedded.add(new EmbeddedChunk(chunk.pageNumber(), chunk.chunkIndex(), chunk.text(), vectors.get(i)));
        }
        return embedded;
    }
}
