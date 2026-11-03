package com.docchat.document;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.docchat.auth.AccountPrincipal;
import com.docchat.auth.SecurityConfig;
import com.docchat.common.web.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DocumentController.class)
@Import(SecurityConfig.class)
class DocumentControllerTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final AccountPrincipal PRINCIPAL = new AccountPrincipal(USER_ID, "user@example.com", null);
    private static final MockMultipartFile FILE =
            new MockMultipartFile("file", "a.pdf", "application/pdf", new byte[] {1});

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DocumentService service;

    @Test
    void uploadAnswersAcceptedWithTheContractUsedByTheFrontend() throws Exception {
        Document document = new Document(
                UUID.randomUUID(),
                USER_ID,
                "a.pdf",
                1,
                DocumentStatus.PROCESSING,
                null,
                null,
                Instant.parse("2026-11-03T12:00:00Z"));
        when(service.upload(eq(USER_ID), any())).thenReturn(document);

        mockMvc.perform(multipart("/api/documents").file(FILE).with(user(PRINCIPAL)).with(csrf()))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/documents/" + document.id()))
                .andExpect(jsonPath("$.id").value(document.id().toString()))
                .andExpect(jsonPath("$.filename").value("a.pdf"))
                .andExpect(jsonPath("$.sizeBytes").value(1))
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.failureReason").isEmpty())
                .andExpect(jsonPath("$.pageCount").isEmpty())
                .andExpect(jsonPath("$.createdAt").value("2026-11-03T12:00:00Z"))
                .andExpect(jsonPath("$.ownerId").doesNotExist());
    }

    @Test
    void uploadRequiresAuthentication() throws Exception {
        mockMvc.perform(multipart("/api/documents").file(FILE).with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verifyNoInteractions(service);
    }

    @Test
    void uploadRequiresTheCsrfToken() throws Exception {
        mockMvc.perform(multipart("/api/documents").file(FILE).with(user(PRINCIPAL)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verifyNoInteractions(service);
    }

    @Test
    void listRejectsPageSizesOutOfRange() throws Exception {
        mockMvc.perform(get("/api/documents").param("size", "101").with(user(PRINCIPAL)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/documents").param("page", "-1").with(user(PRINCIPAL)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void listReturnsPagingInformation() throws Exception {
        when(service.list(USER_ID, 2, 5)).thenReturn(new DocumentService.DocumentPage(List.of(), 11));

        mockMvc.perform(get("/api/documents")
                        .param("page", "2")
                        .param("size", "5")
                        .with(user(PRINCIPAL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.totalItems").value(11));
    }

    @Test
    void anInvalidIdIsABadRequest() throws Exception {
        mockMvc.perform(get("/api/documents/not-a-uuid").with(user(PRINCIPAL)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void expectedFailuresKeepTheirStatusAndCode() throws Exception {
        UUID id = UUID.randomUUID();
        doThrow(new ApiException(HttpStatus.NOT_FOUND, "DOCUMENT_NOT_FOUND", "The document was not found."))
                .when(service)
                .delete(id, USER_ID);

        mockMvc.perform(delete("/api/documents/" + id).with(user(PRINCIPAL)).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("The document was not found."));
    }
}
