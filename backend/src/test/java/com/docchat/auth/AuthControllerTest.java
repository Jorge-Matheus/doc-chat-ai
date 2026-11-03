package com.docchat.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccountService accountService;

    @Test
    void registersAnAccountWithoutReturningAnythingSecret() throws Exception {
        UUID id = UUID.randomUUID();
        when(accountService.register("user@example.com", "a long enough password"))
                .thenReturn(new Account(id, "user@example.com", "the-hash"));

        mockMvc.perform(register("{\"email\":\"user@example.com\",\"password\":\"a long enough password\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.email").value("user@example.com"))
                .andExpect(content().string(not(containsString("the-hash"))));
    }

    @Test
    void reportsEachInvalidFieldWithoutEchoingTheValues() throws Exception {
        mockMvc.perform(register("{\"email\":\"not-an-email\",\"password\":\"tiny-secret\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'email')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'password')]").exists())
                .andExpect(content().string(not(containsString("tiny-secret"))));
        verifyNoInteractions(accountService);
    }

    @Test
    void aMissingPasswordIsInvalid() throws Exception {
        mockMvc.perform(register("{\"email\":\"user@example.com\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("password"));
    }

    @Test
    void passwordsOverTheByteLimitAreInvalidEvenWithFewCharacters() throws Exception {
        String accentedPassword = "é".repeat(37);
        assertThat(accentedPassword.getBytes(StandardCharsets.UTF_8)).hasSize(74);

        mockMvc.perform(register("{\"email\":\"user@example.com\",\"password\":\"" + accentedPassword + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("password"));
        verifyNoInteractions(accountService);
    }

    @Test
    void passwordsAtTheByteLimitAreValid() throws Exception {
        when(accountService.register("user@example.com", "a".repeat(72)))
                .thenReturn(new Account(UUID.randomUUID(), "user@example.com", "hash"));

        mockMvc.perform(register("{\"email\":\"user@example.com\",\"password\":\"" + "a".repeat(72) + "\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void meAnswersUnauthorizedAndStillIssuesTheCsrfCookie() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(cookie().exists("XSRF-TOKEN"));
    }

    @Test
    void meReturnsTheSignedInUser() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(get("/api/auth/me").with(user(new AccountPrincipal(id, "user@example.com", null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.email").value("user@example.com"));
    }

    @Test
    void theBackendDoesNotServeALoginPage() throws Exception {
        mockMvc.perform(get("/login")).andExpect(status().isUnauthorized());
    }

    private static MockHttpServletRequestBuilder register(String json) {
        return post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .with(csrf());
    }
}
