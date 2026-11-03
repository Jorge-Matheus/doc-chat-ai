package com.docchat.ingestion;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.docchat.document.Document;
import com.docchat.document.DocumentFailureReason;
import com.docchat.document.DocumentRepository;
import com.docchat.document.DocumentUploadedEvent;
import com.docchat.storage.FileStorage;
import com.docchat.testsupport.TestPdfs;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataAccessResourceFailureException;

class DocumentProcessorTest {

    private static final UUID ID = UUID.randomUUID();

    private final DocumentRepository repository = mock(DocumentRepository.class);
    private final FileStorage storage = mock(FileStorage.class);
    private final PdfTextExtractor extractor = new PdfTextExtractor(new IngestionProperties(1, 0, 10));
    private final DocumentProcessor processor =
            new DocumentProcessor(repository, storage, extractor, new SyncTaskExecutor());

    @Test
    void savesThePagesOfAnUploadedDocument() {
        givenProcessingDocument(TestPdfs.withPages("Page text"));

        processor.onDocumentUploaded(new DocumentUploadedEvent(ID));

        verify(repository).completeProcessing(ID, List.of("Page text"));
        verify(repository, never()).failProcessing(any(), any());
    }

    @Test
    void recordsWhyAFileCouldNotBeUsed() {
        givenProcessingDocument(TestPdfs.corrupt());

        processor.process(ID);

        verify(repository).failProcessing(ID, DocumentFailureReason.NOT_READABLE);
    }

    @Test
    void recordsAnInternalFailureWhenTheFileCannotBeRead() {
        when(repository.isProcessing(ID)).thenReturn(true);
        when(storage.open(Document.storageKey(ID))).thenThrow(new IllegalStateException("disk failure"));

        processor.process(ID);

        verify(repository).failProcessing(ID, DocumentFailureReason.INTERNAL_ERROR);
    }

    @Test
    void recordsAnInternalFailureWhenThePagesCannotBeSaved() {
        givenProcessingDocument(TestPdfs.withPages("Page text"));
        doThrow(new DataAccessResourceFailureException("database failure"))
                .when(repository)
                .completeProcessing(any(), any());

        processor.process(ID);

        verify(repository).failProcessing(ID, DocumentFailureReason.INTERNAL_ERROR);
    }

    @Test
    void ignoresADocumentDeletedWhileItWaited() {
        when(repository.isProcessing(ID)).thenReturn(false);

        processor.process(ID);

        verifyNoInteractions(storage);
        verify(repository, never()).completeProcessing(any(), any());
    }

    @Test
    void failsTheDocumentWhenTheQueueIsFull() {
        TaskExecutor fullQueue = task -> {
            throw new TaskRejectedException("queue is full");
        };
        DocumentProcessor busyProcessor = new DocumentProcessor(repository, storage, extractor, fullQueue);

        busyProcessor.onDocumentUploaded(new DocumentUploadedEvent(ID));

        verify(repository).failProcessing(ID, DocumentFailureReason.PROCESSING_UNAVAILABLE);
    }

    @Test
    void resumesDocumentsInterruptedByTheLastShutdown() {
        givenProcessingDocument(TestPdfs.withPages("Page text"));
        when(repository.findIdsInProcessing()).thenReturn(List.of(ID));

        processor.resumeInterruptedDocuments();

        verify(repository).completeProcessing(ID, List.of("Page text"));
    }

    @Test
    void startupSurvivesAnUnavailableDatabase() {
        when(repository.findIdsInProcessing()).thenThrow(new DataAccessResourceFailureException("down"));

        processor.resumeInterruptedDocuments();

        verifyNoInteractions(storage);
    }

    private void givenProcessingDocument(byte[] pdf) {
        when(repository.isProcessing(ID)).thenReturn(true);
        when(storage.open(Document.storageKey(ID))).thenReturn(new ByteArrayInputStream(pdf));
    }
}
