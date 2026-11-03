package com.docchat.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.docchat.TestcontainersConfiguration;
import com.docchat.testsupport.ApiClient;
import com.docchat.testsupport.FakeAiConfiguration;
import com.docchat.testsupport.TestPdfs;
import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** Uploads real PDF files over real HTTP and checks the database and the disk. Requires Docker. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, FakeAiConfiguration.class})
class DocumentFlowIT {

    private static final String PASSWORD = "correct horse battery";

    @Value("${local.server.port}")
    private int port;

    @Value("${docchat.storage.directory}")
    private Path storageDirectory;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ApiClient alice;

    @BeforeEach
    void setUp() {
        alice = signedInUser();
    }

    @Test
    void uploadExtractsTheTextOfEachPage() {
        HttpResponse<String> response =
                alice.upload("contract.pdf", TestPdfs.withPages("Text of page one", "", "Text of page three"));

        assertThat(response.statusCode()).isEqualTo(202);
        String id = JsonPath.read(response.body(), "$.id");
        assertThat(response.headers().firstValue("Location")).contains("/api/documents/" + id);
        assertThat((String) JsonPath.read(response.body(), "$.filename")).isEqualTo("contract.pdf");

        String document = awaitFinished(alice, id);

        assertThat((String) JsonPath.read(document, "$.status")).isEqualTo("READY");
        assertThat((Integer) JsonPath.read(document, "$.pageCount")).isEqualTo(3);
        assertThat(pagesOf(id)).containsExactly("Text of page one", "", "Text of page three");
        assertThat(Files.exists(fileOf(id))).isTrue();
    }

    @Test
    void listShowsOnlyTheUsersDocumentsNewestFirst() {
        ApiClient bob = signedInUser();
        String first = uploadAndWait(alice, "first.pdf");
        String second = uploadAndWait(alice, "second.pdf");
        uploadAndWait(bob, "bob.pdf");

        String list = alice.get("/api/documents").body();

        List<String> ids = JsonPath.read(list, "$.items[*].id");
        assertThat(ids).containsExactly(second, first);
        assertThat((Integer) JsonPath.read(list, "$.totalItems")).isEqualTo(2);

        String pageOfOne = alice.get("/api/documents?page=1&size=1").body();
        List<String> pagedIds = JsonPath.read(pageOfOne, "$.items[*].id");
        assertThat(pagedIds).containsExactly(first);
        assertThat((Integer) JsonPath.read(pageOfOne, "$.totalItems")).isEqualTo(2);

        assertThat(alice.get("/api/documents?size=101").statusCode()).isEqualTo(400);
        assertThat(alice.get("/api/documents?page=-1").statusCode()).isEqualTo(400);
    }

    @Test
    void otherUsersCannotReachADocument() {
        String id = uploadAndWait(alice, "private.pdf");
        ApiClient bob = signedInUser();

        assertThat(bob.get("/api/documents/" + id).statusCode()).isEqualTo(404);
        assertThat(bob.delete("/api/documents/" + id).statusCode()).isEqualTo(404);

        assertThat(alice.get("/api/documents/" + id).statusCode()).isEqualTo(200);
        assertThat(pagesOf(id)).isNotEmpty();
        assertThat(Files.exists(fileOf(id))).isTrue();
    }

    @Test
    void theTextOfAPageCanBeRead() {
        String id = awaitReady(alice.upload("contract.pdf", TestPdfs.withPages("Text of page one", "", "Page three")));

        String first = alice.get("/api/documents/" + id + "/pages/1").body();
        assertThat((String) JsonPath.read(first, "$.documentId")).isEqualTo(id);
        assertThat((String) JsonPath.read(first, "$.filename")).isEqualTo("contract.pdf");
        assertThat((Integer) JsonPath.read(first, "$.pageNumber")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(first, "$.pageCount")).isEqualTo(3);
        assertThat((String) JsonPath.read(first, "$.text")).isEqualTo("Text of page one");

        assertThat((String) JsonPath.read(alice.get("/api/documents/" + id + "/pages/2").body(), "$.text")).isEmpty();

        HttpResponse<String> beyond = alice.get("/api/documents/" + id + "/pages/4");
        assertThat(beyond.statusCode()).isEqualTo(404);
        assertThat((String) JsonPath.read(beyond.body(), "$.code")).isEqualTo("PAGE_NOT_FOUND");
        assertThat(alice.get("/api/documents/" + id + "/pages/0").statusCode()).isEqualTo(400);
    }

    @Test
    void theOriginalFileIsServedByteForByte() {
        byte[] pdf = TestPdfs.withPages("Original content");
        String id = awaitReady(alice.upload("relatório final.pdf", pdf));

        HttpResponse<byte[]> response = alice.getBytes("/api/documents/" + id + "/file");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(pdf);
        assertThat(response.headers().firstValue("Content-Type")).contains("application/pdf");
        assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        assertThat(response.headers().firstValue("Content-Disposition"))
                .hasValueSatisfying(value -> assertThat(value)
                        .startsWith("inline; ")
                        .contains("relat%C3%B3rio%20final.pdf"));
    }

    @Test
    void aFileThatIsAlsoAWebPageIsStillServedAsAPdf() {
        byte[] polyglot = "%PDF-1.4 <html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);
        String id = JsonPath.read(alice.upload("page.pdf", polyglot).body(), "$.id");

        HttpResponse<byte[]> response = alice.getBytes("/api/documents/" + id + "/file");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).contains("application/pdf");
        assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
    }

    @Test
    void aFileMissingFromTheDiskIsReportedWithoutInternalDetails() throws Exception {
        String id = uploadAndWait(alice, "lost.pdf");
        Files.delete(fileOf(id));

        HttpResponse<String> response = alice.get("/api/documents/" + id + "/file");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat((String) JsonPath.read(response.body(), "$.code")).isEqualTo("FILE_NOT_AVAILABLE");
        assertThat(response.body()).doesNotContain(storageDirectory.toString());
    }

    @Test
    void otherUsersCannotReachThePagesOrTheFile() {
        String id = uploadAndWait(alice, "private.pdf");
        ApiClient bob = signedInUser();

        HttpResponse<String> page = bob.get("/api/documents/" + id + "/pages/1");
        assertThat(page.statusCode()).isEqualTo(404);
        assertThat(page.body()).doesNotContain("private.pdf");
        HttpResponse<String> file = bob.get("/api/documents/" + id + "/file");
        assertThat(file.statusCode()).isEqualTo(404);
        assertThat(file.body()).doesNotContain("private.pdf");

        assertThat(alice.get("/api/documents/" + id + "/pages/1").statusCode()).isEqualTo(200);
        assertThat(alice.get("/api/documents/" + id + "/file").statusCode()).isEqualTo(200);
    }

    @Test
    void deleteRemovesTheRowThePagesAndTheFile() {
        String id = uploadAndWait(alice, "to-delete.pdf");

        assertThat(alice.delete("/api/documents/" + id).statusCode()).isEqualTo(204);

        assertThat(alice.get("/api/documents/" + id).statusCode()).isEqualTo(404);
        assertThat(pagesOf(id)).isEmpty();
        assertThat(Files.exists(fileOf(id))).isFalse();
        assertThat(alice.delete("/api/documents/" + id).statusCode()).isEqualTo(404);
    }

    @Test
    void anonymousRequestsAreRejected() {
        ApiClient anonymous = new ApiClient(port);

        assertThat(anonymous.upload("a.pdf", TestPdfs.withPages("Text")).statusCode()).isEqualTo(401);
        assertThat(anonymous.get("/api/documents").statusCode()).isEqualTo(401);
        assertThat(anonymous.get("/api/documents/" + UUID.randomUUID()).statusCode()).isEqualTo(401);
    }

    @Test
    void filesThatAreNotPdfsAreRefusedAndNotStored() {
        long filesBefore = countStoredFiles();

        HttpResponse<String> response =
                alice.upload("fake.pdf", "just some text".getBytes(StandardCharsets.UTF_8));

        assertThat(response.statusCode()).isEqualTo(415);
        assertThat((String) JsonPath.read(response.body(), "$.code")).isEqualTo("UNSUPPORTED_FILE_TYPE");
        assertThat(countStoredFiles()).isEqualTo(filesBefore);
    }

    @Test
    void emptyFilesAndMissingPartsAreRefused() {
        HttpResponse<String> empty = alice.upload("empty.pdf", new byte[0]);
        assertThat(empty.statusCode()).isEqualTo(400);
        assertThat((String) JsonPath.read(empty.body(), "$.code")).isEqualTo("EMPTY_FILE");

        assertThat(alice.uploadPart("wrong-part", "a.pdf", TestPdfs.withPages("Text")).statusCode())
                .isEqualTo(400);
    }

    @Test
    void filesOverTheLimitGetAProblemResponse() {
        byte[] tooLarge = new byte[26 * 1024 * 1024];

        HttpResponse<String> response = alice.upload("large.pdf", tooLarge);

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat((String) JsonPath.read(response.body(), "$.correlationId")).isNotEmpty();
    }

    @Test
    void unusablePdfsFailWithTheirReason() {
        assertThat(failureReasonOf("corrupt.pdf", TestPdfs.corrupt())).isEqualTo("NOT_READABLE");
        assertThat(failureReasonOf("locked.pdf", TestPdfs.passwordProtected())).isEqualTo("PROTECTED");
        assertThat(failureReasonOf("copy-locked.pdf", TestPdfs.extractionForbidden())).isEqualTo("PROTECTED");
        assertThat(failureReasonOf("scanned.pdf", TestPdfs.withPages("", ""))).isEqualTo("NO_EXTRACTABLE_TEXT");
    }

    @Test
    void failedDocumentsCanBeDeleted() {
        HttpResponse<String> response = alice.upload("corrupt.pdf", TestPdfs.corrupt());
        String id = JsonPath.read(response.body(), "$.id");
        awaitFinished(alice, id);

        assertThat(alice.delete("/api/documents/" + id).statusCode()).isEqualTo(204);
        assertThat(Files.exists(fileOf(id))).isFalse();
    }

    @Test
    void hostileFilenamesAreOnlyDisplayed() {
        HttpResponse<String> response = alice.upload("../../etc/passwd.pdf", TestPdfs.withPages("Text"));

        String id = JsonPath.read(response.body(), "$.id");
        assertThat((String) JsonPath.read(response.body(), "$.filename")).isEqualTo("passwd.pdf");
        assertThat(Files.exists(fileOf(id))).isTrue();
    }

    private ApiClient signedInUser() {
        ApiClient client = new ApiClient(port);
        String email = "user-" + UUID.randomUUID() + "@example.com";
        assertThat(client.register(email, PASSWORD).statusCode()).isEqualTo(201);
        assertThat(client.login(email, PASSWORD).statusCode()).isEqualTo(204);
        return client;
    }

    private String uploadAndWait(ApiClient client, String filename) {
        HttpResponse<String> response = client.upload(filename, TestPdfs.withPages("Some text"));
        assertThat(response.statusCode()).isEqualTo(202);
        String id = JsonPath.read(response.body(), "$.id");
        awaitFinished(client, id);
        return id;
    }

    private String awaitReady(HttpResponse<String> upload) {
        assertThat(upload.statusCode()).isEqualTo(202);
        String id = JsonPath.read(upload.body(), "$.id");
        assertThat((String) JsonPath.read(awaitFinished(alice, id), "$.status")).isEqualTo("READY");
        return id;
    }

    private String failureReasonOf(String filename, byte[] content) {
        HttpResponse<String> response = alice.upload(filename, content);
        assertThat(response.statusCode()).isEqualTo(202);
        String document = awaitFinished(alice, JsonPath.read(response.body(), "$.id"));
        assertThat((String) JsonPath.read(document, "$.status")).isEqualTo("FAILED");
        return JsonPath.read(document, "$.failureReason");
    }

    private String awaitFinished(ApiClient client, String id) {
        return await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(100))
                .until(
                        () -> client.get("/api/documents/" + id).body(),
                        body -> !"PROCESSING".equals(JsonPath.read(body, "$.status")));
    }

    private List<String> pagesOf(String id) {
        return jdbcTemplate.queryForList(
                "SELECT content FROM document_pages WHERE document_id = ?::uuid ORDER BY page_number",
                String.class,
                id);
    }

    private Path fileOf(String id) {
        return storageDirectory.resolve(id + ".pdf");
    }

    private long countStoredFiles() {
        try (var files = Files.list(storageDirectory)) {
            return files.count();
        } catch (java.io.IOException ex) {
            return 0;
        }
    }
}
