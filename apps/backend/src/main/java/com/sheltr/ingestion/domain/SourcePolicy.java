package com.sheltr.ingestion.domain;

import java.net.URI;

/**
 * Decides whether a source URI may be fetched. Checked before any network call.
 */
public interface SourcePolicy {

    /** Returns true only when the URI may be fetched. Null, malformed or disallowed URIs return false. */
    boolean allows(String sourceUri);

    /**
     * The client-safe rejection message for a URI, naming only its host. Shared by the application
     * service and the controller so both report the same text.
     */
    static String rejectionMessage(String sourceUri) {
        return "Source host not allowed: " + hostForMessage(sourceUri);
    }

    private static String hostForMessage(String sourceUri) {
        if (sourceUri == null) {
            return "(none)";
        }
        try {
            String host = URI.create(sourceUri).getHost();
            return host == null ? "(none)" : host;
        } catch (IllegalArgumentException e) {
            return "(unparseable)";
        }
    }
}
