package com.docchat.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.docchat.ai.AiUnavailableException;
import com.docchat.ai.TextEmbedder;
import com.docchat.document.Document;
import com.docchat.document.DocumentFailureReason;
import com.docchat.document.DocumentRepository;
import com.docchat.document.DocumentUploadedEvent;
import com.docchat.document.EmbeddedChunk;
import com.docchat.storage.FileStorage;
import com.docchat.testsupport.TestPdfs;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.web.client.ResourceAccessException;

class DocumentProcessorTest {

    private static final UUID ID = UUID.randomUUID();
    private static final float[] VECTOR = {0.5f};

    private final DocumentRepository repository = mock(DocumentRepository.class);
    private final FileStorage storage = mock(FileStorage.class);
    private final TextEmbedder embedder = mock(TextEmbedder.class);
    private final PdfTextExtractor extractor = new PdfTextExtractor(new IngestionProperties(1, 0, 10));
    private final DocumentProcessor processor =
            new DocumentProcessor(repository, storage, extractor, embedder, new SyncTaskExecutor());

    @BeforeEach
    void setUp() {
        when(embedder.embedAll(anyList())).thenAnswer(call -> {
            List<String> texts = call.getArgument(0);
            return texts.stream().map(text -> VECTOR).toList();
        });
    }

    @Test
    void savesThePagesAndThePassagesOfAnUploadedDocument() {
        givenProcessingDocument(TestPdfs.withPages("Page one", "", "Page three"));

        processor.onDocumentUploaded(new DocumentUploadedEvent(ID));

        ArgumentCaptor<List<EmbeddedChunk>> chunks = chunksCaptor();
        verify(repository).completeProcessing(eq(ID), eq(List.of("Page one", "", "Page three")), chunks.capture());
        assertThat(chunks.getValue())
                .extracting(EmbeddedChunk::pageNumber, EmbeddedChunk::content)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, "Page one"),
                        org.assertj.core.groups.Tuple.tuple(3, "Page three"));
        assertThat(chunks.getValue().getFirst().embedding()).isSameAs(VECTOR);
        verify(repository, never()).failProcessing(any(), any());
    }

    @Test
    void recordsWhyAFileCouldNotBeUsed() {
        givenProcessingDocument(TestPdfs.corrupt());

        processor.process(ID);

        verify(repository).failProcessing(ID, DocumentFailureReason.NOT_READABLE);
        verifyNoInteractions(embedder);
    }

    @Test
    void recordsThatTheEmbeddingModelWasUnavailable() {
        givenProcessingDocument(TestPdfs.withPages("Page text"));
        when(embedder.embedAll(anyList())).thenThrow(new AiUnavailableException(new ResourceAccessException("down")));

        processor.process(ID);

        verify(repository).failProcessing(ID, DocumentFailureReason.EMBEDDING_UNAVAILABLE);
        verify(repository, never()).completeProcessing(any(), any(), any());
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
                .completeProcessing(any(), any(), any());

        processor.process(ID);

        verify(repository).failProcessing(ID, DocumentFailureReason.INTERNAL_ERROR);
    }

    @Test
    void ignoresADocumentDeletedWhileItWaited() {
        when(repository.isProcessing(ID)).thenReturn(false);

        processor.process(ID);

        verifyNoInteractions(storage);
        verify(repository, never()).completeProcessing(any(), any(), any());
    }

    @Test
    void failsTheDocumentWhenTheQueueIsFull() {
        TaskExecutor fullQueue = task -> {
            throw new TaskRejectedException("queue is full");
        };
        DocumentProcessor busyProcessor = new DocumentProcessor(repository, storage, extractor, embedder, fullQueue);

        busyProcessor.onDocumentUploaded(new DocumentUploadedEvent(ID));

        verify(repository).failProcessing(ID, DocumentFailureReason.PROCESSING_UNAVAILABLE);
    }

    @Test
    void resumesDocumentsInterruptedByTheLastShutdown() {
        givenProcessingDocument(TestPdfs.withPages("Page text"));
        when(repository.findIdsInProcessing()).thenReturn(List.of(ID));

        processor.resumeInterruptedDocuments();

        verify(repository).completeProcessing(eq(ID), eq(List.of("Page text")), anyList());
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

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<EmbeddedChunk>> chunksCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }
}
