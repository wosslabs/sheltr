package com.sheltr.config;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.regex.Pattern;

/**
 * The admin user's name and stored (encoded) password. Never holds a plaintext password.
 *
 * @param username        admin username
 * @param encodedPassword delegating-encoder value, always {@code {bcrypt}$2...}
 */
record AdminCredentials(String username, String encodedPassword) {

    static final String ENV_PREFIX = "sheltr.security.admin";

    /** {@code $2a$}, {@code $2b$} or {@code $2y$}, cost 04 to 31, 53 characters of salt and hash. */
    private static final Pattern BCRYPT =
            Pattern.compile("^\\$2[aby]\\$(0[4-9]|[12][0-9]|3[01])\\$[./A-Za-z0-9]{53}$");
    private static final String BCRYPT_PREFIX = "{bcrypt}";

    /**
     * Binds {@code sheltr.security.admin.*} from the environment, checks it, and encodes it. This is
     * the single place where the credential rules run, so a misconfiguration fails startup:
     * neither or both of password and hash set, a missing username, or a hash that is not bcrypt
     * {@code $2a$/$2b$/$2y$} with cost 04 to 31. A plaintext password is bcrypt-encoded here; the
     * bound {@link SecurityProperties} holding it is a local and is dropped when this returns.
     *
     * <p>Accepted residual: the Spring {@link Environment} property source (environment variable
     * or test property) still holds the plaintext for the life of the application. No bean does.
     *
     * <p>No secret is logged or included in an exception message.
     *
     * @throws IllegalStateException on any misconfiguration
     */
    static AdminCredentials load(Environment environment, PasswordEncoder passwordEncoder) {
        SecurityProperties settings = Binder.get(environment)
                .bind(ENV_PREFIX, SecurityProperties.class)
                .orElseGet(() -> new SecurityProperties(null, null, null));
        settings.requireCredentials();
        if (settings.username() == null || settings.username().isBlank()) {
            throw new IllegalStateException("sheltr.security.admin.username must be set");
        }
        String encoded = settings.password() != null
                ? passwordEncoder.encode(settings.password())
                : normaliseHash(settings.passwordHash());
        return new AdminCredentials(settings.username(), encoded);
    }

    private static String normaliseHash(String hash) {
        String raw = hash.startsWith(BCRYPT_PREFIX) ? hash.substring(BCRYPT_PREFIX.length()) : hash;
        if (!BCRYPT.matcher(raw).matches()) {
            throw new IllegalStateException(
                    "SHELTR_ADMIN_PASSWORD_HASH must be a bcrypt hash ($2a$/$2b$/$2y$ with cost 04 to 31, "
                            + "optionally prefixed with {bcrypt})");
        }
        return BCRYPT_PREFIX + raw;
    }

    /** Masks the encoded password. */
    @Override
    public String toString() {
        return "AdminCredentials[username=" + username + ", encodedPassword=****]";
    }
}
