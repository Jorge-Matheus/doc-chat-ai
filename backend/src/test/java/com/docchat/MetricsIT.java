package com.docchat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.docchat.testsupport.ApiClient;
import com.docchat.testsupport.FakeAiConfiguration;
import com.docchat.testsupport.FakeChatModel;
import com.docchat.testsupport.TestPdfs;
import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** Reads the metrics as Prometheus does, after real requests. Requires Docker. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "docchat.documents.metrics-interval=PT0.2S")
@Import({TestcontainersConfiguration.class, FakeAiConfiguration.class})
class MetricsIT {

    private static final String PASSWORD = "correct horse battery";

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private FakeChatModel chatModel;

    @Test
    void theMetricsMeasureTheWorkAndHoldNoUserData() {
        String email = "metrics-" + UUID.randomUUID() + "@example.com";
        String filename = "SecretFileName" + UUID.randomUUID().toString().replace("-", "") + ".pdf";
        String text = "SecretDocumentWord" + UUID.randomUUID().toString().replace("-", "");
        String question = "SecretQuestionWord" + UUID.randomUUID().toString().replace("-", "");
        String answer = "SecretAnswerWord" + UUID.randomUUID().toString().replace("-", "");
        chatModel.setReply(answer + " [1].");

        ApiClient browser = new ApiClient(port);
        browser.register(email, PASSWORD);
        browser.login(email, PASSWORD);
        String document = JsonPath.read(browser.upload(filename, TestPdfs.withPages(text)).body(), "$.id");
        await().atMost(Duration.ofSeconds(30))
                .until(() -> "READY".equals(JsonPath.read(browser.get("/api/documents/" + document).body(), "$.status")));
        HttpResponse<String> asked =
                browser.send("POST", "/api/conversations", "{\"question\":\"" + text + " " + question + "\"}");
        String conversation = JsonPath.read(asked.body(), "$.id");
        browser.send("POST", "/api/conversations/" + conversation + "/turns", "{\"question\":\"" + text + "\"}");

        // Prometheus cannot sign in, so this has to work without a session.
        HttpResponse<String> scrape = new ApiClient(port).get("/actuator/prometheus");

        assertThat(scrape.statusCode()).isEqualTo(200);
        assertThat(scrape.body())
                .contains("docchat_document_processing_seconds_count{error=\"none\",outcome=\"ready\"")
                .contains("docchat_document_stage_seconds_count{error=\"none\",stage=\"extraction\"")
                .contains("docchat_document_stage_seconds_count{error=\"none\",stage=\"embedding\"")
                .contains("docchat_document_stage_seconds_count{error=\"none\",stage=\"saving\"")
                .contains("docchat_question_seconds_count{error=\"none\",outcome=\"answered\"")
                .contains("docchat_question_stage_seconds_count{error=\"none\",stage=\"rewrite\"")
                .contains("docchat_question_stage_seconds_count{error=\"none\",stage=\"embedding\"")
                .contains("docchat_question_stage_seconds_count{error=\"none\",stage=\"search\"")
                .contains("docchat_question_stage_seconds_count{error=\"none\",stage=\"generation\"")
                .contains("docchat_question_seconds_bucket")
                .contains("http_server_requests_seconds_bucket")
                .contains("hikaricp_connections_active")
                .contains("executor_queued_tasks")
                .contains("jvm_memory_used_bytes");
        assertThat(scrape.body())
                .as("no email, file name, document text, question or answer")
                .doesNotContain(email, filename, text, question, answer, document, conversation);

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(new ApiClient(port).get("/actuator/prometheus").body())
                        .containsPattern("docchat_documents\\{[^}]*status=\"ready\"[^}]*} [1-9]")
                        .contains("status=\"processing\"")
                        .contains("status=\"failed\""));
    }
}
