package com.docchat.auth;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class AccountRepository {

    private static final RowMapper<Account> ROW_MAPPER = (row, rowNumber) -> new Account(
            row.getObject("id", UUID.class), row.getString("email"), row.getString("password_hash"));

    private final JdbcClient jdbcClient;

    AccountRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    Optional<Account> findByEmail(String normalizedEmail) {
        return jdbcClient
                .sql("SELECT id, email, password_hash FROM users WHERE email = :email")
                .param("email", normalizedEmail)
                .query(ROW_MAPPER)
                .optional();
    }

    /** @throws org.springframework.dao.DuplicateKeyException if the email is already registered */
    Account insert(String normalizedEmail, String passwordHash) {
        return jdbcClient
                .sql("""
                        INSERT INTO users (email, password_hash)
                        VALUES (:email, :passwordHash)
                        RETURNING id, email, password_hash
                        """)
                .param("email", normalizedEmail)
                .param("passwordHash", passwordHash)
                .query(ROW_MAPPER)
                .single();
    }
}
