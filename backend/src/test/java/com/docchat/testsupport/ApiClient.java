package com.docchat.testsupport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Talks to a running server over real HTTP and behaves like the browser: it keeps cookies and
 * sends the CSRF token back as a header. Each instance is a separate browser session.
 */
public final class ApiClient {

    private static final String BOUNDARY = "docchat-test-boundary";

    private final String baseUrl;
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient http;

    public ApiClient(int port) {
        this.baseUrl = "http://localhost:" + port;
        this.http = HttpClient.newBuilder().cookieHandler(cookies).build();
    }

    public HttpResponse<String> get(String path) {
        return send(request(path).GET().build());
    }

    public HttpResponse<byte[]> getBytes(String path) {
        try {
            return http.send(request(path).GET().build(), BodyHandlers.ofByteArray());
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    public HttpResponse<String> delete(String path) {
        return send(withCsrf(request(path)).DELETE().build());
    }

    public HttpResponse<String> postJson(String path, String json) {
        return send(withCsrf(request(path))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(json))
                .build());
    }

    /** Sends a valid request without the CSRF header, as a forged cross-site request would. */
    public HttpResponse<String> postJsonWithoutCsrf(String path, String json) {
        return send(request(path)
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(json))
                .build());
    }

    public HttpResponse<String> register(String email, String password) {
        return postJson(
                "/api/auth/register",
                "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password));
    }

    public HttpResponse<String> login(String email, String password) {
        String form = "email=" + encode(email) + "&password=" + encode(password);
        return send(withCsrf(request("/api/auth/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(BodyPublishers.ofString(form))
                .build());
    }

    public HttpResponse<String> logout() {
        return send(withCsrf(request("/api/auth/logout")).POST(BodyPublishers.noBody()).build());
    }

    public HttpResponse<String> upload(String filename, byte[] content) {
        return send(withCsrf(request("/api/documents"))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .POST(BodyPublishers.ofByteArray(multipartBody("file", filename, content)))
                .build());
    }

    public HttpResponse<String> uploadPart(String partName, String filename, byte[] content) {
        return send(withCsrf(request("/api/documents"))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .POST(BodyPublishers.ofByteArray(multipartBody(partName, filename, content)))
                .build());
    }

    public Optional<HttpCookie> cookie(String name) {
        return cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals(name))
                .findFirst();
    }

    /** Lets a test keep using a session cookie after the server replaced it. */
    public void setCookie(HttpCookie cookie) {
        cookies.getCookieStore().add(URI.create(baseUrl), cookie);
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path));
    }

    private HttpRequest.Builder withCsrf(HttpRequest.Builder builder) {
        // The first request of a browser session is what gives it a token.
        if (cookie("XSRF-TOKEN").isEmpty()) {
            get("/api/system/status");
        }
        cookie("XSRF-TOKEN").ifPresent(token -> builder.header("X-XSRF-TOKEN", token.getValue()));
        return builder;
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, BodyHandlers.ofString());
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private static byte[] multipartBody(String partName, String filename, byte[] content) {
        String head = "--%s\r\nContent-Disposition: form-data; name=\"%s\"; filename=\"%s\"\r\n"
                        .formatted(BOUNDARY, partName, filename)
                + "Content-Type: application/octet-stream\r\n\r\n";
        String tail = "\r\n--" + BOUNDARY + "--\r\n";
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(head.getBytes(StandardCharsets.UTF_8));
        body.writeBytes(content);
        body.writeBytes(tail.getBytes(StandardCharsets.UTF_8));
        return body.toByteArray();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
