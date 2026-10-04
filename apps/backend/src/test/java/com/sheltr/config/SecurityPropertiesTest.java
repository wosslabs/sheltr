package com.sheltr.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityPropertiesTest {

    @Test
    void rejectsMissingCredentialsInEveryProfile() {
        var props = new SecurityProperties("admin", null, null);
        assertThatThrownBy(props::requireCredentials)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("SHELTR_ADMIN_PASSWORD")
            .hasMessageContaining("SHELTR_ADMIN_PASSWORD_HASH");
    }

    @Test
    void rejectsBothPasswordAndHash() {
        var props = new SecurityProperties("admin", "secret", "$2a$10$hash");
        assertThatThrownBy(props::requireCredentials)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not both");
    }

    @Test
    void acceptsPasswordOnly() {
        var props = new SecurityProperties("admin", "secret", null);
        assertThatCode(props::requireCredentials).doesNotThrowAnyException();
    }

    @Test
    void acceptsHashOnly() {
        var props = new SecurityProperties("admin", null, "$2a$10$hash");
        assertThatCode(props::requireCredentials).doesNotThrowAnyException();
    }
}
