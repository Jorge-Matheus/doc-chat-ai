package com.docchat.auth;

import java.util.Locale;
import java.util.UUID;

record Account(UUID id, String email, String passwordHash) {

    static String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }
}
