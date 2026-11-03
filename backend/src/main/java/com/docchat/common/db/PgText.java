package com.docchat.common.db;

public final class PgText {

    private PgText() {}

    /** PostgreSQL rejects NUL characters, and its driver rejects unpaired surrogates. */
    public static String clean(String text) {
        return text.replaceAll("[\\x00\\p{Cs}]", "").strip();
    }
}
