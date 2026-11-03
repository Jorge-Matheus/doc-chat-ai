package com.docchat.document;

import com.docchat.common.web.ApiException;
import com.docchat.storage.FileStorage;
import com.docchat.storage.StorageException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

@Service
@EnableConfigurationProperties(DocumentProperties.class)
class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private static final String PDF_SIGNATURE = "%PDF-";
    /** Readers accept a signature within the first kilobyte, and some real files need that. */
    private static final int SIGNATURE_SEARCH_BYTES = 1024;
    private static final int MAX_FILENAME_LENGTH = 255;
    private static final String DEFAULT_FILENAME = "document.pdf";

    private final DocumentRepository documentRepository;
    private final FileStorage fileStorage;
    private final ApplicationEventPublisher eventPublisher;
    private final DocumentProperties properties;

    DocumentService(
            DocumentRepository documentRepository,
            FileStorage fileStorage,
            ApplicationEventPublisher eventPublisher,
            DocumentProperties properties) {
        this.documentRepository = documentRepository;
        this.fileStorage = fileStorage;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
    }

    /** Stores the file and queues it for processing. The content is checked later by the extractor. */
    Document upload(UUID ownerId, MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_FILE", "The file is empty.");
        }
        requirePdfSignature(file);
        requireStorageLeft(ownerId, file.getSize());

        UUID id = UUID.randomUUID();
        String key = Document.storageKey(id);
        try (InputStream content = file.getInputStream()) {
            fileStorage.store(key, content);
        }
        Document document;
        try {
            document = documentRepository.insertProcessing(
                    id, ownerId, displayName(file.getOriginalFilename()), file.getSize());
        } catch (RuntimeException ex) {
            deleteFileQuietly(key);
            throw ex;
        }
        eventPublisher.publishEvent(new DocumentUploadedEvent(id));
        return document;
    }

    Document get(UUID id, UUID ownerId) {
        return documentRepository.findOwned(id, ownerId).orElseThrow(DocumentService::notFound);
    }

    DocumentList list(UUID ownerId, int page, int size) {
        List<Document> items = documentRepository.findPageOwnedBy(ownerId, size, (long) page * size);
        return new DocumentList(items, documentRepository.countOwnedBy(ownerId));
    }

    /** Only processed documents have pages, so one still processing also answers 404. */
    DocumentPage getPage(UUID id, UUID ownerId, int pageNumber) {
        return documentRepository
                .findPageOwned(id, ownerId, pageNumber)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PAGE_NOT_FOUND", "The page was not found."));
    }

    /** @return the content of the file, which the caller must close */
    InputStream openFile(UUID id) {
        try {
            return fileStorage.open(Document.storageKey(id));
        } catch (StorageException ex) {
            log.warn("Could not open the file of document {}", id, ex);
            throw new ApiException(HttpStatus.NOT_FOUND, "FILE_NOT_AVAILABLE", "The original file is not available.");
        }
    }

    void delete(UUID id, UUID ownerId) {
        if (!documentRepository.deleteOwned(id, ownerId)) {
            throw notFound();
        }
        deleteFileQuietly(Document.storageKey(id));
    }

    /** Processes again a document that failed for a reason unrelated to its file, which is still stored. */
    Document retry(UUID id, UUID ownerId) {
        Document document = documentRepository
                .requeueFailed(id, ownerId)
                .orElseThrow(() -> {
                    get(id, ownerId);
                    return new ApiException(
                            HttpStatus.CONFLICT,
                            "DOCUMENT_CANNOT_BE_RETRIED",
                            "Only a document that failed for a temporary reason can be tried again.");
                });
        eventPublisher.publishEvent(new DocumentUploadedEvent(id));
        return document;
    }

    record DocumentList(List<Document> items, long totalItems) {}

    /** Two uploads at the same moment can pass this together, which is fine for protecting the disk. */
    private void requireStorageLeft(UUID ownerId, long fileSize) {
        long limit = properties.storagePerUser().toBytes();
        if (documentRepository.totalSizeOwnedBy(ownerId) + fileSize > limit) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "STORAGE_LIMIT_REACHED",
                    "You have reached your storage limit of %s. Delete some documents to make room."
                            .formatted(readable(properties.storagePerUser())));
        }
    }

    /** DataSize prints itself in bytes, which is no way to tell a person about gigabytes. */
    static String readable(DataSize size) {
        DecimalFormat number = new DecimalFormat("0.#", DecimalFormatSymbols.getInstance(Locale.ROOT));
        double bytes = size.toBytes();
        if (bytes >= DataSize.ofGigabytes(1).toBytes()) {
            return number.format(bytes / DataSize.ofGigabytes(1).toBytes()) + " GB";
        }
        if (bytes >= DataSize.ofMegabytes(1).toBytes()) {
            return number.format(bytes / DataSize.ofMegabytes(1).toBytes()) + " MB";
        }
        if (bytes >= DataSize.ofKilobytes(1).toBytes()) {
            return number.format(bytes / DataSize.ofKilobytes(1).toBytes()) + " KB";
        }
        return number.format(bytes) + " bytes";
    }

    /** A document of another user gets the same answer, so its existence is not revealed. */
    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "DOCUMENT_NOT_FOUND", "The document was not found.");
    }

    private static void requirePdfSignature(MultipartFile file) throws IOException {
        try (InputStream content = file.getInputStream()) {
            byte[] start = content.readNBytes(SIGNATURE_SEARCH_BYTES);
            if (!new String(start, StandardCharsets.ISO_8859_1).contains(PDF_SIGNATURE)) {
                throw new ApiException(
                        HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_FILE_TYPE", "Only PDF files are accepted.");
            }
        }
    }

    /** The name is only displayed, so this removes path segments and characters the database rejects. */
    static String displayName(String originalFilename) {
        if (originalFilename == null) {
            return DEFAULT_FILENAME;
        }
        int lastSeparator = Math.max(originalFilename.lastIndexOf('/'), originalFilename.lastIndexOf('\\'));
        String name = originalFilename.substring(lastSeparator + 1).replaceAll("[\\p{Cntrl}\\p{Cs}]", "").strip();
        if (name.isEmpty()) {
            return DEFAULT_FILENAME;
        }
        int codePoints = name.codePointCount(0, name.length());
        return codePoints <= MAX_FILENAME_LENGTH
                ? name
                : name.substring(0, name.offsetByCodePoints(0, MAX_FILENAME_LENGTH));
    }

    private void deleteFileQuietly(String key) {
        try {
            fileStorage.delete(key);
        } catch (StorageException ex) {
            // The database is already consistent, and an orphan file is not visible to anyone.
            log.warn("Could not delete file {}", key, ex);
        }
    }
}
