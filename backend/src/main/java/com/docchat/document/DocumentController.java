package com.docchat.document;

import com.docchat.auth.AccountPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/documents")
@Tag(name = "Documents", description = "PDF files of the signed-in user")
class DocumentController {

    private final DocumentService documentService;

    DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "Upload a PDF",
            description = "Answers 202 while the text is extracted in the background. Poll the document"
                    + " until its status is READY or FAILED.")
    ResponseEntity<DocumentResponse> upload(
            @AuthenticationPrincipal AccountPrincipal principal, @RequestPart("file") MultipartFile file)
            throws IOException {
        Document document = documentService.upload(principal.getId(), file);
        return ResponseEntity.accepted()
                .location(URI.create("/api/documents/" + document.id()))
                .body(DocumentResponse.from(document));
    }

    @GetMapping
    @Operation(summary = "List the user's documents, newest first")
    DocumentListResponse list(
            @AuthenticationPrincipal AccountPrincipal principal,
            @Parameter(description = "Page number, starting at 0") @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        DocumentService.DocumentList result = documentService.list(principal.getId(), page, size);
        List<DocumentResponse> items =
                result.items().stream().map(DocumentResponse::from).toList();
        return new DocumentListResponse(items, page, size, result.totalItems());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a document of the user")
    DocumentResponse get(@AuthenticationPrincipal AccountPrincipal principal, @PathVariable UUID id) {
        return DocumentResponse.from(documentService.get(id, principal.getId()));
    }

    @GetMapping("/{id}/pages/{pageNumber}")
    @Operation(summary = "Get the text extracted from one page of a document")
    DocumentPage getPage(
            @AuthenticationPrincipal AccountPrincipal principal,
            @PathVariable UUID id,
            @Parameter(description = "Page number, starting at 1") @PathVariable @Min(1) int pageNumber) {
        return documentService.getPage(id, principal.getId(), pageNumber);
    }

    /**
     * Uploads only have their signature checked, so a file could also be a valid HTML page. The fixed
     * content type, together with the nosniff header sent with every response, keeps it from running
     * as a page of this site.
     */
    @GetMapping("/{id}/file")
    @Operation(summary = "Get the original PDF of a document")
    ResponseEntity<Resource> getFile(@AuthenticationPrincipal AccountPrincipal principal, @PathVariable UUID id) {
        Document document = documentService.get(id, principal.getId());
        ContentDisposition disposition = ContentDisposition.inline()
                .filename(document.filename(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(document.sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(new InputStreamResource(documentService.openFile(document.id())));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a document, its extracted text and its file")
    void delete(@AuthenticationPrincipal AccountPrincipal principal, @PathVariable UUID id) {
        documentService.delete(id, principal.getId());
    }

    record DocumentResponse(
            UUID id,
            String filename,
            long sizeBytes,
            DocumentStatus status,
            DocumentFailureReason failureReason,
            Integer pageCount,
            Instant createdAt) {

        static DocumentResponse from(Document document) {
            return new DocumentResponse(
                    document.id(),
                    document.filename(),
                    document.sizeBytes(),
                    document.status(),
                    document.failureReason(),
                    document.pageCount(),
                    document.createdAt());
        }
    }

    record DocumentListResponse(List<DocumentResponse> items, int page, int size, long totalItems) {}
}
