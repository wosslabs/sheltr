package com.sheltr.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Admin credentials for {@code /api/v1/admin/**}. Bound from {@code sheltr.security.admin.*}.
 *
 * <p>Exactly one of {@code password} (a {@code {noop}} or plain value, local development only) or
 * {@code passwordHash} (a bcrypt hash, production) is expected. Blank values are treated as absent.
 */
@ConfigurationProperties("sheltr.security.admin")
public record SecurityProperties(String username, String password, String passwordHash) {

    public SecurityProperties {
        password = blankToNull(password);
        passwordHash = blankToNull(passwordHash);
    }

    /**
     * Returns this instance after checking the credential rules for the given profile.
     *
     * @throws IllegalStateException when the configuration is unsafe for the profile
     */
    public SecurityProperties validated(boolean local) {
        requireCredentials(local);
        return this;
    }

    /**
     * Fails fast on credential misconfiguration. Messages name the environment variables
     * {@code SHELTR_ADMIN_PASSWORD} and {@code SHELTR_ADMIN_PASSWORD_HASH}.
     *
     * @param local true when the {@code local} profile is active
     * @throws IllegalStateException when both password and hash are set, when neither is set outside
     *                               the local profile, or when the local profile has no password
     */
    public void requireCredentials(boolean local) {
        boolean hasPassword = password != null;
        boolean hasHash = passwordHash != null;

        if (hasPassword && hasHash) {
            throw new IllegalStateException(
                    "Set SHELTR_ADMIN_PASSWORD or SHELTR_ADMIN_PASSWORD_HASH, not both");
        }
        if (!hasPassword && !hasHash && !local) {
            throw new IllegalStateException(
                    "SHELTR_ADMIN_PASSWORD or SHELTR_ADMIN_PASSWORD_HASH must be set outside the local profile");
        }
        if (local && !hasPassword) {
            throw new IllegalStateException(
                    "The local profile requires sheltr.security.admin.password (SHELTR_ADMIN_PASSWORD is not set)");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
