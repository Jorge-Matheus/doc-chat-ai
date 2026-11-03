package com.docchat.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.docchat.TestcontainersConfiguration;
import com.docchat.testsupport.ApiClient;
import com.jayway.jsonpath.JsonPath;
import java.net.HttpCookie;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** Runs the real filter chain over real HTTP against PostgreSQL, as the browser does. Requires Docker. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class AuthFlowIT {

    private static final String PASSWORD = "correct horse battery";

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ApiClient browser;
    private String email;

    @BeforeEach
    void setUp() {
        browser = new ApiClient(port);
        email = "user-" + UUID.randomUUID() + "@example.com";
    }

    @Test
    void registersSignsInAndSignsOut() {
        assertThat(browser.get("/api/auth/me").statusCode()).isEqualTo(401);

        HttpResponse<String> registered = browser.register(email, PASSWORD);
        assertThat(registered.statusCode()).isEqualTo(201);
        assertThat((String) JsonPath.read(registered.body(), "$.email")).isEqualTo(email);
        assertThat(registered.body()).doesNotContain("password");

        assertThat(browser.login(email, PASSWORD).statusCode()).isEqualTo(204);
        HttpResponse<String> me = browser.get("/api/auth/me");
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat((String) JsonPath.read(me.body(), "$.email")).isEqualTo(email);

        assertThat(browser.logout().statusCode()).isEqualTo(204);
        assertThat(browser.get("/api/auth/me").statusCode()).isEqualTo(401);
    }

    @Test
    void signedOutSessionCookieNoLongerWorks() {
        browser.register(email, PASSWORD);
        browser.login(email, PASSWORD);
        HttpCookie session = browser.cookie("SESSION").orElseThrow();

        browser.logout();
        browser.setCookie(session);

        assertThat(browser.get("/api/auth/me").statusCode()).isEqualTo(401);
    }

    @Test
    void sessionCookieIsHiddenFromJavaScriptAndCsrfCookieIsNot() {
        browser.register(email, PASSWORD);
        browser.login(email, PASSWORD);
        browser.get("/api/auth/me");

        assertThat(browser.cookie("SESSION").orElseThrow().isHttpOnly()).isTrue();
        assertThat(browser.cookie("XSRF-TOKEN").orElseThrow().isHttpOnly()).isFalse();
    }

    @Test
    void loginReplacesTheSessionIdAndInvalidatesTheOldOne() {
        browser.register(email, PASSWORD);
        browser.login(email, PASSWORD);
        HttpCookie first = browser.cookie("SESSION").orElseThrow();
        browser.get("/api/auth/me");

        browser.login(email, PASSWORD);
        HttpCookie second = browser.cookie("SESSION").orElseThrow();
        assertThat(second.getValue()).isNotEqualTo(first.getValue());

        browser.setCookie(first);
        assertThat(browser.get("/api/auth/me").statusCode()).isEqualTo(401);
    }

    @Test
    void anonymousRequestsDoNotCreateSessions() {
        browser.get("/api/auth/me");
        browser.get("/api/documents");

        assertThat(browser.cookie("SESSION")).isEmpty();
        assertThat(browser.cookie("XSRF-TOKEN")).isPresent();
    }

    @Test
    void wrongPasswordAndUnknownEmailGetTheSameAnswer() {
        browser.register(email, PASSWORD);

        HttpResponse<String> wrongPassword = browser.login(email, "not the right password");
        HttpResponse<String> unknownEmail = browser.login("nobody-" + email, PASSWORD);

        assertThat(wrongPassword.statusCode()).isEqualTo(401);
        assertThat(unknownEmail.statusCode()).isEqualTo(401);
        assertThat((String) JsonPath.read(wrongPassword.body(), "$.code")).isEqualTo("INVALID_CREDENTIALS");
        assertThat((String) JsonPath.read(unknownEmail.body(), "$.code")).isEqualTo("INVALID_CREDENTIALS");
        assertThat((String) JsonPath.read(wrongPassword.body(), "$.detail"))
                .isEqualTo(JsonPath.read(unknownEmail.body(), "$.detail"));
        assertThat(browser.cookie("SESSION")).isEmpty();
    }

    @Test
    void emailIsCaseInsensitive() {
        assertThat(browser.register(email, PASSWORD).statusCode()).isEqualTo(201);

        HttpResponse<String> duplicate = browser.register(email.toUpperCase(), PASSWORD);

        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat((String) JsonPath.read(duplicate.body(), "$.code")).isEqualTo("EMAIL_ALREADY_REGISTERED");
        assertThat(browser.login(email.toUpperCase(), PASSWORD).statusCode()).isEqualTo(204);
    }

    @Test
    void weakPasswordIsRejectedWithoutEchoingIt() {
        HttpResponse<String> rejected = browser.register(email, "tinypass");

        assertThat(rejected.statusCode()).isEqualTo(400);
        List<String> fields = JsonPath.read(rejected.body(), "$.errors[*].field");
        assertThat(fields).containsExactly("password");
        assertThat(rejected.body()).doesNotContain("tinypass");
        assertThat(browser.login(email, "tinypass").statusCode()).isEqualTo(401);
    }

    @Test
    void requestsWithoutTheCsrfTokenAreRejected() {
        browser.get("/api/auth/me");

        HttpResponse<String> response = browser.postJsonWithoutCsrf(
                "/api/auth/register", "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD));

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat((String) JsonPath.read(response.body(), "$.code")).isEqualTo("FORBIDDEN");
        assertThat((String) JsonPath.read(response.body(), "$.correlationId")).isNotEmpty();
    }

    @Test
    void passwordIsHashedAndTheHashStaysOutOfTheSession() {
        browser.register(email, PASSWORD);
        browser.login(email, PASSWORD);
        browser.get("/api/auth/me");

        String hash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM users WHERE email = ?", String.class, email);
        assertThat(hash).startsWith("{bcrypt}").doesNotContain(PASSWORD);

        List<byte[]> attributes = jdbcTemplate.queryForList(
                """
                SELECT a.attribute_bytes FROM spring_session_attributes a
                JOIN spring_session s ON s.primary_id = a.session_primary_id
                WHERE s.principal_name = ?
                """,
                byte[].class,
                email);
        assertThat(attributes).isNotEmpty();
        assertThat(attributes).allSatisfy(bytes -> assertThat(new String(bytes, StandardCharsets.ISO_8859_1))
                .doesNotContain(hash.substring("{bcrypt}".length())));
    }
}
