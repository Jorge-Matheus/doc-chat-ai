package com.docchat;

import static org.assertj.core.api.Assertions.assertThat;

import com.docchat.testsupport.ApiClient;
import com.jayway.jsonpath.JsonPath;
import java.net.HttpCookie;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * What a signed-in browser gets while the database is down, over real HTTP. Sessions live in the
 * database, so that browser's cookie cannot be checked. It does not require Docker.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/unreachable",
            "spring.datasource.username=nobody",
            "spring.datasource.password=nothing",
            "spring.datasource.hikari.initialization-fail-timeout=-1",
            "spring.datasource.hikari.connection-timeout=250",
            "spring.flyway.enabled=false"
        })
class DatabaseOutageTest {

    private static final String SESSION_ID =
            Base64.getEncoder().encodeToString("some-session-id".getBytes(StandardCharsets.UTF_8));

    @Value("${local.server.port}")
    private int port;

    private ApiClient signedIn;

    @BeforeEach
    void setUp() {
        signedIn = new ApiClient(port);
        HttpCookie session = new HttpCookie("SESSION", SESSION_ID);
        session.setPath("/");
        // The default version is written in a format that browsers do not use and Tomcat does not read.
        session.setVersion(0);
        signedIn.setCookie(session);
    }

    @Test
    void theRoutesThatReportTheStateOfTheSystemStillWork() {
        HttpResponse<String> status = signedIn.get("/api/system/status");
        assertThat(status.statusCode()).isEqualTo(200);
        assertThat((String) JsonPath.read(status.body(), "$.database")).isEqualTo("DOWN");

        assertThat(signedIn.get("/actuator/health/liveness").statusCode()).isEqualTo(200);
        assertThat(signedIn.get("/actuator/health/readiness").statusCode()).isEqualTo(503);
        assertThat(signedIn.get("/actuator/health").statusCode()).isEqualTo(503);
        assertThat(signedIn.get("/actuator/prometheus").statusCode()).isEqualTo(200);
    }

    @Test
    void routesThatNeedTheSessionExplainThatTheDatabaseIsUnavailable() {
        for (String path : List.of("/api/documents", "/api/conversations", "/api/auth/me")) {
            HttpResponse<String> response = signedIn.get(path);

            assertThat(response.statusCode()).as(path).isEqualTo(503);
            assertThat(response.headers().firstValue("Content-Type")).as(path).contains("application/problem+json");
            assertThat((String) JsonPath.read(response.body(), "$.code")).isEqualTo("DATABASE_UNAVAILABLE");
            assertThat((String) JsonPath.read(response.body(), "$.detail")).contains("Try again");
            assertThat((String) JsonPath.read(response.body(), "$.correlationId")).isNotBlank();
        }
    }

    @Test
    void aCookieThatCannotBeCheckedDoesNotEndTheSession() {
        HttpResponse<String> response = signedIn.get("/api/documents");

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.headers().allValues("Set-Cookie")).noneMatch(cookie -> cookie.startsWith("SESSION="));
        assertThat(signedIn.cookie("SESSION")).isPresent();
    }

    @Test
    void signingInDoesNotBlameTheEmailOrPassword() {
        HttpResponse<String> response = new ApiClient(port).login("someone@example.com", "a long enough password");

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat((String) JsonPath.read(response.body(), "$.code")).isEqualTo("DATABASE_UNAVAILABLE");
    }

    @Test
    void creatingAnAccountExplainsTheOutage() {
        HttpResponse<String> response = new ApiClient(port).register("someone@example.com", "a long enough password");

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat((String) JsonPath.read(response.body(), "$.code")).isEqualTo("DATABASE_UNAVAILABLE");
    }

    @Test
    void someoneNotSignedInIsStillToldToSignIn() {
        HttpResponse<String> response = new ApiClient(port).get("/api/documents");

        assertThat(response.statusCode()).isEqualTo(401);
    }
}
