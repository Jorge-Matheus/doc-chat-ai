package com.docchat.auth;

import com.docchat.common.web.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
class AccountService {

    private final AccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;

    AccountService(AccountRepository accountRepository, PasswordEncoder passwordEncoder) {
        this.accountRepository = accountRepository;
        this.passwordEncoder = passwordEncoder;
    }

    Account register(String email, String password) {
        String passwordHash = passwordEncoder.encode(password);
        try {
            return accountRepository.insert(Account.normalizeEmail(email), passwordHash);
        } catch (DuplicateKeyException ex) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED", "This email is already registered.");
        }
    }
}
