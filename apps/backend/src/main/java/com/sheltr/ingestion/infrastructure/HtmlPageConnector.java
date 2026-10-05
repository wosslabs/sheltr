package com.sheltr.ingestion.infrastructure;

import com.sheltr.ingestion.domain.RawContent;
import com.sheltr.ingestion.domain.SourceConnector;
import com.sheltr.ingestion.domain.SourcePolicy;
import com.sheltr.knowledge.domain.Checksum;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Set;

/**
 * Fetches an HTML page and returns its main text. The returned bytes are the extracted text in UTF-8, and the
 * checksum is the SHA-256 of those bytes, so a changed page changes the checksum.
 *
 * <p>Redirects are followed by hand so every target can be checked against the {@link SourcePolicy} before it
 * is requested. At most {@value #MAX_REDIRECTS} redirects are followed. A disallowed target fails without a
 * request to it.
 */
public class HtmlPageConnector implements SourceConnector {

    static final int MAX_REDIRECTS = 3;
    private static final Set<Integer> REDIRECT_STATUSES = Set.of(301, 302, 303, 307, 308);

    private final SourcePolicy policy;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final MainContentExtractor extractor;
    private final HttpClient httpClient;

    public HtmlPageConnector(SourcePolicy policy, Duration connectTimeout, Duration requestTimeout) {
        this.policy = policy;
        this.connectTimeout = connectTimeout;
        this.requestTimeout = requestTimeout;
        this.extractor = new MainContentExtractor();
        // Redirects are followed manually in fetch(), never by the client.
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
        URI current = checkedUri(sourceUri);
        int redirectsFollowed = 0;
        try {
            while (true) {
                HttpResponse<String> response = send(current);
                int status = response.statusCode();
                if (REDIRECT_STATUSES.contains(status)) {
                    if (redirectsFollowed == MAX_REDIRECTS) {
                        throw new RuntimeException("Too many redirects (more than " + MAX_REDIRECTS
                                + ") fetching " + sourceUri);
                    }
                    URI next = redirectTarget(current, response);
                    if (!policy.allows(next.toString())) {
                        throw new RuntimeException("Redirect from " + current.getHost() + " to a disallowed target. "
                                + SourcePolicy.rejectionMessage(next.toString()));
                    }
                    current = next;
                    redirectsFollowed++;
                    continue;
                }
                if (status != 200) {
                    throw new RuntimeException("Failed to download " + sourceUri + ": HTTP " + status);
                }
                String text = extractor.extract(response.body());
                if (text.isEmpty()) {
                    throw new RuntimeException("No main text could be extracted from " + sourceUri);
                }
                byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
                return new RawContent(bytes, new Checksum(sha256Hex(bytes)));
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("Failed to download " + sourceUri, e);
        }
    }

    private URI checkedUri(String sourceUri) {
        if (!policy.allows(sourceUri)) {
            throw new RuntimeException(SourcePolicy.rejectionMessage(sourceUri));
        }
        return URI.create(sourceUri);
    }

    private HttpResponse<String> send(URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(requestTimeout)
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static URI redirectTarget(URI current, HttpResponse<?> response) {
        String location = response.headers().firstValue("Location").orElseThrow(() ->
                new RuntimeException("Redirect without a Location header from " + current.getHost()));
        try {
            return current.resolve(location);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Redirect with an unparseable Location from " + current.getHost(), e);
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest(bytes)) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
