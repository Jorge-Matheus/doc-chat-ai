package com.docchat.ingestion;

import com.docchat.document.Document;
import com.docchat.document.DocumentFailureReason;
import com.docchat.document.DocumentRepository;
import com.docchat.document.DocumentUploadedEvent;
import com.docchat.storage.FileStorage;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/** Extracts the text of uploaded documents in the background. */
@Component
class DocumentProcessor {

    private static final Logger log = LoggerFactory.getLogger(DocumentProcessor.class);

    private final DocumentRepository documentRepository;
    private final FileStorage fileStorage;
    private final PdfTextExtractor extractor;
    private final TaskExecutor executor;

    DocumentProcessor(
            DocumentRepository documentRepository,
            FileStorage fileStorage,
            PdfTextExtractor extractor,
            @Qualifier("documentProcessingExecutor") TaskExecutor executor) {
        this.documentRepository = documentRepository;
        this.fileStorage = fileStorage;
        this.extractor = extractor;
        this.executor = executor;
    }

    @EventListener
    void onDocumentUploaded(DocumentUploadedEvent event) {
        enqueue(event.documentId());
    }

    /** Documents still marked as processing were interrupted by the previous shutdown. */
    @EventListener(ApplicationReadyEvent.class)
    void resumeInterruptedDocuments() {
        try {
            documentRepository.findIdsInProcessing().forEach(this::enqueue);
        } catch (DataAccessException ex) {
            log.warn("Could not resume interrupted documents: {}", ex.getClass().getSimpleName());
        }
    }

    private void enqueue(UUID documentId) {
        try {
            executor.execute(() -> process(documentId));
        } catch (TaskRejectedException ex) {
            log.warn("Processing queue is full, failing document {}", documentId);
            documentRepository.failProcessing(documentId, DocumentFailureReason.PROCESSING_UNAVAILABLE);
        }
    }

    void process(UUID documentId) {
        // It may have been deleted while it waited in the queue.
        if (!documentRepository.isProcessing(documentId)) {
            return;
        }
        try (InputStream pdf = fileStorage.open(Document.storageKey(documentId))) {
            documentRepository.completeProcessing(documentId, extractor.extractPages(pdf));
            log.info("Document {} processed", documentId);
        } catch (PdfExtractionException ex) {
            log.info("Document {} rejected: {}", documentId, ex.reason());
            documentRepository.failProcessing(documentId, ex.reason());
        } catch (IOException | RuntimeException | OutOfMemoryError ex) {
            log.error("Could not process document {}", documentId, ex);
            documentRepository.failProcessing(documentId, DocumentFailureReason.INTERNAL_ERROR);
        }
    }
}
