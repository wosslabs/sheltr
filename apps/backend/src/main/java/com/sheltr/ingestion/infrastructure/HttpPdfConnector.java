package com.sheltr.ingestion.infrastructure;

import com.sheltr.ingestion.domain.RawContent;
import com.sheltr.ingestion.domain.SourceConnector;
import com.sheltr.knowledge.domain.Checksum;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class HttpPdfConnector implements SourceConnector {

    static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final HttpClient httpClient;

    public HttpPdfConnector() {
        this(DEFAULT_CONNECT_TIMEOUT, DEFAULT_REQUEST_TIMEOUT);
    }

    public HttpPdfConnector(Duration connectTimeout, Duration requestTimeout) {
        this.connectTimeout = connectTimeout;
        this.requestTimeout = requestTimeout;
        // Redirects are never followed: a 3xx is returned as-is and treated as a failure below.
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    Duration connectTimeout() {
        return connectTimeout;
    }

    Duration requestTimeout() {
        return requestTimeout;
    }

    @Override
    public RawContent fetch(String sourceUri) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(sourceUri))
                    .GET()
                    // Known limit: HttpRequest.timeout bounds the wait for response headers (and a stalled
                    // response), not the time spent streaming a slow body. A server that sends headers and
                    // then trickles the PDF bytes can hold this call open past requestTimeout.
                    .timeout(requestTimeout)
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            // Any non-200 status, including 3xx redirects, is a failure.
            if (response.statusCode() != 200) {
                throw new RuntimeException("Failed to download " + sourceUri + ": HTTP " + response.statusCode());
            }
            byte[] bytes = response.body();
            return new RawContent(bytes, new Checksum(Sha256Hex.of(bytes)));
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to download " + sourceUri, e);
        }
    }
}
