package com.docchat.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.docchat.common.web.ApiException;
import com.docchat.storage.FileStorage;
import com.docchat.testsupport.TestPdfs;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

class DocumentServiceTest {

    private static final UUID OWNER = UUID.randomUUID();

    private final DocumentRepository repository = mock(DocumentRepository.class);
    private final FileStorage storage = mock(FileStorage.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final DocumentService service = new DocumentService(repository, storage, events);

    @Test
    void storesAValidFileThenAnnouncesIt() throws Exception {
        service.upload(OWNER, pdf("contract.pdf"));

        ArgumentCaptor<UUID> id = ArgumentCaptor.forClass(UUID.class);
        verify(repository).insertProcessing(id.capture(), eq(OWNER), eq("contract.pdf"), anyLong());
        verify(storage).store(eq(id.getValue() + ".pdf"), any());
        verify(events).publishEvent(new DocumentUploadedEvent(id.getValue()));
    }

    @Test
    void refusesAnEmptyFile() {
        MockMultipartFile empty = new MockMultipartFile("file", "a.pdf", null, new byte[0]);

        assertThatThrownBy(() -> service.upload(OWNER, empty))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getCode()).isEqualTo("EMPTY_FILE"));
        verifyNoInteractions(storage);
    }

    @Test
    void refusesAFileWithoutThePdfSignatureWhateverItsNameIs() {
        MockMultipartFile fake = new MockMultipartFile(
                "file", "fake.pdf", "application/pdf", "<html>".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.upload(OWNER, fake))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE));
        verifyNoInteractions(storage);
    }

    @Test
    void removesTheFileWhenTheDatabaseFails() {
        when(repository.insertProcessing(any(), any(), anyString(), anyLong()))
                .thenThrow(new DataAccessResourceFailureException("down"));

        assertThatThrownBy(() -> service.upload(OWNER, pdf("a.pdf")))
                .isInstanceOf(DataAccessResourceFailureException.class);

        verify(storage).delete(anyString());
        verifyNoInteractions(events);
    }

    @Test
    void deleteOfADocumentThatIsNotOwnedLeavesTheFileAlone() {
        UUID id = UUID.randomUUID();
        when(repository.deleteOwned(id, OWNER)).thenReturn(false);

        assertThatThrownBy(() -> service.delete(id, OWNER))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.getCode()).isEqualTo("DOCUMENT_NOT_FOUND"));
        verify(storage, never()).delete(anyString());
    }

    @Test
    void aFileThatCannotBeRemovedDoesNotFailTheDelete() {
        UUID id = UUID.randomUUID();
        when(repository.deleteOwned(id, OWNER)).thenReturn(true);
        doThrow(new com.docchat.storage.StorageExceptionForTests()).when(storage).delete(anyString());

        service.delete(id, OWNER);

        verify(repository).deleteOwned(id, OWNER);
    }

    @Test
    void displayNameKeepsOnlyTheSafeLastSegment() {
        assertThat(DocumentService.displayName("contract.pdf")).isEqualTo("contract.pdf");
        assertThat(DocumentService.displayName("../../etc/passwd.pdf")).isEqualTo("passwd.pdf");
        assertThat(DocumentService.displayName("C:\\Users\\me\\report.pdf")).isEqualTo("report.pdf");
        assertThat(DocumentService.displayName("a\u0000b\r\n.pdf")).isEqualTo("ab.pdf");
        assertThat(DocumentService.displayName(null)).isEqualTo("document.pdf");
        assertThat(DocumentService.displayName("folder/")).isEqualTo("document.pdf");
    }

    @Test
    void displayNameIsLimitedWithoutSplittingACharacter() {
        String longName = "\uD83D\uDE00".repeat(300);

        String result = DocumentService.displayName(longName);

        assertThat(result.codePointCount(0, result.length())).isEqualTo(255);
        assertThat(result).endsWith("\uD83D\uDE00");
    }

    private static MockMultipartFile pdf(String filename) {
        return new MockMultipartFile("file", filename, "application/pdf", TestPdfs.withPages("Text"));
    }
}
