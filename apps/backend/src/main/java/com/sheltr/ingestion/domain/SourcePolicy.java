package com.sheltr.ingestion.domain;

import java.net.URI;

/**
 * Decides whether a source URI may be fetched. Checked before any network call.
 */
public interface SourcePolicy {

    /** Returns true only when the URI may be fetched. Null, malformed or disallowed URIs return false. */
    boolean allows(String sourceUri);

    /**
     * The client-safe rejection message for a URI. A URI carrying credentials gets a fixed message that
     * echoes nothing from it; otherwise the message names only its host, truncated to
     * {@value #MAX_ECHOED_HOST_LENGTH} characters. Shared by the application service and the controller
     * so both report the same text.
     */
    static String rejectionMessage(String sourceUri) {
        if (hasCredentials(sourceUri)) {
            return "Source URI must not contain credentials.";
        }
        return "Source host not allowed: " + hostForMessage(sourceUri);
    }

    /** Upper bound on the host echoed back to the client in a rejection message. */
    int MAX_ECHOED_HOST_LENGTH = 255;

    private static boolean hasCredentials(String sourceUri) {
        if (sourceUri == null) {
            return false;
        }
        try {
            return URI.create(sourceUri).getRawUserInfo() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String hostForMessage(String sourceUri) {
        if (sourceUri == null) {
            return "(none)";
        }
        try {
            String host = URI.create(sourceUri).getHost();
            return host == null ? "(none)" : truncate(host, MAX_ECHOED_HOST_LENGTH);
        } catch (IllegalArgumentException e) {
            return "(unparseable)";
        }
    }

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
