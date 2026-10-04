package com.sheltr.ingestion.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Ingestion settings. Bound from {@code sheltr.ingestion.*}.
 *
 * @param allowedHosts   hostnames the connector may fetch; empty denies every host
 * @param connectTimeout TCP/TLS connect timeout for source fetches; defaults to 10 s; must be positive
 * @param requestTimeout per-request timeout for source fetches; defaults to 60 s; must be positive
 */
@ConfigurationProperties("sheltr.ingestion")
public record IngestionProperties(List<String> allowedHosts, Duration connectTimeout, Duration requestTimeout) {

    static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(60);

    public IngestionProperties {
        allowedHosts = allowedHosts == null ? List.of() : List.copyOf(allowedHosts);
        connectTimeout = connectTimeout == null ? DEFAULT_CONNECT_TIMEOUT : connectTimeout;
        requestTimeout = requestTimeout == null ? DEFAULT_REQUEST_TIMEOUT : requestTimeout;
        requirePositive("connect-timeout", connectTimeout);
        requirePositive("request-timeout", requestTimeout);
    }

    private static void requirePositive(String name, Duration value) {
        if (!value.isPositive()) {
            throw new IllegalArgumentException("sheltr.ingestion." + name + " must be positive, was " + value);
        }
    }
}
