package com.docchat.ingestion;

import com.docchat.common.db.PgText;
import com.docchat.document.DocumentFailureReason;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

@Component
class PdfTextExtractor {

    private final int maxPages;

    PdfTextExtractor(IngestionProperties properties) {
        this.maxPages = properties.maxPages();
    }

    /**
     * Returns the text of each page, in order, with blank strings for pages without text.
     *
     * @throws PdfExtractionException if the file cannot produce any text
     */
    List<String> extractPages(InputStream pdf) {
        // Uploads are small enough to read into memory, but PDFBox's working data goes to disk.
        try (RandomAccessReadBuffer source = new RandomAccessReadBuffer(pdf);
                PDDocument document = Loader.loadPDF(source, IOUtils.createTempFileOnlyStreamCache())) {
            if (!document.getCurrentAccessPermission().canExtractContent()) {
                throw new PdfExtractionException(DocumentFailureReason.PROTECTED);
            }
            int pageCount = document.getNumberOfPages();
            if (pageCount > maxPages) {
                throw new PdfExtractionException(DocumentFailureReason.TOO_MANY_PAGES);
            }
            List<String> pages = extractEachPage(document, pageCount);
            if (pages.stream().allMatch(String::isBlank)) {
                throw new PdfExtractionException(DocumentFailureReason.NO_EXTRACTABLE_TEXT);
            }
            return pages;
        } catch (InvalidPasswordException ex) {
            throw new PdfExtractionException(DocumentFailureReason.PROTECTED, ex);
        } catch (PdfExtractionException ex) {
            throw ex;
        } catch (IOException | RuntimeException | StackOverflowError ex) {
            // PDFBox reports malformed files in all of these ways.
            throw new PdfExtractionException(DocumentFailureReason.NOT_READABLE, ex);
        }
    }

    private static List<String> extractEachPage(PDDocument document, int pageCount) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        List<String> pages = new ArrayList<>(pageCount);
        for (int number = 1; number <= pageCount; number++) {
            stripper.setStartPage(number);
            stripper.setEndPage(number);
            pages.add(PgText.clean(stripper.getText(document)));
        }
        return pages;
    }
}
