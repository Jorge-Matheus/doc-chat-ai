package com.docchat.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Login and logout are handled by Spring Security's filters. See {@link SecurityConfig}. */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Accounts", description = "Registration and the current user")
class AuthController {

    private final AccountService accountService;

    AuthController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an account")
    AccountResponse register(@Valid @RequestBody RegisterRequest request) {
        Account account = accountService.register(request.email(), request.password());
        return new AccountResponse(account.id(), account.email());
    }

    @GetMapping("/me")
    @Operation(summary = "Get the signed-in user", description = "Answers 401 when no one is signed in.")
    AccountResponse me(@AuthenticationPrincipal AccountPrincipal principal) {
        return new AccountResponse(principal.getId(), principal.getUsername());
    }

    record AccountResponse(UUID id, String email) {}

    record RegisterRequest(@NotBlank @Email @Size(max = 254) String email, @ValidPassword String password) {

        /** Replaces the generated version, so the password cannot reach a log. */
        @Override
        public String toString() {
            return "RegisterRequest[email=" + email + "]";
        }
    }
}
