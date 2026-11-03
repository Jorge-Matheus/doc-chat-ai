package com.docchat.chat;

import com.docchat.common.db.PgText;
import com.docchat.common.web.ApiException;
import com.docchat.rag.AnswerService;
import com.docchat.rag.AnswerService.Answer;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Stores each answer once the model has finished, outside of any transaction, because answering can
 * take minutes. If the model fails, nothing is stored and the user can ask again.
 */
@Service
class ConversationService {

    private static final int MAX_TITLE_LENGTH = 80;

    private final ConversationRepository repository;
    private final AnswerService answerService;

    ConversationService(ConversationRepository repository, AnswerService answerService) {
        this.repository = repository;
        this.answerService = answerService;
    }

    record ConversationDetails(Conversation conversation, List<Turn> turns) {}

    record ConversationPage(List<Conversation> items, long totalItems) {}

    ConversationDetails start(UUID ownerId, String rawQuestion, List<UUID> documentIds) {
        String question = cleanQuestion(rawQuestion);
        Answer answer = cleaned(answerService.answer(ownerId, question, documentIds, List.of()));
        UUID id = UUID.randomUUID();
        repository.insertWithFirstTurn(id, ownerId, titleFrom(question), question, answer);
        return get(id, ownerId);
    }

    Turn addTurn(UUID id, UUID ownerId, String rawQuestion, List<UUID> documentIds) {
        String question = cleanQuestion(rawQuestion);
        repository.findOwned(id, ownerId).orElseThrow(ConversationService::notFound);
        var history = repository.findRecentTurns(id, ownerId, AnswerService.HISTORY_TURNS);
        Answer answer = cleaned(answerService.answer(ownerId, question, documentIds, history));
        long turnId = repository
                .appendTurn(id, ownerId, question, answer)
                .orElseThrow(ConversationService::notFound);
        return repository.findTurns(id, ownerId).stream()
                .filter(turn -> turn.id() == turnId)
                .findFirst()
                .orElseThrow(ConversationService::notFound);
    }

    ConversationDetails get(UUID id, UUID ownerId) {
        Conversation conversation = repository.findOwned(id, ownerId).orElseThrow(ConversationService::notFound);
        return new ConversationDetails(conversation, repository.findTurns(id, ownerId));
    }

    ConversationPage list(UUID ownerId, int page, int size) {
        List<Conversation> items = repository.findPageOwnedBy(ownerId, size, (long) page * size);
        return new ConversationPage(items, repository.countOwnedBy(ownerId));
    }

    void delete(UUID id, UUID ownerId) {
        if (!repository.deleteOwned(id, ownerId)) {
            throw notFound();
        }
    }

    /** One line, cut at a whole character, because it is only used to recognise the conversation. */
    static String titleFrom(String question) {
        String line = question.replaceAll("\\s+", " ");
        if (line.codePointCount(0, line.length()) <= MAX_TITLE_LENGTH) {
            return line;
        }
        return line.substring(0, line.offsetByCodePoints(0, MAX_TITLE_LENGTH)).strip() + "...";
    }

    /** Validation does not see these characters as blank, but they cannot be stored. */
    private static String cleanQuestion(String question) {
        String cleaned = PgText.clean(question);
        if (cleaned.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_QUESTION", "Write your question first.");
        }
        return cleaned;
    }

    private static Answer cleaned(Answer answer) {
        return new Answer(
                answer.interpretedQuestion() == null ? null : PgText.clean(answer.interpretedQuestion()),
                answer.text() == null ? null : PgText.clean(answer.text()),
                answer.citations());
    }

    /** A conversation of another user gets the same answer, so its existence is not revealed. */
    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "CONVERSATION_NOT_FOUND", "The conversation was not found.");
    }
}
