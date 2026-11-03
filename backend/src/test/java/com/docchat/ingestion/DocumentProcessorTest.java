package com.docchat.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.client.ResourceAccessException;

class DocumentProcessorTest {

    private static final UUID ID = UUID.randomUUID();
    private static final float[] VECTOR = {0.5f};

    private final DocumentRepository repository = mock(DocumentRepository.class);
    private final FileStorage storage = mock(FileStorage.class);
    private final TextEmbedder embedder = mock(TextEmbedder.class);
    private final PdfTextExtractor extractor = new PdfTextExtractor(properties(Duration.ofSeconds(30)));
    private final DocumentProcessor processor = processorWith(new SyncTaskExecutor(), Duration.ofSeconds(30));

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
        doThrow(new DataIntegrityViolationException("rejected data"))
                .when(repository)
                .completeProcessing(any(), any(), any());

        processor.process(ID);

        verify(repository).failProcessing(ID, DocumentFailureReason.INTERNAL_ERROR);
    }

    /**
     * Each of these is how saving can fail when the database goes away: with a connection lost during
     * the work, with none to start the transaction, and with one lost when ending it. The database
     * can be back by the time the failure would be recorded, so recording it would blame the document.
     */
    @ParameterizedTest
    @MethodSource("databaseOutages")
    void aDatabaseOutageWhileSavingLeavesTheDocumentToBeProcessedAgain(RuntimeException outage) {
        givenProcessingDocument(TestPdfs.withPages("Page text"));
        doThrow(outage).doNothing().when(repository).completeProcessing(any(), any(), any());

        processor.process(ID);

        verify(repository, never()).failProcessing(any(), any());

        givenProcessingDocument(TestPdfs.withPages("Page text"));
        when(repository.findIdsInProcessing()).thenReturn(List.of(ID));
        processor.resumeInterruptedDocuments();

        verify(repository, times(2)).completeProcessing(eq(ID), any(), any());
    }

    private static Stream<RuntimeException> databaseOutages() {
        return Stream.of(
                new DataAccessResourceFailureException("connection lost"),
                new CannotCreateTransactionException("no connection for the transaction"),
                new TransactionSystemException("connection lost when ending the transaction"));
    }

    @Test
    void aFailureThatCannotBeRecordedIsFoundAgainLater() {
        givenProcessingDocument(TestPdfs.corrupt());
        doThrow(new DataAccessResourceFailureException("database down"))
                .doNothing()
                .when(repository)
                .failProcessing(ID, DocumentFailureReason.NOT_READABLE);

        processor.process(ID);

        givenProcessingDocument(TestPdfs.corrupt());
        when(repository.findIdsInProcessing()).thenReturn(List.of(ID));
        processor.resumeInterruptedDocuments();

        verify(repository, times(2)).failProcessing(ID, DocumentFailureReason.NOT_READABLE);
        verify(repository, never()).failProcessing(ID, DocumentFailureReason.INTERNAL_ERROR);
    }

    @Test
    void aDatabaseOutageBeforeStartingLeavesTheDocumentForLater() {
        when(repository.isProcessing(ID)).thenThrow(new DataAccessResourceFailureException("database down"));

        processor.process(ID);

        verifyNoInteractions(storage);
        verify(repository, never()).failProcessing(any(), any());
    }

    /** Stopping interrupts the workers, so their calls to the model fail for a reason unrelated to the file. */
    @Test
    void aDocumentInterruptedByTheShutdownIsNotMarkedAsFailed() {
        givenProcessingDocument(TestPdfs.withPages("Page text"));
        when(embedder.embedAll(anyList())).thenThrow(new AiUnavailableException(new ResourceAccessException("interrupted")));

        processor.onApplicationStopping();
        processor.process(ID);

        verify(repository, never()).failProcessing(any(), any());
        verify(repository, never()).completeProcessing(any(), any(), any());
    }

    @Test
    void aDocumentIsNotQueuedAgainWhileItIsInProgress() {
        List<Runnable> queue = new ArrayList<>();
        DocumentProcessor queueing = processorWith(queue::add, Duration.ofSeconds(30));
        when(repository.findIdsInProcessing()).thenReturn(List.of(ID));

        queueing.onDocumentUploaded(new DocumentUploadedEvent(ID));
        queueing.resumeInterruptedDocuments();
        queueing.resumeInterruptedDocuments();

        assertThat(queue).hasSize(1);

        // Once a worker has finished with it, a document still marked as processing was left behind.
        queue.getFirst().run();
        queueing.resumeInterruptedDocuments();

        assertThat(queue).hasSize(2);
    }

    @Test
    void aFullQueueDoesNotFailDocumentsThatAreBeingResumed() {
        TaskExecutor fullQueue = task -> {
            throw new TaskRejectedException("queue is full");
        };
        when(repository.findIdsInProcessing()).thenReturn(List.of(ID));

        processorWith(fullQueue, Duration.ofSeconds(30)).resumeInterruptedDocuments();

        verify(repository, never()).failProcessing(any(), any());
    }

    @Test
    void aDocumentPastTheTimeLimitFailsAndItsWorkIsInterrupted() throws Exception {
        givenProcessingDocument(TestPdfs.withPages("Page text"));
        CountDownLatch interrupted = new CountDownLatch(1);
        when(embedder.embedAll(anyList())).thenAnswer(call -> {
            try {
                Thread.sleep(Duration.ofMinutes(1));
                return List.of(VECTOR);
            } catch (InterruptedException ex) {
                interrupted.countDown();
                throw new AiUnavailableException(ex);
            }
        });

        processorWith(new SyncTaskExecutor(), Duration.ofMillis(100)).process(ID);

        verify(repository).failProcessing(ID, DocumentFailureReason.PROCESSING_TIMED_OUT);
        assertThat(interrupted.await(10, TimeUnit.SECONDS)).isTrue();
        verify(repository, never()).completeProcessing(any(), any(), any());
    }

    /** A worker held for good would leave every later document waiting forever. */
    @Test
    void workThatIgnoresInterruptionsDoesNotHoldTheWorker() {
        givenProcessingDocument(TestPdfs.withPages("Page text"));
        CountDownLatch released = new CountDownLatch(1);
        when(embedder.embedAll(anyList())).thenAnswer(call -> {
            boolean finished = false;
            while (!finished) {
                try {
                    released.await();
                    finished = true;
                } catch (InterruptedException ignored) {
                    // On purpose: this is the behaviour being tested.
                }
            }
            return List.of(VECTOR);
        });

        try {
            assertTimeoutPreemptively(
                    Duration.ofSeconds(10),
                    () -> processorWith(new SyncTaskExecutor(), Duration.ofMillis(100)).process(ID));

            verify(repository).failProcessing(ID, DocumentFailureReason.PROCESSING_TIMED_OUT);
        } finally {
            released.countDown();
        }
    }

    @Test
    void theReasonOfATimeLimitIsRecordedBeforeTheWorkIsInterrupted() throws Exception {
        givenProcessingDocument(TestPdfs.withPages("Page text"));
        CountDownLatch interrupted = new CountDownLatch(1);
        List<DocumentFailureReason> recorded = new CopyOnWriteArrayList<>();
        doAnswer(call -> {
                    recorded.add(call.getArgument(1));
                    return null;
                })
                .when(repository)
                .failProcessing(any(), any());
        when(embedder.embedAll(anyList())).thenAnswer(call -> {
            try {
                Thread.sleep(Duration.ofMinutes(1));
                return List.of(VECTOR);
            } catch (InterruptedException ex) {
                interrupted.countDown();
                throw new AiUnavailableException(ex);
            }
        });

        processorWith(new SyncTaskExecutor(), Duration.ofMillis(100)).process(ID);

        assertThat(interrupted.await(10, TimeUnit.SECONDS)).isTrue();
        await().atMost(Duration.ofSeconds(10)).until(() -> recorded.size() == 2);
        // Only the first one takes effect, because failing a document needs it to be still processing.
        assertThat(recorded.getFirst()).isEqualTo(DocumentFailureReason.PROCESSING_TIMED_OUT);
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

        processorWith(fullQueue, Duration.ofSeconds(30)).onDocumentUploaded(new DocumentUploadedEvent(ID));

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

    private DocumentProcessor processorWith(TaskExecutor executor, Duration timeLimit) {
        return new DocumentProcessor(repository, storage, extractor, embedder, executor, properties(timeLimit));
    }

    private static IngestionProperties properties(Duration timeLimit) {
        return new IngestionProperties(1, 0, 10, timeLimit);
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
