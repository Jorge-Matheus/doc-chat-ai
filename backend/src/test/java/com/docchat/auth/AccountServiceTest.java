package com.docchat.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.docchat.common.web.ApiException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

class AccountServiceTest {

    private final AccountRepository repository = mock(AccountRepository.class);
    private final PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    private final AccountService service = new AccountService(repository, encoder);

    @Test
    void savesANormalizedEmailAndAHashedPassword() {
        service.register("  User@Example.COM ", "a long enough password");

        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        Mockito.verify(repository).insert(Mockito.eq("user@example.com"), hash.capture());
        assertThat(hash.getValue()).startsWith("{bcrypt}");
        assertThat(encoder.matches("a long enough password", hash.getValue())).isTrue();
    }

    @Test
    void reportsADuplicateEmailAsAConflict() {
        when(repository.insert(anyString(), any())).thenThrow(new DuplicateKeyException("duplicate"));

        assertThatThrownBy(() -> service.register("user@example.com", "a long enough password"))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getCode()).isEqualTo("EMAIL_ALREADY_REGISTERED");
                });
    }
}
