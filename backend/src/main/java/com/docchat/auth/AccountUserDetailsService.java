package com.docchat.auth;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
class AccountUserDetailsService implements UserDetailsService {

    private final AccountRepository accountRepository;

    AccountUserDetailsService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String email) {
        return accountRepository
                .findByEmail(Account.normalizeEmail(email))
                .map(account -> new AccountPrincipal(account.id(), account.email(), account.passwordHash()))
                // Spring Security reports this to the client as the same error as a wrong password.
                .orElseThrow(() -> new UsernameNotFoundException("Unknown account"));
    }
}
