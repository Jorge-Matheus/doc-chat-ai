package com.docchat.chat;

import com.docchat.auth.AccountPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/conversations")
@Tag(name = "Conversations", description = "Questions and answers from the signed-in user's documents")
class ConversationController {

    private final ConversationService conversationService;

    ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @PostMapping
    @Operation(
            summary = "Start a conversation with its first question",
            description = "A turn has \"answered\": false when the documents do not contain the answer."
                    + " Answers 503 when the AI service is not available, and then nothing is stored.")
    ResponseEntity<ConversationResponse> start(
            @AuthenticationPrincipal AccountPrincipal principal, @Valid @RequestBody QuestionRequest request) {
        ConversationResponse conversation =
                ConversationResponse.from(conversationService.start(principal.getId(), request.question(), request.ids()));
        return ResponseEntity.created(URI.create("/api/conversations/" + conversation.id()))
                .body(conversation);
    }

    @PostMapping("/{id}/turns")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Ask another question in a conversation",
            description = "The question can refer to earlier ones, as in \"and the penalty?\"."
                    + " Answers 503 when the AI service is not available, and then nothing is stored.")
    Turn addTurn(
            @AuthenticationPrincipal AccountPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody QuestionRequest request) {
        return conversationService.addTurn(id, principal.getId(), request.question(), request.ids());
    }

    @GetMapping
    @Operation(summary = "List the user's conversations, most recently active first")
    ConversationListResponse list(
            @AuthenticationPrincipal AccountPrincipal principal,
            @Parameter(description = "Page number, starting at 0") @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        ConversationService.ConversationPage result = conversationService.list(principal.getId(), page, size);
        return new ConversationListResponse(result.items(), page, size, result.totalItems());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a conversation with all its questions and answers")
    ConversationResponse get(@AuthenticationPrincipal AccountPrincipal principal, @PathVariable UUID id) {
        return ConversationResponse.from(conversationService.get(id, principal.getId()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a conversation with all its questions and answers")
    void delete(@AuthenticationPrincipal AccountPrincipal principal, @PathVariable UUID id) {
        conversationService.delete(id, principal.getId());
    }

    /**
     * @param question at most 2000 characters
     * @param documentIds limits the question to these documents, or to all of them when absent
     */
    record QuestionRequest(@NotBlank @Size(max = 2000) String question, @Size(max = 50) List<UUID> documentIds) {

        List<UUID> ids() {
            return documentIds == null ? List.of() : documentIds;
        }
    }

    record ConversationResponse(UUID id, String title, Instant createdAt, Instant updatedAt, List<Turn> turns) {

        static ConversationResponse from(ConversationService.ConversationDetails details) {
            Conversation conversation = details.conversation();
            return new ConversationResponse(
                    conversation.id(),
                    conversation.title(),
                    conversation.createdAt(),
                    conversation.updatedAt(),
                    details.turns());
        }
    }

    record ConversationListResponse(List<Conversation> items, int page, int size, long totalItems) {}
}
