package com.docchat.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.docchat.TestcontainersConfiguration;
import com.docchat.rag.AnswerService.Answer;
import com.docchat.rag.AnswerService.Citation;
import com.docchat.testsupport.ApiClient;
import com.docchat.testsupport.FakeAiConfiguration;
import com.docchat.testsupport.FakeChatModel;
import com.docchat.testsupport.TestPdfs;
import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Real HTTP, real SQL and fake models. Requires Docker. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, FakeAiConfiguration.class})
class ConversationFlowIT {

    private static final String PASSWORD = "correct horse battery";
    private static final String NOTICE_QUESTION = "What is the termination notice?";
    private static final String NOTICE_TEXT = "Termination requires sixty days notice";

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FakeChatModel chatModel;

    @Autowired
    private ConversationRepository repository;

    private ApiClient alice;
    private String document;

    @BeforeEach
    void setUp() {
        alice = signedInUser();
        document = upload(alice, "contract.pdf", NOTICE_TEXT);
    }

    @AfterEach
    void restoreModels() {
        chatModel.reset();
    }

    @Test
    void aConversationKeepsItsQuestionsAndAnswersWithTheirSources() {
        String id = start(alice, NOTICE_QUESTION);
        chatModel.setReply("Sixty days [1].");
        assertThat(addTurn(alice, id, NOTICE_QUESTION).statusCode()).isEqualTo(201);

        String body = alice.get("/api/conversations/" + id).body();

        assertThat((String) JsonPath.read(body, "$.title")).isEqualTo(NOTICE_QUESTION);
        List<String> texts = JsonPath.read(body, "$.turns[*].text");
        assertThat(texts).containsExactly(FakeChatModel.DEFAULT_REPLY, "Sixty days [1].");
        assertThat((String) JsonPath.read(body, "$.turns[1].citations[0].documentId")).isEqualTo(document);
        assertThat((String) JsonPath.read(body, "$.turns[1].citations[0].filename")).isEqualTo("contract.pdf");
        assertThat((Integer) JsonPath.read(body, "$.turns[1].citations[0].pageNumber")).isEqualTo(1);
        assertThat((String) JsonPath.read(body, "$.turns[1].citations[0].excerpt")).isEqualTo(NOTICE_TEXT);
    }

    @Test
    void aFollowUpIsUnderstoodThroughTheConversation() {
        String id = start(alice, NOTICE_QUESTION);
        chatModel.setRewriteReply(NOTICE_QUESTION);

        HttpResponse<String> response = addTurn(alice, id, "And when?");

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat((String) JsonPath.read(response.body(), "$.question")).isEqualTo("And when?");
        assertThat((String) JsonPath.read(response.body(), "$.interpretedQuestion")).isEqualTo(NOTICE_QUESTION);
        assertThat((Boolean) JsonPath.read(response.body(), "$.answered")).isTrue();
        assertThat(chatModel.lastRewritePrompt())
                .contains("Q: " + NOTICE_QUESTION)
                .contains("A: The answer is in the first source.")
                .contains("Latest question: And when?");
        assertThat(chatModel.lastPrompt()).contains("Question: " + NOTICE_QUESTION).doesNotContain("And when?");
    }

    @Test
    void aFollowUpThatDoesNotNeedRewritingHasNoInterpretation() {
        String id = start(alice, NOTICE_QUESTION);

        String body = addTurn(alice, id, "Termination notice?").body();

        assertThat((Object) JsonPath.read(body, "$.interpretedQuestion")).isNull();
    }

    @Test
    void aFirstQuestionIsNotRewritten() {
        int rewritesBefore = chatModel.rewriteCalls();

        start(alice, NOTICE_QUESTION);

        assertThat(chatModel.rewriteCalls()).isEqualTo(rewritesBefore);
    }

    @Test
    void aQuestionThatWasNotAnsweredStaysInTheConversation() {
        String id = start(alice, "Favourite pizza topping recipes");

        String body = alice.get("/api/conversations/" + id).body();
        assertThat((Boolean) JsonPath.read(body, "$.turns[0].answered")).isFalse();
        assertThat((Object) JsonPath.read(body, "$.turns[0].text")).isNull();

        addTurn(alice, id, "And for dessert?");
        assertThat(chatModel.lastRewritePrompt()).contains("A: (not found in the documents)");
    }

    @Test
    void onlyTheMostRecentTurnsAreUsedToUnderstandAFollowUp() {
        String id = start(alice, "Question number one");
        addTurn(alice, id, "Question number two");
        addTurn(alice, id, "Question number three");
        addTurn(alice, id, "Question number four");

        addTurn(alice, id, "Question number five");

        assertThat(chatModel.lastRewritePrompt())
                .doesNotContain("Q: Question number one")
                .contains("Q: Question number two\nA:")
                .contains("Q: Question number three\nA:")
                .contains("Q: Question number four\nA:");
    }

    @Test
    void conversationsAreListedByLatestActivity() {
        ApiClient carol = signedInUser();
        String first = start(carol, "First question");
        String second = start(carol, "Second question");

        addTurn(carol, first, "Another question");

        String list = carol.get("/api/conversations").body();
        List<String> ids = JsonPath.read(list, "$.items[*].id");
        assertThat(ids).containsExactly(first, second);
        assertThat((Integer) JsonPath.read(list, "$.totalItems")).isEqualTo(2);

        String page = carol.get("/api/conversations?page=1&size=1").body();
        List<String> pagedIds = JsonPath.read(page, "$.items[*].id");
        assertThat(pagedIds).containsExactly(second);
    }

    @Test
    void otherUsersCannotReachAConversation() {
        String id = start(alice, NOTICE_QUESTION);
        ApiClient bob = signedInUser();
        int callsBefore = chatModel.calls();

        assertThat(bob.get("/api/conversations/" + id).statusCode()).isEqualTo(404);
        HttpResponse<String> followUp = addTurn(bob, id, "And then?");
        assertThat(followUp.statusCode()).isEqualTo(404);
        assertThat((String) JsonPath.read(followUp.body(), "$.code")).isEqualTo("CONVERSATION_NOT_FOUND");
        assertThat(bob.delete("/api/conversations/" + id).statusCode()).isEqualTo(404);
        assertThat((Integer) JsonPath.read(bob.get("/api/conversations").body(), "$.totalItems")).isZero();

        assertThat(chatModel.calls()).as("the model must not be asked").isEqualTo(callsBefore);
        assertThat(alice.get("/api/conversations/" + id).statusCode()).isEqualTo(200);
        assertThat(turnCount(id)).isEqualTo(1);
    }

    /** The service already checks the owner first, so this proves each query also does it by itself. */
    @Test
    void everyQueryOfTheRepositoryIsLimitedToTheOwner() {
        UUID id = UUID.fromString(start(alice, NOTICE_QUESTION));
        UUID stranger = UUID.randomUUID();
        Answer answer = new Answer(null, "Text.", List.of());

        assertThat(repository.findOwned(id, stranger)).isEmpty();
        assertThat(repository.findTurns(id, stranger)).isEmpty();
        assertThat(repository.findRecentTurns(id, stranger, 3)).isEmpty();
        assertThat(repository.findPageOwnedBy(stranger, 10, 0)).isEmpty();
        assertThat(repository.countOwnedBy(stranger)).isZero();
        assertThat(repository.appendTurn(id, stranger, "Question", answer)).isEmpty();
        assertThat(repository.deleteOwned(id, stranger)).isFalse();
        assertThat(turnCount(id.toString())).isEqualTo(1);
    }

    @Test
    void conversationsAndTheirSourcesRequireAuthentication() {
        String id = start(alice, NOTICE_QUESTION);
        ApiClient anonymous = new ApiClient(port);

        assertThat(anonymous.get("/api/conversations").statusCode()).isEqualTo(401);
        assertThat(anonymous.get("/api/conversations/" + id).statusCode()).isEqualTo(401);
        assertThat(alice.postJsonWithoutCsrf("/api/conversations", "{\"question\":\"Anything\"}").statusCode())
                .isEqualTo(403);
    }

    @Test
    void deletingACitedDocumentKeepsTheAnswerAndRemovesThePassageText() {
        String other = upload(alice, "other.pdf", "Termination of the other agreement requires ninety days");
        String id = start(alice, "Termination requires notice");
        chatModel.setReply("From both sources [1] [2].");
        String turn = addTurn(alice, id, "Termination requires notice").body();
        assertThat((List<?>) JsonPath.read(turn, "$.citations")).hasSize(2);

        assertThat(alice.delete("/api/documents/" + document).statusCode()).isEqualTo(204);

        String body = alice.get("/api/conversations/" + id).body();
        assertThat((String) JsonPath.read(body, "$.turns[1].text")).isEqualTo("From both sources [1] [2].");
        assertThat(body).doesNotContain(NOTICE_TEXT).contains("ninety days");
        List<String> filenames = JsonPath.read(body, "$.turns[1].citations[*].filename");
        assertThat(filenames).containsExactlyInAnyOrder("contract.pdf", "other.pdf");
        List<Object> documentIds = JsonPath.read(body, "$.turns[1].citations[?(@.filename == 'contract.pdf')].documentId");
        assertThat(documentIds).containsOnlyNulls();
        List<Object> excerpts = JsonPath.read(body, "$.turns[1].citations[?(@.filename == 'contract.pdf')].excerpt");
        assertThat(excerpts).containsOnlyNulls();
        List<Integer> pages = JsonPath.read(body, "$.turns[1].citations[?(@.filename == 'contract.pdf')].pageNumber");
        assertThat(pages).containsOnly(1);
        List<String> kept = JsonPath.read(body, "$.turns[1].citations[?(@.filename == 'other.pdf')].documentId");
        assertThat(kept).containsOnly(other);
    }

    @Test
    void aDocumentDeletedWhileTheModelAnswersIsCitedAsDeleted() {
        chatModel.setDuringAnswer(() -> assertThat(alice.delete("/api/documents/" + document).statusCode())
                .isEqualTo(204));

        HttpResponse<String> response = alice.postJson(
                "/api/conversations", "{\"question\":\"%s\"}".formatted(NOTICE_QUESTION));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat((Object) JsonPath.read(response.body(), "$.turns[0].citations[0].documentId")).isNull();
        assertThat((Object) JsonPath.read(response.body(), "$.turns[0].citations[0].excerpt")).isNull();
        assertThat((String) JsonPath.read(response.body(), "$.turns[0].citations[0].filename")).isEqualTo("contract.pdf");
    }

    @Test
    void aConversationDeletedWhileTheModelAnswersGetsNoNewTurn() {
        String id = start(alice, NOTICE_QUESTION);
        chatModel.setDuringAnswer(() -> assertThat(alice.delete("/api/conversations/" + id).statusCode())
                .isEqualTo(204));

        HttpResponse<String> response = addTurn(alice, id, NOTICE_QUESTION);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(turnCount(id)).isZero();
    }

    @Test
    void deletingAConversationRemovesItsTurnsAndCitationsButNotTheDocument() {
        String id = start(alice, NOTICE_QUESTION);
        assertThat(citationCount(id)).isEqualTo(1);

        assertThat(alice.delete("/api/conversations/" + id).statusCode()).isEqualTo(204);

        assertThat(alice.get("/api/conversations/" + id).statusCode()).isEqualTo(404);
        assertThat(alice.delete("/api/conversations/" + id).statusCode()).isEqualTo(404);
        assertThat(turnCount(id)).isZero();
        assertThat(citationCount(id)).isZero();
        assertThat(alice.get("/api/documents/" + document).statusCode()).isEqualTo(200);
    }

    @Test
    void nothingIsStoredWhenTheModelIsUnavailable() {
        long conversationsBefore = conversationCount();
        chatModel.setFailing(true);

        HttpResponse<String> response =
                alice.postJson("/api/conversations", "{\"question\":\"%s\"}".formatted(NOTICE_QUESTION));

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat((String) JsonPath.read(response.body(), "$.code")).isEqualTo("AI_UNAVAILABLE");
        assertThat(response.body()).doesNotContain("Simulated");
        assertThat(conversationCount()).isEqualTo(conversationsBefore);
    }

    @Test
    void aFollowUpIsNotStoredWhenTheModelFails() {
        String id = start(alice, NOTICE_QUESTION);

        chatModel.setFailingOnRewrite(true);
        assertThat(addTurn(alice, id, "And then?").statusCode()).isEqualTo(503);
        assertThat(turnCount(id)).isEqualTo(1);

        chatModel.setFailingOnRewrite(false);
        chatModel.setFailing(true);
        assertThat(addTurn(alice, id, NOTICE_QUESTION).statusCode()).isEqualTo(503);
        assertThat(turnCount(id)).isEqualTo(1);
    }

    @Test
    void charactersTheDatabaseRejectsDoNotBreakAQuestion() {
        HttpResponse<String> response = alice.postJson(
                "/api/conversations", "{\"question\":\"What is the\\u0000 termination\\uD83D notice?\"}");

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat((String) JsonPath.read(response.body(), "$.turns[0].question"))
                .isEqualTo(NOTICE_QUESTION);

        HttpResponse<String> nothingLeft = alice.postJson("/api/conversations", "{\"question\":\"\\u0000\"}");
        assertThat(nothingLeft.statusCode()).isEqualTo(400);
        assertThat((String) JsonPath.read(nothingLeft.body(), "$.code")).isEqualTo("INVALID_QUESTION");
    }

    @Test
    void aFailureWhileSavingLeavesNoPartialConversation() {
        UUID id = UUID.randomUUID();
        UUID owner = jdbcTemplate.queryForObject(
                "SELECT owner_id FROM documents WHERE id = ?::uuid", UUID.class, document);
        Citation citation = new Citation(1, UUID.fromString(document), "contract.pdf", 1, 0);
        Answer repeatedNumber = new Answer(null, "Text [1].", List.of(citation, citation));

        assertThatThrownBy(() -> repository.insertWithFirstTurn(id, owner, "Title", "Question", repeatedNumber))
                .isInstanceOf(DataAccessException.class);

        assertThat(repository.findOwned(id, owner)).isEmpty();
        assertThat(turnCount(id.toString())).isZero();
    }

    private ApiClient signedInUser() {
        ApiClient client = new ApiClient(port);
        String email = "user-" + UUID.randomUUID() + "@example.com";
        assertThat(client.register(email, PASSWORD).statusCode()).isEqualTo(201);
        assertThat(client.login(email, PASSWORD).statusCode()).isEqualTo(204);
        return client;
    }

    private String upload(ApiClient client, String filename, String... pages) {
        HttpResponse<String> response = client.upload(filename, TestPdfs.withPages(pages));
        assertThat(response.statusCode()).isEqualTo(202);
        String id = JsonPath.read(response.body(), "$.id");
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> "READY".equals(JsonPath.read(client.get("/api/documents/" + id).body(), "$.status")));
        return id;
    }

    private String start(ApiClient client, String question) {
        HttpResponse<String> response = client.postJson("/api/conversations", "{\"question\":\"%s\"}".formatted(question));
        assertThat(response.statusCode()).isEqualTo(201);
        return JsonPath.read(response.body(), "$.id");
    }

    private static HttpResponse<String> addTurn(ApiClient client, String id, String question) {
        return client.postJson("/api/conversations/" + id + "/turns", "{\"question\":\"%s\"}".formatted(question));
    }

    private long conversationCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM conversations", Long.class);
    }

    private int turnCount(String conversationId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM conversation_turns WHERE conversation_id = ?::uuid", Integer.class, conversationId);
    }

    private int citationCount(String conversationId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM turn_citations c
                JOIN conversation_turns t ON t.id = c.turn_id
                WHERE t.conversation_id = ?::uuid
                """,
                Integer.class,
                conversationId);
    }
}
