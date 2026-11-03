package com.docchat.testsupport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/** Generates real PDF files, so tests do not need binary fixtures. */
public final class TestPdfs {

    private TestPdfs() {}

    /** A document with one page per text. An empty text produces a page with no content. */
    public static byte[] withPages(String... pageTexts) {
        try (PDDocument document = build(pageTexts)) {
            return save(document);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** Opening it requires the password. */
    public static byte[] passwordProtected() {
        return encrypted("user-password", new AccessPermission());
    }

    /** It opens without a password, but its permissions forbid extracting the text. */
    public static byte[] extractionForbidden() {
        AccessPermission permissions = new AccessPermission();
        permissions.setCanExtractContent(false);
        return encrypted("", permissions);
    }

    /** It has the PDF signature, but the rest of the file is garbage. */
    public static byte[] corrupt() {
        return "%PDF-1.7\nthis is not really a PDF".getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] encrypted(String userPassword, AccessPermission permissions) {
        try (PDDocument document = build("Secret text")) {
            StandardProtectionPolicy policy =
                    new StandardProtectionPolicy("owner-password", userPassword, permissions);
            policy.setEncryptionKeyLength(256);
            document.protect(policy);
            return save(document);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static PDDocument build(String... pageTexts) throws IOException {
        PDDocument document = new PDDocument();
        for (String text : pageTexts) {
            PDPage page = new PDPage();
            document.addPage(page);
            if (!text.isEmpty()) {
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(72, 700);
                    content.showText(text);
                    content.endText();
                }
            }
        }
        return document;
    }

    private static byte[] save(PDDocument document) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        document.save(output);
        return output.toByteArray();
    }
}
