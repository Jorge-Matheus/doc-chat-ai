package com.docchat.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.docchat.TestcontainersConfiguration;
import com.docchat.testsupport.ApiClient;
import com.docchat.testsupport.FakeAiConfiguration;
import com.docchat.testsupport.FakeChatModel;
import com.docchat.testsupport.TestPdfs;
import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Checks the security rules over real HTTP against a real database. The rules about routes read the
 * routes from the application, so an endpoint added later is covered without changing this test.
 * Requires Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, FakeAiConfiguration.class})
@ExtendWith(OutputCaptureExtension.class)
class SecurityRulesIT {

    private static final String PASSWORD = "correct horse battery";
    private static final String QUESTION_BODY = "{\"question\":\"What does it say?\"}";
    private static final Set<Route> PUBLIC_ROUTES =
            Set.of(new Route("POST", "/api/auth/register"), new Route("GET", "/api/system/status"));
    private static final List<String> LOOK_LIKE_INTERNAL_DETAILS =
            List.of("Exception", "org.springframework", "com.docchat", "java.", "SQL", "\tat ", "postgres");

    @Value("${local.server.port}")
    private int port;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FakeChatModel chatModel;

    private ApiClient alice;
    private String aliceEmail;

    private record Route(String method, String pattern) {

        boolean changesData() {
            return !method.equals("GET") && !method.equals("HEAD") && !method.equals("OPTIONS");
        }

        String body() {
            return pattern.endsWith("/retry") || !method.equals("POST") ? null : QUESTION_BODY;
        }
    }

    @BeforeEach
    void setUp() {
        chatModel.reset();
        aliceEmail = "alice-" + UUID.randomUUID() + "@example.com";
        alice = new ApiClient(port);
        alice.register(aliceEmail, PASSWORD);
        alice.login(aliceEmail, PASSWORD);
    }

    @Test
    void theListOfRoutesIsReallyReadFromTheApplication() {
        assertThat(apiRoutes())
                .contains(
                        new Route("POST", "/api/documents"),
                        new Route("GET", "/api/documents/{id}/file"),
                        new Route("POST", "/api/documents/{id}/retry"),
                        new Route("DELETE", "/api/conversations/{id}"),
                        new Route("POST", "/api/conversations/{id}/turns"));
    }

    @Test
    void everyRouteRequiresSigningInExceptTheOnesListedAsPublic() {
        ApiClient anonymous = new ApiClient(port);
        UUID id = UUID.randomUUID();

        for (Route route : apiRoutes()) {
            if (PUBLIC_ROUTES.contains(route)) {
                continue;
            }
            HttpResponse<String> response = anonymous.send(route.method(), path(route, id), route.body());

            assertThat(response.statusCode()).as(route.toString()).isEqualTo(401);
            assertThat((String) JsonPath.read(response.body(), "$.code"))
                    .as(route.toString())
                    .isEqualTo("UNAUTHORIZED");
        }
    }

    @Test
    void everyRouteThatChangesDataRequiresTheCsrfToken() {
        String document = uploadAndWait(alice);
        String conversation = startConversation(alice);

        List<Route> routes = apiRoutes().stream().filter(Route::changesData).toList();
        assertThat(routes).isNotEmpty();
        for (Route route : routes) {
            HttpResponse<String> response =
                    alice.sendWithoutCsrf(route.method(), path(route, ownedId(route, document, conversation)), route.body());

            assertThat(response.statusCode()).as(route.toString()).isEqualTo(403);
            assertThat((String) JsonPath.read(response.body(), "$.code")).as(route.toString()).isEqualTo("FORBIDDEN");
        }
        assertThat(alice.get("/api/documents/" + document).statusCode()).isEqualTo(200);
        assertThat(alice.get("/api/conversations/" + conversation).statusCode()).isEqualTo(200);
    }

    /** The answer must be the same as for an id that does not exist, so nothing about the other user leaks. */
    @Test
    void everyRouteWithAnIdHidesWhatBelongsToAnotherUser() {
        String document = uploadAndWait(alice);
        String conversation = startConversation(alice);
        ApiClient bob = signedInUser();
        int modelCalls = chatModel.calls();

        List<Route> routes = apiRoutes().stream().filter(route -> route.pattern().contains("{id}")).toList();
        assertThat(routes).hasSizeGreaterThanOrEqualTo(8);
        for (Route route : routes) {
            String theirs = ownedId(route, document, conversation);
            HttpResponse<String> forOwned = bob.send(route.method(), path(route, theirs), route.body());
            HttpResponse<String> forMissing = bob.send(route.method(), path(route, UUID.randomUUID()), route.body());

            assertThat(forOwned.statusCode()).as(route.toString()).isEqualTo(404);
            assertThat((String) JsonPath.read(forOwned.body(), "$.code"))
                    .as(route.toString())
                    .isEqualTo(JsonPath.read(forMissing.body(), "$.code"));
            assertThat((String) JsonPath.read(forOwned.body(), "$.detail"))
                    .as(route.toString())
                    .isEqualTo(JsonPath.read(forMissing.body(), "$.detail"));
        }

        assertThat(chatModel.calls()).as("the other user's question never reached the model").isEqualTo(modelCalls);
        assertThat(alice.get("/api/documents/" + document).statusCode()).isEqualTo(200);
        assertThat((List<?>) JsonPath.read(alice.get("/api/conversations/" + conversation).body(), "$.turns"))
                .hasSize(1);
    }

    @Test
    void onlyTheHealthInfoAndPrometheusEndpointsOfActuatorAreExposed() {
        for (String endpoint : List.of(
                "env", "beans", "heapdump", "threaddump", "loggers", "metrics", "mappings", "configprops", "sessions",
                "scheduledtasks", "conditions", "shutdown")) {
            assertThat(alice.get("/actuator/" + endpoint).statusCode()).as(endpoint).isEqualTo(404);
        }
        assertThat(new ApiClient(port).get("/actuator/env").statusCode()).isEqualTo(401);
        assertThat(new ApiClient(port).get("/actuator/prometheus").statusCode()).isEqualTo(200);
    }

    @Test
    void healthDoesNotDescribeTheComponents() {
        String health = new ApiClient(port).get("/actuator/health").body();

        assertThat(health).contains("status").doesNotContain("components", "details", "database", "PostgreSQL");
    }

    @Test
    void responsesAskBrowsersNotToSniffFrameOrStoreThem() {
        HttpResponse<String> response = alice.get("/api/documents");

        assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        assertThat(response.headers().firstValue("X-Frame-Options")).contains("DENY");
        assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(value -> assertThat(value)
                .contains("no-store"));
    }

    @Test
    void theSessionCookieCannotBeReadByScriptsOrSentByOtherSites() {
        ApiClient browser = new ApiClient(port);
        String email = "cookie-" + UUID.randomUUID() + "@example.com";
        browser.register(email, PASSWORD);

        HttpResponse<String> login = browser.login(email, PASSWORD);

        String cookie = login.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith("SESSION="))
                .findFirst()
                .orElseThrow();
        assertThat(cookie).containsIgnoringCase("HttpOnly").containsIgnoringCase("SameSite=Lax");
    }

    @Test
    void aSessionEndsAfterThirtyMinutesWithoutActivity() {
        Integer interval = jdbcTemplate.queryForObject(
                "SELECT max_inactive_interval FROM spring_session WHERE principal_name = ?", Integer.class, aliceEmail);

        assertThat(interval).isEqualTo(30 * 60);
    }

    @Test
    void anExpiredSessionNoLongerWorks() {
        assertThat(alice.get("/api/auth/me").statusCode()).isEqualTo(200);

        // A session counts as expired from when it was last used, not from the column of the cleanup job.
        jdbcTemplate.update(
                "UPDATE spring_session SET last_access_time = 0, expiry_time = 0 WHERE principal_name = ?", aliceEmail);

        assertThat(alice.get("/api/auth/me").statusCode()).isEqualTo(401);
    }

    @Test
    void malformedRequestsGetAnErrorWithoutInternalDetails() {
        String longQuestion = "x".repeat(2001);
        String manyDocuments = String.join(",", java.util.Collections.nCopies(51, "\"" + UUID.randomUUID() + "\""));

        List<HttpResponse<String>> responses = List.of(
                alice.get("/api/documents/not-a-uuid"),
                alice.get("/api/documents/' OR '1'='1"
                        .replace(" ", "%20")
                        .replace("'", "%27")),
                alice.get("/api/documents/" + UUID.randomUUID() + "/pages/not-a-number"),
                alice.get("/api/documents/" + UUID.randomUUID() + "/pages/0"),
                alice.get("/api/documents?page=-1"),
                alice.get("/api/documents?size=1000"),
                alice.get("/api/documents?page=1%3BDROP%20TABLE%20documents"),
                alice.get("/api/conversations?size=0"),
                alice.send("POST", "/api/conversations", "{ this is not json"),
                alice.send("POST", "/api/conversations", "{\"question\":\"\"}"),
                alice.send("POST", "/api/conversations", "{\"question\":\"" + longQuestion + "\"}"),
                alice.send("POST", "/api/conversations", "{\"question\":\"a\",\"documentIds\":[\"not-a-uuid\"]}"),
                alice.send("POST", "/api/conversations", "{\"question\":\"a\",\"documentIds\":[" + manyDocuments + "]}"),
                alice.send("POST", "/api/auth/register", "{\"email\":\"not-an-email\",\"password\":\"" + PASSWORD + "\"}"));

        for (HttpResponse<String> response : responses) {
            assertThat(response.statusCode()).as(response.uri().toString()).isEqualTo(400);
            assertThat(response.body()).as(response.uri().toString()).doesNotContain(LOOK_LIKE_INTERNAL_DETAILS);
            assertThat((String) JsonPath.read(response.body(), "$.correlationId")).isNotBlank();
        }
    }

    @Test
    void aWrongMethodOrContentTypeGetsAnErrorWithoutInternalDetails() {
        HttpResponse<String> wrongMethod = alice.send("PUT", "/api/documents", null);
        HttpResponse<String> wrongContentType = alice.send("POST", "/api/documents", "{}");

        assertThat(wrongMethod.statusCode()).isEqualTo(405);
        assertThat(wrongMethod.body()).doesNotContain(LOOK_LIKE_INTERNAL_DETAILS);
        assertThat(wrongContentType.statusCode()).isEqualTo(415);
        assertThat(wrongContentType.body()).doesNotContain(LOOK_LIKE_INTERNAL_DETAILS);
    }

    @Test
    void pathsThatTryToLeaveTheirFolderAreRefused() {
        String document = uploadAndWait(alice);

        for (String attack : List.of(
                "/api/documents/..%2f..%2f..%2fetc%2fpasswd/file",
                "/api/documents/%2e%2e%2f%2e%2e%2fetc%2fpasswd/file",
                "/api/documents/" + document + "%2f..%2f" + UUID.randomUUID() + "/file",
                "/api/documents/..%5c..%5cwindows%5cwin.ini/file")) {
            HttpResponse<String> response = alice.get(attack);

            assertThat(response.statusCode()).as(attack).isIn(400, 404);
            assertThat(response.body()).as(attack).doesNotContain("root:", "[fonts]");
        }
    }

    @Test
    void textThatLooksLikeSqlIsOnlyText() {
        String attack = "'; DROP TABLE documents; --";

        HttpResponse<String> response = alice.upload(attack + ".pdf", TestPdfs.withPages("Some text about contracts"));
        String document = JsonPath.read(response.body(), "$.id");
        await().atMost(Duration.ofSeconds(30))
                .until(() -> !"PROCESSING".equals(JsonPath.read(alice.get("/api/documents/" + document).body(), "$.status")));
        HttpResponse<String> question =
                alice.send("POST", "/api/conversations", "{\"question\":\"contracts " + attack + "\"}");

        assertThat(question.statusCode()).isEqualTo(201);
        assertThat((String) JsonPath.read(question.body(), "$.title")).contains(attack);
        assertThat((String) JsonPath.read(alice.get("/api/documents/" + document).body(), "$.filename"))
                .isEqualTo(attack + ".pdf");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) > 0 FROM documents", Boolean.class)).isTrue();
    }

    @Test
    void theLogsDoNotContainPasswordsDocumentsQuestionsOrAnswers(CapturedOutput output) {
        String secretPassword = "my-secret-password-" + UUID.randomUUID();
        String secretText = "SecretDocumentWord" + UUID.randomUUID().toString().replace("-", "");
        String secretQuestion = "SecretQuestionWord" + UUID.randomUUID().toString().replace("-", "");
        String secretAnswer = "SecretAnswerWord" + UUID.randomUUID().toString().replace("-", "");
        chatModel.setReply(secretAnswer + " [1].");
        String email = "logs-" + UUID.randomUUID() + "@example.com";
        ApiClient browser = new ApiClient(port);

        browser.register(email, secretPassword);
        browser.login(email, "a-wrong-" + secretPassword);
        browser.login(email, secretPassword);
        String document = JsonPath.read(browser.upload("contract.pdf", TestPdfs.withPages(secretText)).body(), "$.id");
        await().atMost(Duration.ofSeconds(30))
                .until(() -> "READY".equals(JsonPath.read(browser.get("/api/documents/" + document).body(), "$.status")));
        HttpResponse<String> answered = browser.send("POST", "/api/conversations", "{\"question\":\"" + secretText + " " + secretQuestion + "\"}");
        String session = browser.cookie("SESSION").orElseThrow().getValue();

        assertThat(answered.statusCode()).isEqualTo(201);
        assertThat((String) JsonPath.read(answered.body(), "$.turns[0].text")).contains(secretAnswer);
        assertThat(output.getAll()).doesNotContain(secretPassword, secretText, secretQuestion, secretAnswer, session);
    }

    private List<Route> apiRoutes() {
        return mappings.getHandlerMethods().keySet().stream()
                .flatMap(mapping -> mapping.getPatternValues().stream()
                        .filter(pattern -> pattern.startsWith("/api/"))
                        .flatMap(pattern -> mapping.getMethodsCondition().getMethods().stream()
                                .map(method -> new Route(method.name(), pattern))))
                .toList();
    }

    private static String path(Route route, Object id) {
        return route.pattern().replace("{id}", id.toString()).replace("{pageNumber}", "1");
    }

    /** A new kind of resource must be added here, so its owner check gets tested too. */
    private static String ownedId(Route route, String document, String conversation) {
        if (route.pattern().startsWith("/api/documents/")) {
            return document;
        }
        if (route.pattern().startsWith("/api/conversations/")) {
            return conversation;
        }
        return UUID.randomUUID().toString();
    }

    private ApiClient signedInUser() {
        ApiClient client = new ApiClient(port);
        String email = "user-" + UUID.randomUUID() + "@example.com";
        client.register(email, PASSWORD);
        client.login(email, PASSWORD);
        return client;
    }

    private String uploadAndWait(ApiClient client) {
        String id = JsonPath.read(
                client.upload("contract.pdf", TestPdfs.withPages("What does it say? The contract says this.")).body(),
                "$.id");
        await().atMost(Duration.ofSeconds(30))
                .until(() -> "READY".equals(JsonPath.read(client.get("/api/documents/" + id).body(), "$.status")));
        return id;
    }

    private String startConversation(ApiClient client) {
        return JsonPath.read(client.send("POST", "/api/conversations", QUESTION_BODY).body(), "$.id");
    }
}
