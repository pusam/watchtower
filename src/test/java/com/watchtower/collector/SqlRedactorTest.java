package com.watchtower.collector;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SqlRedactorTest {

    @Test
    void redacts_singleQuotedStrings() {
        assertThat(SqlRedactor.redact("SELECT * FROM users WHERE email = 'alice@example.com'"))
                .isEqualTo("SELECT * FROM users WHERE email = '?'");
    }

    @Test
    void redacts_escapedQuotesInString() {
        assertThat(SqlRedactor.redact("SELECT 'it''s fine' FROM t"))
                .isEqualTo("SELECT '?' FROM t");
    }

    @Test
    void redacts_longDigitRun() {
        assertThat(SqlRedactor.redact("WHERE user_id = 1234567890123"))
                .isEqualTo("WHERE user_id = ?");
    }

    @Test
    void preserves_shortNumbers() {
        assertThat(SqlRedactor.redact("LIMIT 100 OFFSET 5"))
                .isEqualTo("LIMIT 100 OFFSET 5");
    }

    @Test
    void redacts_hexLiteral() {
        assertThat(SqlRedactor.redact("WHERE token = 0xDEADBEEFCAFE"))
                .isEqualTo("WHERE token = ?");
    }

    @Test
    void doesNotMangleIdentifiersContainingDigits() {
        assertThat(SqlRedactor.redact("SELECT col2, col3 FROM t1"))
                .isEqualTo("SELECT col2, col3 FROM t1");
    }

    @Test
    void handlesNullAndEmpty() {
        assertThat(SqlRedactor.redact(null)).isNull();
        assertThat(SqlRedactor.redact("")).isEqualTo("");
    }

    @Test
    void redactsMultipleLiteralsInOneStatement() {
        assertThat(SqlRedactor.redact(
                "INSERT INTO creds(user, token) VALUES ('bob', 'secret-xyz')"))
                .isEqualTo("INSERT INTO creds(user, token) VALUES ('?', '?')");
    }
}
