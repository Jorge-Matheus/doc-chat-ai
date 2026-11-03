package com.docchat.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.docchat.document.DocumentFailureReason;
import com.docchat.testsupport.TestPdfs;
import java.io.ByteArrayInputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

class PdfTextExtractorTest {

    private final PdfTextExtractor extractor = new PdfTextExtractor(new IngestionProperties(1, 0, 5));

    @Test
    void extractsTheTextOfEachPageInOrder() {
        assertThat(extract(TestPdfs.withPages("First page", "Second page")))
                .containsExactly("First page", "Second page");
    }

    @Test
    void keepsBlankPagesSoPageNumbersStayCorrect() {
        assertThat(extract(TestPdfs.withPages("First page", "", "Third page")))
                .containsExactly("First page", "", "Third page");
    }

    @Test
    void acceptsADocumentAtThePageLimit() {
        assertThat(extract(TestPdfs.withPages("1", "2", "3", "4", "5"))).hasSize(5);
    }

    @Test
    void refusesADocumentOverThePageLimit() {
        assertFailsWith(TestPdfs.withPages("1", "2", "3", "4", "5", "6"), DocumentFailureReason.TOO_MANY_PAGES);
    }

    @Test
    void refusesADocumentWithoutText() {
        assertFailsWith(TestPdfs.withPages("", ""), DocumentFailureReason.NO_EXTRACTABLE_TEXT);
    }

    @Test
    void refusesAPasswordProtectedDocument() {
        assertFailsWith(TestPdfs.passwordProtected(), DocumentFailureReason.PROTECTED);
    }

    @Test
    void refusesADocumentThatForbidsExtraction() {
        assertFailsWith(TestPdfs.extractionForbidden(), DocumentFailureReason.PROTECTED);
    }

    @Test
    void refusesADamagedDocument() {
        assertFailsWith(TestPdfs.corrupt(), DocumentFailureReason.NOT_READABLE);
    }

    private List<String> extract(byte[] pdf) {
        return extractor.extractPages(new ByteArrayInputStream(pdf));
    }

    private void assertFailsWith(byte[] pdf, DocumentFailureReason reason) {
        assertThatThrownBy(() -> extract(pdf))
                .isInstanceOfSatisfying(
                        PdfExtractionException.class, ex -> assertThat(ex.reason()).isEqualTo(reason));
    }
}
