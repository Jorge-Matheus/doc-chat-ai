package com.docchat.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.docchat.TestcontainersConfiguration;
import com.docchat.testsupport.ApiClient;
import com.docchat.testsupport.FakeAiConfiguration;
import com.docchat.testsupport.TestPdfs;
import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** The limit is low here, so a few small files reach it. Requires Docker. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "docchat.documents.storage-per-user=5KB")
@Import({TestcontainersConfiguration.class, FakeAiConfiguration.class})
class StorageLimitIT {

    private static final String PASSWORD = "correct horse battery";

    @Value("${local.server.port}")
    private int port;

    @Test
    void eachUserHasTheirOwnLimitAndDeletingMakesRoom() {
        ApiClient alice = signedInUser();
        byte[] file = TestPdfs.withPages("Some text");

        String kept = null;
        HttpResponse<String> response = alice.upload("a.pdf", file);
        while (response.statusCode() == 202) {
            kept = JsonPath.read(response.body(), "$.id");
            response = alice.upload("a.pdf", file);
        }

        assertThat(kept).as("at least one file fits").isNotNull();
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat((String) JsonPath.read(response.body(), "$.code")).isEqualTo("STORAGE_LIMIT_REACHED");
        assertThat((String) JsonPath.read(response.body(), "$.detail")).contains("5 KB");

        assertThat(signedInUser().upload("b.pdf", file).statusCode()).isEqualTo(202);

        alice.delete("/api/documents/" + kept);
        assertThat(alice.upload("a.pdf", file).statusCode()).isEqualTo(202);
    }

    private ApiClient signedInUser() {
        ApiClient client = new ApiClient(port);
        String email = "user-" + UUID.randomUUID() + "@example.com";
        client.register(email, PASSWORD);
        client.login(email, PASSWORD);
        return client;
    }
}
