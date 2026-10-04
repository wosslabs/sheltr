package com.sheltr.config;

/**
 * Raw admin credential settings from {@code sheltr.security.admin.*}: {@code username},
 * {@code password} (from {@code SHELTR_ADMIN_PASSWORD}) and {@code passwordHash} (from
 * {@code SHELTR_ADMIN_PASSWORD_HASH}, a bcrypt hash).
 *
 * <p>Not a Spring bean and deliberately not {@code @ConfigurationProperties}: it is bound only
 * inside {@link AdminCredentials#load}, used once to build the encoded credential, and then
 * dropped, so no bean holds the plaintext after startup.
 *
 * <p>Exactly one of {@code password} or {@code passwordHash} must be set in every profile. No
 * password is defined in YAML. Blank values are treated as absent.
 */
record SecurityProperties(String username, String password, String passwordHash) {

    SecurityProperties {
        password = blankToNull(password);
        passwordHash = blankToNull(passwordHash);
    }

    /**
     * Fails fast on credential misconfiguration. Messages name the environment variables
     * {@code SHELTR_ADMIN_PASSWORD} and {@code SHELTR_ADMIN_PASSWORD_HASH}.
     *
     * @throws IllegalStateException when both password and hash are set, or when neither is set
     */
    void requireCredentials() {
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
