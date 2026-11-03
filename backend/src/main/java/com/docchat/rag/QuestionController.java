package com.docchat.rag;

import com.docchat.auth.AccountPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/questions")
@Tag(name = "Questions", description = "Answers from the signed-in user's documents")
class QuestionController {

    private final AnswerService answerService;

    QuestionController(AnswerService answerService) {
        this.answerService = answerService;
    }

    @PostMapping
    @Operation(
            summary = "Ask a question about the user's documents",
            description = "Answers with \"answered\": false when the documents do not contain the answer,"
                    + " and with 503 when the AI service is not available.")
    AnswerService.Answer ask(
            @AuthenticationPrincipal AccountPrincipal principal, @Valid @RequestBody QuestionRequest request) {
        List<UUID> documentIds = request.documentIds() == null ? List.of() : request.documentIds();
        return answerService.ask(principal.getId(), request.question(), documentIds);
    }

    /**
     * @param question at most 2000 characters
     * @param documentIds limits the question to these documents, or to all of them when absent
     */
    record QuestionRequest(@NotBlank @Size(max = 2000) String question, @Size(max = 50) List<UUID> documentIds) {}
}
