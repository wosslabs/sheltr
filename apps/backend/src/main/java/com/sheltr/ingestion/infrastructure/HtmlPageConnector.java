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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches an HTML page and returns its main text. The returned bytes are the extracted text in UTF-8, and the
 * checksum is the SHA-256 of those bytes, so a changed page changes the checksum.
 *
 * <p>The body is decoded with the charset declared in the {@code Content-Type} header. If none is declared, or the
 * declared name is unknown, UTF-8 is used. A {@code <meta charset>} in the page is ignored; that is an accepted
 * limit.
 *
 * <p>Redirects are followed by hand so every target can be checked against the {@link SourcePolicy} before it
 * is requested. At most {@value #MAX_REDIRECTS} redirects are followed. A disallowed target fails without a
 * request to it. A page with no extractable text returns empty bytes; the caller decides whether that fails
 * the run.
 */
public class HtmlPageConnector implements SourceConnector {

    static final int MAX_REDIRECTS = 3;
    private static final Set<Integer> REDIRECT_STATUSES = Set.of(301, 302, 303, 307, 308);
    private static final Pattern CHARSET_PARAMETER =
            Pattern.compile("(?i)charset\\s*=\\s*\"?([^\";\\s]+)\"?");

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
                HttpResponse<byte[]> response = send(current);
                int status = response.statusCode();
                if (REDIRECT_STATUSES.contains(status)) {
                    if (redirectsFollowed == MAX_REDIRECTS) {
                        throw new RuntimeException("Too many redirects (more than " + MAX_REDIRECTS
                                + ") fetching " + sourceUri);
                    }
                    URI next = redirectTarget(current, response);
                    checkRedirectTarget(next);
                    current = next;
                    redirectsFollowed++;
                    continue;
                }
                if (status != 200) {
                    throw new RuntimeException("Failed to download " + sourceUri + ": HTTP " + status);
                }
                String html = decode(response.body(), response.headers().firstValue("Content-Type").orElse(null));
                byte[] bytes = extractor.extract(html).getBytes(StandardCharsets.UTF_8);
                return new RawContent(bytes, new Checksum(Sha256Hex.of(bytes)));
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("Failed to download " + sourceUri, e);
        }
    }

    /**
     * Decides whether a redirect target may be requested. Throws, with no request made, unless the policy allows
     * the target.
     */
    void checkRedirectTarget(URI target) {
        if (!policy.allows(target.toString())) {
            throw new RuntimeException("Redirect to a disallowed target. "
                    + SourcePolicy.rejectionMessage(target.toString()));
        }
    }

    /** Decodes the body with the charset named in the Content-Type header, falling back to UTF-8. */
    static String decode(byte[] body, String contentType) {
        return new String(body, charsetOf(contentType));
    }

    static Charset charsetOf(String contentType) {
        if (contentType == null) {
            return StandardCharsets.UTF_8;
        }
        Matcher matcher = CHARSET_PARAMETER.matcher(contentType);
        if (!matcher.find()) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(matcher.group(1));
        } catch (IllegalArgumentException e) {
            // Unknown or illegal charset name (UnsupportedCharsetException and IllegalCharsetNameException).
            return StandardCharsets.UTF_8;
        }
    }

    private URI checkedUri(String sourceUri) {
        if (!policy.allows(sourceUri)) {
            throw new RuntimeException(SourcePolicy.rejectionMessage(sourceUri));
        }
        return URI.create(sourceUri);
    }

    private HttpResponse<byte[]> send(URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(requestTimeout)
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
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
}
