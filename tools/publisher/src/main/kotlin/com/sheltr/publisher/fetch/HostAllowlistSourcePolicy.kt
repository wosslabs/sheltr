package com.sheltr.publisher.fetch

import java.net.URI

/**
 * Allows only https URIs whose host exactly matches one of [allowedHosts] (case-insensitive), with no explicit
 * port other than 443 and no userinfo. An empty list denies everything.
 */
class HostAllowlistSourcePolicy(allowedHosts: Collection<String>) : SourcePolicy {

    private val allowed: Set<String> = allowedHosts.map { it.lowercase() }.toSet()

    override fun allows(sourceUri: String?): Boolean {
        if (sourceUri == null) {
            return false
        }
        val uri = try {
            URI.create(sourceUri)
        } catch (e: IllegalArgumentException) {
            return false
        }
        if (!"https".equals(uri.scheme, ignoreCase = true) ||
            uri.rawUserInfo != null ||
            (uri.port != -1 && uri.port != HTTPS_PORT)
        ) {
            return false
        }
        val host = uri.host ?: return false
        return host.lowercase() in allowed
    }

    private companion object {
        const val HTTPS_PORT = 443
    }
}
