package com.sheltr.publisher.html

import com.sheltr.publisher.fetch.Checksum
import com.sheltr.publisher.fetch.RawContent
import com.sheltr.publisher.fetch.SourceConnector
import com.sheltr.publisher.fetch.SourcePolicy
import com.sheltr.publisher.fetch.sha256Hex
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * Fetches an HTML page and returns its main text. The returned bytes are the extracted text in UTF-8, and the
 * checksum is the SHA-256 of those bytes, so a changed page changes the checksum.
 *
 * The body is decoded with the charset declared in the `Content-Type` header. If none is declared, or the
 * declared name is unknown, UTF-8 is used. A `<meta charset>` in the page is ignored; that is an accepted limit.
 *
 * Redirects are followed by hand so every target can be checked against the [SourcePolicy] before it is
 * requested. At most [MAX_REDIRECTS] redirects are followed. A disallowed target fails without a request to it.
 * A page with no extractable text returns empty bytes; the caller decides whether that fails the run.
 */
class HtmlPageConnector(
    private val policy: SourcePolicy,
    connectTimeout: Duration = Duration.ofSeconds(10),
    private val requestTimeout: Duration = Duration.ofSeconds(60),
) : SourceConnector {

    private val extractor = MainContentExtractor()

    // Redirects are followed manually in fetch(), never by the client.
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(connectTimeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    override fun fetch(sourceUri: String): RawContent {
        var current = checkedUri(sourceUri)
        var redirectsFollowed = 0
        try {
            while (true) {
                val response = send(current)
                val status = response.statusCode()
                if (status in REDIRECT_STATUSES) {
                    if (redirectsFollowed == MAX_REDIRECTS) {
                        throw RuntimeException(
                            "Too many redirects (more than $MAX_REDIRECTS) fetching $sourceUri",
                        )
                    }
                    val next = redirectTarget(current, response)
                    checkRedirectTarget(next)
                    current = next
                    redirectsFollowed++
                    continue
                }
                if (status != 200) {
                    throw RuntimeException("Failed to download $sourceUri: HTTP $status")
                }
                val html = decode(response.body(), response.headers().firstValue("Content-Type").orElse(null))
                val bytes = extractor.extract(html).toByteArray(StandardCharsets.UTF_8)
                return RawContent(bytes, Checksum(sha256Hex(bytes)))
            }
        } catch (e: IOException) {
            throw RuntimeException("Failed to download $sourceUri", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RuntimeException("Failed to download $sourceUri", e)
        }
    }

    /**
     * Decides whether a redirect target may be requested. Throws, with no request made, unless the policy allows
     * the target.
     */
    internal fun checkRedirectTarget(target: URI) {
        if (!policy.allows(target.toString())) {
            throw RuntimeException(
                "Redirect to a disallowed target. " + SourcePolicy.rejectionMessage(target.toString()),
            )
        }
    }

    private fun checkedUri(sourceUri: String): URI {
        if (!policy.allows(sourceUri)) {
            throw RuntimeException(SourcePolicy.rejectionMessage(sourceUri))
        }
        return URI.create(sourceUri)
    }

    private fun send(uri: URI): HttpResponse<ByteArray> {
        val request = HttpRequest.newBuilder(uri)
            .GET()
            .timeout(requestTimeout)
            .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
    }

    companion object {

        /** Redirects followed before the fetch is refused. */
        internal const val MAX_REDIRECTS = 3

        private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)

        private val CHARSET_PARAMETER = Regex("(?i)charset\\s*=\\s*\"?([^\";\\s]+)\"?")

        /** Decodes the body with the charset named in the Content-Type header, falling back to UTF-8. */
        internal fun decode(body: ByteArray, contentType: String?): String = String(body, charsetOf(contentType))

        internal fun charsetOf(contentType: String?): Charset {
            if (contentType == null) {
                return StandardCharsets.UTF_8
            }
            val match = CHARSET_PARAMETER.find(contentType) ?: return StandardCharsets.UTF_8
            return try {
                Charset.forName(match.groupValues[1])
            } catch (e: IllegalArgumentException) {
                // Unknown or illegal charset name (UnsupportedCharsetException and IllegalCharsetNameException).
                StandardCharsets.UTF_8
            }
        }

        private fun redirectTarget(current: URI, response: HttpResponse<*>): URI {
            val location = response.headers().firstValue("Location").orElseThrow {
                RuntimeException("Redirect without a Location header from ${current.host}")
            }
            try {
                return current.resolve(location)
            } catch (e: IllegalArgumentException) {
                throw RuntimeException("Redirect with an unparseable Location from ${current.host}", e)
            }
        }
    }
}
