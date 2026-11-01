package com.sheltr.publisher.pdf

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
import java.time.Duration

/**
 * Downloads a PDF over HTTP. Redirects are never followed (any 3xx, like
 * any other non-200 status, fails), and the checksum is the SHA-256 of the downloaded bytes.
 *
 * The URI is checked against the [SourcePolicy] before any request is made.
 *
 * Known limit: the request timeout bounds the wait for response headers, not the time spent
 * streaming a slow body.
 */
class HttpPdfConnector(
    private val policy: SourcePolicy,
    connectTimeout: Duration = Duration.ofSeconds(10),
    private val requestTimeout: Duration = Duration.ofSeconds(60),
) : SourceConnector {

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(connectTimeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    override fun fetch(sourceUri: String): RawContent {
        if (!policy.allows(sourceUri)) {
            throw RuntimeException(SourcePolicy.rejectionMessage(sourceUri))
        }
        try {
            val request = HttpRequest.newBuilder(URI.create(sourceUri))
                .GET()
                .timeout(requestTimeout)
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
            if (response.statusCode() != 200) {
                throw RuntimeException("Failed to download $sourceUri: HTTP ${response.statusCode()}")
            }
            val bytes = response.body()
            return RawContent(bytes, Checksum(sha256Hex(bytes)))
        } catch (e: IOException) {
            throw RuntimeException("Failed to download $sourceUri", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RuntimeException("Failed to download $sourceUri", e)
        }
    }
}
