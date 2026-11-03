package com.docchat.common.db;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PgTextTest {

    @Test
    void removesCharactersThatTheDatabaseRejects() {
        assertThat(PgText.clean("  a\u0000b\uD83Dc 😀  ")).isEqualTo("abc 😀");
    }
}
