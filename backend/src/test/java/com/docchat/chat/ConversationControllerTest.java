package com.docchat.chat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.docchat.auth.AccountPrincipal;
import com.docchat.auth.SecurityConfig;
import com.docchat.chat.Turn.TurnCitation;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(ConversationController.class)
@Import(SecurityConfig.class)
class ConversationControllerTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final AccountPrincipal PRINCIPAL = new AccountPrincipal(USER_ID, "user@example.com", null);
    private static final UUID ID = UUID.randomUUID();
    private static final Instant TIME = Instant.parse("2026-11-03T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ConversationService service;

    @Test
    void startingAConversationAnswersWithTheContractUsedByTheFrontend() throws Exception {
        UUID document = UUID.randomUUID();
        Turn turn = new Turn(
                5,
                "The question",
                null,
                true,
                "The answer [1].",
                List.of(new TurnCitation(1, document, "a.pdf", 3, "The excerpt")),
                TIME);
        when(service.start(USER_ID, "The question", List.of(document)))
                .thenReturn(new ConversationService.ConversationDetails(
                        new Conversation(ID, "The question", TIME, TIME), List.of(turn)));

        mockMvc.perform(asUser(post("/api/conversations"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"The question\",\"documentIds\":[\"%s\"]}".formatted(document)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/conversations/" + ID))
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.title").value("The question"))
                .andExpect(jsonPath("$.createdAt").value("2026-11-03T12:00:00Z"))
                .andExpect(jsonPath("$.updatedAt").value("2026-11-03T12:00:00Z"))
                .andExpect(jsonPath("$.turns[0].id").value(5))
                .andExpect(jsonPath("$.turns[0].question").value("The question"))
                .andExpect(jsonPath("$.turns[0].interpretedQuestion").isEmpty())
                .andExpect(jsonPath("$.turns[0].answered").value(true))
                .andExpect(jsonPath("$.turns[0].text").value("The answer [1]."))
                .andExpect(jsonPath("$.turns[0].createdAt").value("2026-11-03T12:00:00Z"))
                .andExpect(jsonPath("$.turns[0].citations[0].number").value(1))
                .andExpect(jsonPath("$.turns[0].citations[0].documentId").value(document.toString()))
                .andExpect(jsonPath("$.turns[0].citations[0].filename").value("a.pdf"))
                .andExpect(jsonPath("$.turns[0].citations[0].pageNumber").value(3))
                .andExpect(jsonPath("$.turns[0].citations[0].excerpt").value("The excerpt"));
    }

    @Test
    void aFollowUpWithoutDocumentsCoversAllOfThem() throws Exception {
        when(service.addTurn(ID, USER_ID, "And then?", List.of()))
                .thenReturn(new Turn(6, "And then?", "What then?", false, null, List.of(), TIME));

        mockMvc.perform(asUser(post("/api/conversations/" + ID + "/turns"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"And then?\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.interpretedQuestion").value("What then?"))
                .andExpect(jsonPath("$.answered").value(false))
                .andExpect(jsonPath("$.text").isEmpty())
                .andExpect(jsonPath("$.citations").isEmpty());
    }

    @Test
    void refusesAnInvalidQuestion() throws Exception {
        for (String body : List.of(
                "{}",
                "{\"question\":\"   \"}",
                "{\"question\":\"%s\"}".formatted("a".repeat(2001)),
                "{\"question\":\"Q\",\"documentIds\":[\"not-a-uuid\"]}")) {
            mockMvc.perform(asUser(post("/api/conversations"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    @Test
    void acceptsAQuestionOfTheLongestAllowedLength() throws Exception {
        when(service.start(eq(USER_ID), any(), any()))
                .thenReturn(new ConversationService.ConversationDetails(
                        new Conversation(ID, "Title", TIME, TIME), List.of()));

        mockMvc.perform(asUser(post("/api/conversations"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"%s\"}".formatted("a".repeat(2000))))
                .andExpect(status().isCreated());
    }

    @Test
    void listReturnsPagingInformationWithoutTheOwner() throws Exception {
        when(service.list(USER_ID, 2, 5))
                .thenReturn(new ConversationService.ConversationPage(
                        List.of(new Conversation(ID, "Title", TIME, TIME)), 11));

        mockMvc.perform(get("/api/conversations")
                        .param("page", "2")
                        .param("size", "5")
                        .with(user(PRINCIPAL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(ID.toString()))
                .andExpect(jsonPath("$.items[0].title").value("Title"))
                .andExpect(jsonPath("$.items[0].ownerId").doesNotExist())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.totalItems").value(11));
    }

    @Test
    void listRejectsPageSizesOutOfRange() throws Exception {
        mockMvc.perform(get("/api/conversations").param("size", "101").with(user(PRINCIPAL)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/conversations").param("page", "-1").with(user(PRINCIPAL)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void everyRouteRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/conversations")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/conversations/" + ID)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/conversations").with(csrf())).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/conversations/" + ID + "/turns").with(csrf())).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/conversations/" + ID).with(csrf())).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void changesRequireTheCsrfToken() throws Exception {
        String body = "{\"question\":\"Q\"}";
        mockMvc.perform(post("/api/conversations")
                        .with(user(PRINCIPAL))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/conversations/" + ID + "/turns")
                        .with(user(PRINCIPAL))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/conversations/" + ID).with(user(PRINCIPAL)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    private static MockHttpServletRequestBuilder asUser(MockHttpServletRequestBuilder request) {
        return request.with(user(PRINCIPAL)).with(csrf());
    }
}
