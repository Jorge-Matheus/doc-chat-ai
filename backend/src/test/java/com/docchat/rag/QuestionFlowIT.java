package com.docchat.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.docchat.TestcontainersConfiguration;
import com.docchat.testsupport.ApiClient;
import com.docchat.testsupport.FakeAiConfiguration;
import com.docchat.testsupport.FakeChatModel;
import com.docchat.testsupport.FakeEmbeddingModel;
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
import org.springframework.jdbc.core.JdbcTemplate;

/** Real HTTP, real vector search in PostgreSQL and fake models. Requires Docker. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, FakeAiConfiguration.class})
class QuestionFlowIT {

    private static final String PASSWORD = "correct horse battery";

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FakeChatModel chatModel;

    @Autowired
    private FakeEmbeddingModel embeddingModel;

    private ApiClient alice;

    @BeforeEach
    void setUp() {
        alice = signedInUser();
    }

    @AfterEach
    void restoreModels() {
        chatModel.reset();
        embeddingModel.setFailing(false);
    }

    @Test
    void anAnswerCitesTheRealFileAndPageOfThePassageTheModelUsed() {
        upload(alice, "contract.pdf", "Payment terms are thirty days", "Termination requires sixty days notice");

        String body = ask(alice, "What is the termination notice?").body();

        assertThat((Boolean) JsonPath.read(body, "$.turns[0].answered")).isTrue();
        assertThat((String) JsonPath.read(body, "$.turns[0].text")).isEqualTo(FakeChatModel.DEFAULT_REPLY);
        assertThat((Integer) JsonPath.read(body, "$.turns[0].citations[0].number")).isEqualTo(1);
        assertThat((String) JsonPath.read(body, "$.turns[0].citations[0].filename")).isEqualTo("contract.pdf");
        assertThat((Integer) JsonPath.read(body, "$.turns[0].citations[0].pageNumber")).isEqualTo(2);
        assertThat((String) JsonPath.read(body, "$.turns[0].citations[0].excerpt")).contains("sixty days notice");
    }

    @Test
    void theModelNeverSeesFileNamesOrPageNumbers() {
        upload(alice, "very-secret-name.pdf", "Termination requires sixty days notice");

        ask(alice, "What is the termination notice?");

        assertThat(chatModel.lastPrompt())
                .contains("sixty days notice")
                .contains("<source number=\"1\">")
                .doesNotContain("very-secret-name")
                .doesNotContain("page");
    }

    @Test
    void aSearchNeverReachesAnotherUsersPassages() {
        upload(alice, "alice.pdf", "Project orchid budget is confidential");
        ApiClient bob = signedInUser();
        int callsBefore = chatModel.calls();

        String body = ask(bob, "Project orchid budget is confidential").body();

        assertThat((Boolean) JsonPath.read(body, "$.turns[0].answered")).isFalse();
        assertThat((List<?>) JsonPath.read(body, "$.turns[0].citations")).isEmpty();
        assertThat(chatModel.calls()).as("the model must not be asked").isEqualTo(callsBefore);
    }

    @Test
    void namingAnotherUsersDocumentDoesNotReachIt() {
        String aliceDocument = upload(alice, "alice.pdf", "Project orchid budget is confidential");
        ApiClient bob = signedInUser();

        HttpResponse<String> response = bob.postJson(
                "/api/conversations",
                "{\"question\":\"Project orchid budget\",\"documentIds\":[\"%s\"]}".formatted(aliceDocument));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat((Boolean) JsonPath.read(response.body(), "$.turns[0].answered")).isFalse();
    }

    @Test
    void aQuestionCanBeLimitedToChosenDocuments() {
        upload(alice, "first.pdf", "Project orchid budget details");
        String second = upload(alice, "second.pdf", "Project orchid schedule details");

        HttpResponse<String> response = alice.postJson(
                "/api/conversations",
                "{\"question\":\"Project orchid budget details\",\"documentIds\":[\"%s\"]}".formatted(second));

        assertThat((String) JsonPath.read(response.body(), "$.turns[0].citations[0].filename")).isEqualTo("second.pdf");
    }

    @Test
    void unrelatedQuestionsAreNotSentToTheModel() {
        upload(alice, "contract.pdf", "Termination requires sixty days notice");
        int callsBefore = chatModel.calls();

        String body = ask(alice, "Favourite pizza topping recipes").body();

        assertThat((Boolean) JsonPath.read(body, "$.turns[0].answered")).isFalse();
        assertThat(chatModel.calls()).isEqualTo(callsBefore);
    }

    @Test
    void theModelCanSayTheAnswerIsNotInTheDocuments() {
        upload(alice, "contract.pdf", "Termination requires sixty days notice");
        chatModel.setReply("NOT_FOUND");

        String body = ask(alice, "What is the termination notice?").body();

        assertThat((Boolean) JsonPath.read(body, "$.turns[0].answered")).isFalse();
    }

    @Test
    void anAnswerWithoutAValidCitationIsNotShown() {
        upload(alice, "contract.pdf", "Termination requires sixty days notice");

        chatModel.setReply("It is ninety days, from what I remember.");
        assertThat((Boolean) JsonPath.read(ask(alice, "What is the termination notice?").body(), "$.turns[0].answered"))
                .isFalse();

        chatModel.setReply("It is ninety days [9].");
        assertThat((Boolean) JsonPath.read(ask(alice, "What is the termination notice?").body(), "$.turns[0].answered"))
                .isFalse();
    }

    @Test
    void anUnavailableModelIsReportedWithoutInternalDetails() {
        upload(alice, "contract.pdf", "Termination requires sixty days notice");
        chatModel.setFailing(true);

        HttpResponse<String> response = ask(alice, "What is the termination notice?");

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat((String) JsonPath.read(response.body(), "$.code")).isEqualTo("AI_UNAVAILABLE");
        assertThat(response.body()).doesNotContain("Simulated");
    }

    @Test
    void documentsFailClearlyWhenTheEmbeddingModelIsUnavailable() {
        embeddingModel.setFailing(true);

        HttpResponse<String> response = alice.upload("contract.pdf", TestPdfs.withPages("Some text"));
        String document = awaitFinished(alice, JsonPath.read(response.body(), "$.id"));

        assertThat((String) JsonPath.read(document, "$.status")).isEqualTo("FAILED");
        assertThat((String) JsonPath.read(document, "$.failureReason")).isEqualTo("EMBEDDING_UNAVAILABLE");
    }

    @Test
    void aDocumentThatFailedBecauseOfTheModelWorksWhenTriedAgain() {
        embeddingModel.setFailing(true);
        HttpResponse<String> upload = alice.upload("contract.pdf", TestPdfs.withPages("The notice period is sixty days"));
        String id = JsonPath.read(upload.body(), "$.id");
        assertThat((Boolean) JsonPath.read(awaitFinished(alice, id), "$.canRetry")).isTrue();
        embeddingModel.setFailing(false);

        ApiClient bob = signedInUser();
        assertThat(bob.send("POST", "/api/documents/" + id + "/retry", null).statusCode()).isEqualTo(404);
        assertThat((String) JsonPath.read(alice.get("/api/documents/" + id).body(), "$.status")).isEqualTo("FAILED");

        HttpResponse<String> retry = alice.send("POST", "/api/documents/" + id + "/retry", null);

        assertThat(retry.statusCode()).isEqualTo(202);
        String document = awaitFinished(alice, id);
        assertThat((String) JsonPath.read(document, "$.status")).isEqualTo("READY");
        assertThat((String) JsonPath.read(document, "$.failureReason")).isNull();
        assertThat(countChunks(id)).isEqualTo(1);
        assertThat((Boolean) JsonPath.read(ask(alice, "What is the notice period?").body(), "$.turns[0].answered"))
                .isTrue();
    }

    @Test
    void deletingADocumentRemovesItsPassagesFromSearch() {
        String id = upload(alice, "contract.pdf", "Termination requires sixty days notice");
        assertThat(countChunks(id)).isPositive();

        alice.delete("/api/documents/" + id);

        assertThat(countChunks(id)).isZero();
        assertThat((Boolean) JsonPath.read(ask(alice, "Termination notice").body(), "$.turns[0].answered")).isFalse();
    }

    @Test
    void passagesAreStoredForEachPageThatHasText() {
        String id = upload(alice, "contract.pdf", "Text of page one", "", "Text of page three");

        List<Integer> pages = jdbcTemplate.queryForList(
                "SELECT page_number FROM document_chunks WHERE document_id = ?::uuid ORDER BY page_number",
                Integer.class,
                id);

        assertThat(pages).containsExactly(1, 3);
    }

    @Test
    void questionsAreValidatedAndRequireAuthentication() {
        assertThat(alice.postJson("/api/conversations", "{\"question\":\"   \"}").statusCode()).isEqualTo(400);
        assertThat(alice.postJson(
                                "/api/conversations",
                                "{\"question\":\"%s\"}".formatted("a".repeat(2001)))
                        .statusCode())
                .isEqualTo(400);
        assertThat(new ApiClient(port)
                        .postJson("/api/conversations", "{\"question\":\"Anything\"}")
                        .statusCode())
                .isEqualTo(401);
        assertThat(alice.postJsonWithoutCsrf("/api/conversations", "{\"question\":\"Anything\"}")
                        .statusCode())
                .isEqualTo(403);
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
        assertThat((String) JsonPath.read(awaitFinished(client, id), "$.status")).isEqualTo("READY");
        return id;
    }

    private HttpResponse<String> ask(ApiClient client, String question) {
        return client.postJson("/api/conversations", "{\"question\":\"%s\"}".formatted(question));
    }

    private String awaitFinished(ApiClient client, String id) {
        return await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(100))
                .until(
                        () -> client.get("/api/documents/" + id).body(),
                        body -> !"PROCESSING".equals(JsonPath.read(body, "$.status")));
    }

    private int countChunks(String documentId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_chunks WHERE document_id = ?::uuid", Integer.class, documentId);
    }
}
