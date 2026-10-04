package com.sheltr.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Loads {@link SecurityConfig} with Boot's security auto-configuration, without a database, to
 * check startup behaviour. Credentials come from {@code sheltr.security.admin.*} properties.
 */
@ExtendWith(OutputCaptureExtension.class)
class SecurityConfigContextTest {

    @Configuration(proxyBeanMethods = false)
    @Import(SecurityConfig.class)
    static class Config {
    }

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    SecurityAutoConfiguration.class,
                    UserDetailsServiceAutoConfiguration.class,
                    ServletWebSecurityAutoConfiguration.class))
            .withUserConfiguration(Config.class)
            .withPropertyValues("sheltr.security.admin.username=admin");

    @Test
    void failsToStartWhenNeitherCredentialIsSet() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("SHELTR_ADMIN_PASSWORD")
                    .hasMessageContaining("SHELTR_ADMIN_PASSWORD_HASH");
        });
    }

    @Test
    void failsToStartWhenBothCredentialsAreSet() {
        runner.withPropertyValues(
                        "sheltr.security.admin.password=plain",
                        "sheltr.security.admin.password-hash=$2a$10$abcdefghijklmnopqrstuuJ1yC0z5r0q8bq7Y2b5cVZ8m4lQ9Wm6")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("not both");
                });
    }

    @Test
    void plaintextPasswordIsStoredAsBcryptAndNoGeneratedPasswordIsLogged(CapturedOutput output) {
        runner.withPropertyValues("sheltr.security.admin.password=plain-secret-value")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(UserDetailsService.class);

                    UserDetails admin = context.getBean(UserDetailsService.class).loadUserByUsername("admin");
                    assertThat(admin.getPassword()).startsWith("{bcrypt}").doesNotContain("plain-secret-value");
                    assertThat(admin.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_ADMIN");
                    assertThat(context.getBean(PasswordEncoder.class).matches("plain-secret-value", admin.getPassword()))
                            .isTrue();
                });
        assertThat(output).doesNotContain("Using generated security password").doesNotContain("plain-secret-value");
    }

    @Test
    void noBeanHoldsThePlaintextPasswordOrTheSettingsRecord() {
        runner.withPropertyValues("sheltr.security.admin.password=plain-secret-value")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(SecurityProperties.class);
                    assertThat(context).doesNotHaveBean(AdminCredentials.class);
                    assertThat(BeanSecrets.beansExposing(context.getBeanFactory(), "plain-secret-value")).isEmpty();
                });
    }

    @Test
    void bcryptCostBelow04FailsStartup() {
        runner.withPropertyValues("sheltr.security.admin.password-hash=" + hashWithCost("03"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining("SHELTR_ADMIN_PASSWORD_HASH");
                });
    }

    @Test
    void bcryptCostAbove31FailsStartup() {
        runner.withPropertyValues("sheltr.security.admin.password-hash=" + hashWithCost("32"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining("SHELTR_ADMIN_PASSWORD_HASH");
                });
    }

    @Test
    void bcryptCostBoundariesAndVariantsAreAccepted() {
        for (String hash : new String[] {hashWithCost("04"), hashWithCost("31"),
                validRawHash().replace("$2a$", "$2b$"), validRawHash().replace("$2a$", "$2y$")}) {
            runner.withPropertyValues("sheltr.security.admin.password-hash=" + hash)
                    .run(context -> assertThat(context).hasNotFailed());
        }
    }

    @Test
    void unsupportedBcryptVariantFailsStartup() {
        runner.withPropertyValues("sheltr.security.admin.password-hash=" + validRawHash().replace("$2a$", "$2x$"))
                .run(context -> assertThat(context).hasFailed());
    }

    private static String validRawHash() {
        return SecurityConfig.passwordEncoder().encode("hashed-secret").substring("{bcrypt}".length());
    }

    /** A syntactically valid bcrypt string with the given two-digit cost (not re-hashed). */
    private static String hashWithCost(String cost) {
        String raw = validRawHash();
        assertThat(raw).startsWith("$2a$10$");
        return "$2a$" + cost + raw.substring("$2a$10".length());
    }

    @Test
    void rawBcryptHashIsAccepted() {
        String hash = SecurityConfig.passwordEncoder().encode("hashed-secret").substring("{bcrypt}".length());
        runner.withPropertyValues("sheltr.security.admin.password-hash=" + hash)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    UserDetails admin = context.getBean(UserDetailsService.class).loadUserByUsername("admin");
                    assertThat(context.getBean(PasswordEncoder.class).matches("hashed-secret", admin.getPassword()))
                            .isTrue();
                });
    }

    @Test
    void prefixedBcryptHashIsAccepted() {
        String hash = SecurityConfig.passwordEncoder().encode("hashed-secret");
        runner.withPropertyValues("sheltr.security.admin.password-hash=" + hash)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    UserDetails admin = context.getBean(UserDetailsService.class).loadUserByUsername("admin");
                    assertThat(context.getBean(PasswordEncoder.class).matches("hashed-secret", admin.getPassword()))
                            .isTrue();
                });
    }

    @Test
    void nonBcryptHashFailsStartupWithoutEchoingIt() {
        runner.withPropertyValues("sheltr.security.admin.password-hash=not-a-hash-value")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining("SHELTR_ADMIN_PASSWORD_HASH")
                            .message().doesNotContain("not-a-hash-value");
                });
    }
}
