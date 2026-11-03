package com.docchat.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.docchat.ai.AiUnavailableException;
import com.docchat.common.web.ApiException;
import com.docchat.rag.AnswerService;
import com.docchat.rag.AnswerService.Answer;
import com.docchat.rag.AnswerService.PreviousTurn;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

class ConversationServiceTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID CONVERSATION = UUID.randomUUID();
    private static final Answer ANSWER = new Answer(null, "An answer [1].", List.of());

    private final ConversationRepository repository = mock(ConversationRepository.class);
    private final AnswerService answerService = mock(AnswerService.class);
    private final ConversationService service = new ConversationService(repository, answerService);

    @Test
    void aFirstQuestionIsAnsweredWithoutHistoryThenStored() {
        when(answerService.answer(OWNER, "The question", List.of(), List.of())).thenReturn(ANSWER);
        when(repository.findOwned(any(), eq(OWNER))).thenReturn(Optional.of(conversation()));

        service.start(OWNER, "  The question ", List.of());

        verify(repository).insertWithFirstTurn(any(), eq(OWNER), eq("The question"), eq("The question"), eq(ANSWER));
    }

    @Test
    void nothingIsStoredWhenTheModelIsUnavailable() {
        when(answerService.answer(any(), anyString(), any(), any()))
                .thenThrow(new AiUnavailableException(new RuntimeException()));

        assertThatThrownBy(() -> service.start(OWNER, "The question", List.of()))
                .isInstanceOf(AiUnavailableException.class);

        verify(repository, never()).insertWithFirstTurn(any(), any(), anyString(), anyString(), any());
    }

    @Test
    void aFollowUpIsAnsweredWithTheRecentTurns() {
        List<PreviousTurn> history = List.of(new PreviousTurn("Earlier", "Before"));
        when(repository.findOwned(CONVERSATION, OWNER)).thenReturn(Optional.of(conversation()));
        when(repository.findRecentTurns(CONVERSATION, OWNER, AnswerService.HISTORY_TURNS)).thenReturn(history);
        when(answerService.answer(OWNER, "And then?", List.of(), history)).thenReturn(ANSWER);
        when(repository.appendTurn(CONVERSATION, OWNER, "And then?", ANSWER)).thenReturn(Optional.of(7L));
        when(repository.findTurns(CONVERSATION, OWNER)).thenReturn(List.of(turn(6), turn(7), turn(8)));

        assertThat(service.addTurn(CONVERSATION, OWNER, "And then?", List.of()).id()).isEqualTo(7);
    }

    @Test
    void aFollowUpInAConversationOfAnotherUserDoesNotReachTheModel() {
        when(repository.findOwned(CONVERSATION, OWNER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addTurn(CONVERSATION, OWNER, "And then?", List.of()))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("CONVERSATION_NOT_FOUND"));

        verifyNoInteractions(answerService);
    }

    @Test
    void aConversationDeletedWhileTheModelAnsweredIsReportedAsNotFound() {
        when(repository.findOwned(CONVERSATION, OWNER)).thenReturn(Optional.of(conversation()));
        when(answerService.answer(any(), anyString(), any(), any())).thenReturn(ANSWER);
        when(repository.appendTurn(any(), any(), anyString(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addTurn(CONVERSATION, OWNER, "And then?", List.of()))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void refusesAQuestionMadeOnlyOfCharactersThatCannotBeStored() {
        assertThatThrownBy(() -> service.start(OWNER, "\u0000\uD83D", List.of()))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.getCode()).isEqualTo("INVALID_QUESTION"));

        verifyNoInteractions(answerService);
    }

    @Test
    void removesCharactersThatCannotBeStoredFromTheQuestionAndTheAnswer() {
        when(answerService.answer(OWNER, "Question", List.of(), List.of()))
                .thenReturn(new Answer("Inter\u0000preted", "An\u0000swer [1].", List.of()));
        when(repository.findOwned(any(), eq(OWNER))).thenReturn(Optional.of(conversation()));

        service.start(OWNER, "Ques\u0000tion", List.of());

        ArgumentCaptor<Answer> stored = ArgumentCaptor.forClass(Answer.class);
        verify(repository).insertWithFirstTurn(any(), any(), anyString(), eq("Question"), stored.capture());
        assertThat(stored.getValue().text()).isEqualTo("Answer [1].");
        assertThat(stored.getValue().interpretedQuestion()).isEqualTo("Interpreted");
    }

    @Test
    void theTitleIsOneShortLine() {
        assertThat(ConversationService.titleFrom("Short question?")).isEqualTo("Short question?");
        assertThat(ConversationService.titleFrom("Two\n\n  lines?")).isEqualTo("Two lines?");
        assertThat(ConversationService.titleFrom("a".repeat(80))).isEqualTo("a".repeat(80));
        assertThat(ConversationService.titleFrom("a".repeat(81))).isEqualTo("a".repeat(80) + "...");
    }

    @Test
    void theTitleNeverCutsACharacterInTwo() {
        String title = ConversationService.titleFrom("😀".repeat(81));

        assertThat(title).isEqualTo("😀".repeat(80) + "...");
    }

    @Test
    void deletingAConversationThatIsNotThereIsNotFound() {
        when(repository.deleteOwned(CONVERSATION, OWNER)).thenReturn(false);

        assertThatThrownBy(() -> service.delete(CONVERSATION, OWNER))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void aLargePageNumberDoesNotOverflow() {
        when(repository.findPageOwnedBy(eq(OWNER), anyInt(), anyLong())).thenReturn(List.of());

        service.list(OWNER, Integer.MAX_VALUE, 100);

        verify(repository).findPageOwnedBy(OWNER, 100, Integer.MAX_VALUE * 100L);
    }

    private static Conversation conversation() {
        return new Conversation(CONVERSATION, "Title", Instant.EPOCH, Instant.EPOCH);
    }

    private static Turn turn(long id) {
        return new Turn(id, "Question", null, true, "Answer [1].", List.of(), Instant.EPOCH);
    }
}
