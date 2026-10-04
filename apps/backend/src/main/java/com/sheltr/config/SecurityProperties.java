package com.sheltr.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Admin credentials for {@code /api/v1/admin/**}. Bound from {@code sheltr.security.admin.*}.
 *
 * <p>Exactly one of {@code password} (from {@code SHELTR_ADMIN_PASSWORD}) or {@code passwordHash}
 * (from {@code SHELTR_ADMIN_PASSWORD_HASH}, a bcrypt hash) must be set in every profile. No
 * password is defined in YAML. Blank values are treated as absent.
 */
@ConfigurationProperties("sheltr.security.admin")
public record SecurityProperties(String username, String password, String passwordHash) {

    public SecurityProperties {
        password = blankToNull(password);
        passwordHash = blankToNull(passwordHash);
    }

    /**
     * Returns this instance after checking the credential rules.
     *
     * @throws IllegalStateException when the credentials are missing or ambiguous
     */
    public SecurityProperties validated() {
        requireCredentials();
        return this;
    }

    /**
     * Fails fast on credential misconfiguration. Messages name the environment variables
     * {@code SHELTR_ADMIN_PASSWORD} and {@code SHELTR_ADMIN_PASSWORD_HASH}.
     *
     * @throws IllegalStateException when both password and hash are set, or when neither is set
     */
    public void requireCredentials() {
        boolean hasPassword = password != null;
        boolean hasHash = passwordHash != null;

        if (hasPassword && hasHash) {
            throw new IllegalStateException(
                    "Set SHELTR_ADMIN_PASSWORD or SHELTR_ADMIN_PASSWORD_HASH, not both");
        }
        if (!hasPassword && !hasHash) {
            throw new IllegalStateException(
                    "SHELTR_ADMIN_PASSWORD or SHELTR_ADMIN_PASSWORD_HASH must be set in every profile");
        }
    }

    /**
     * Masks both secrets so they never reach logs. Other fields print normally.
     */
    @Override
    public String toString() {
        return "SecurityProperties[username=" + username
                + ", password=" + mask(password)
                + ", passwordHash=" + mask(passwordHash) + "]";
    }

    private static String mask(String secret) {
        return secret == null ? "null" : "****";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
