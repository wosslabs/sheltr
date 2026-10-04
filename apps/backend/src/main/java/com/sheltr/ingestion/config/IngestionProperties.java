package com.sheltr.ingestion.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Ingestion settings. Bound from {@code sheltr.ingestion.*}.
 *
 * @param allowedHosts hostnames the connector may fetch; empty denies every host
 */
@ConfigurationProperties("sheltr.ingestion")
public record IngestionProperties(List<String> allowedHosts) {

    public IngestionProperties {
        allowedHosts = allowedHosts == null ? List.of() : List.copyOf(allowedHosts);
    }
}
