package com.sheltr.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityPropertiesTest {

    @Test
    void rejectsMissingPasswordWhenNotLocal() {
        var props = new SecurityProperties("admin", null, null);
        assertThatThrownBy(() -> props.requireCredentials(false))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("SHELTR_ADMIN_PASSWORD");
    }

    @Test
    void rejectsBothPasswordAndHash() {
        var props = new SecurityProperties("admin", "secret", "$2a$10$hash");
        assertThatThrownBy(() -> props.requireCredentials(false))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not both");
    }

    @Test
    void allowsLocalDefaultCredentialOnlyWhenLocal() {
        var props = new SecurityProperties("admin", "{noop}local-dev", null);
        assertThatCode(() -> props.requireCredentials(true)).doesNotThrowAnyException();
    }
}
