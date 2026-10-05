package com.sheltr.publisher.fetch

import java.net.URI

/** Decides whether a source URI may be fetched. Checked before any network call. */
fun interface SourcePolicy {

    /** True only when the URI may be fetched. Null, malformed or disallowed URIs return false. */
    fun allows(sourceUri: String?): Boolean

    companion object {

        /** Upper bound on the host echoed back in a rejection message. */
        const val MAX_ECHOED_HOST_LENGTH = 255

        /**
         * The rejection message for a URI. A URI carrying credentials gets a fixed message that echoes nothing
         * from it; otherwise the message names only its host, truncated to [MAX_ECHOED_HOST_LENGTH] characters.
         */
        fun rejectionMessage(sourceUri: String?): String {
            if (hasCredentials(sourceUri)) {
                return "Source URI must not contain credentials."
            }
            return "Source host not allowed: " + hostForMessage(sourceUri)
        }

        private fun hasCredentials(sourceUri: String?): Boolean {
            if (sourceUri == null) {
                return false
            }
            return try {
                URI.create(sourceUri).rawUserInfo != null
            } catch (e: IllegalArgumentException) {
                false
            }
        }

        private fun hostForMessage(sourceUri: String?): String {
            if (sourceUri == null) {
                return "(none)"
            }
            return try {
                val host = URI.create(sourceUri).host
                if (host == null) "(none)" else host.take(MAX_ECHOED_HOST_LENGTH)
            } catch (e: IllegalArgumentException) {
                "(unparseable)"
            }
        }
    }
}
