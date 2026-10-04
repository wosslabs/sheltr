package com.sheltr.ingestion.config;

import com.sheltr.ingestion.domain.SourcePolicy;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Allows only https URIs whose host exactly matches one of {@code sheltr.ingestion.allowed-hosts}
 * (case-insensitive), with no explicit port other than 443 and no userinfo. An empty list denies everything.
 */
public class HostAllowlistSourcePolicy implements SourcePolicy {

    private static final int HTTPS_PORT = 443;

    private final Set<String> allowedHosts;

    public HostAllowlistSourcePolicy(IngestionProperties properties) {
        this.allowedHosts = properties.allowedHosts().stream()
                .map(host -> host.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public boolean allows(String sourceUri) {
        if (sourceUri == null) {
            return false;
        }
        URI uri;
        try {
            uri = URI.create(sourceUri);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getRawUserInfo() != null
                || (uri.getPort() != -1 && uri.getPort() != HTTPS_PORT)) {
            return false;
        }
        String host = uri.getHost();
        return host != null && allowedHosts.contains(host.toLowerCase(Locale.ROOT));
    }
}
