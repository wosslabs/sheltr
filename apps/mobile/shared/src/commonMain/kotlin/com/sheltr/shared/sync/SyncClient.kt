package com.sheltr.shared.sync

import com.sheltr.shared.bundle.BUNDLE_DOCUMENTS_DIR
import com.sheltr.shared.bundle.BUNDLE_MANIFEST_FILE
import com.sheltr.shared.bundle.BUNDLE_SIGNATURE_FILE
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess

/** Fetches the files of one published bundle. Every call returns the exact bytes served, or throws. */
interface SyncClient {
    /** bundle.json. */
    suspend fun fetchManifestBytes(): ByteArray

    /** bundle.json.sig (raw 64-byte Ed25519 signature). */
    suspend fun fetchSignatureBytes(): ByteArray

    /** documents/<id>.json. */
    suspend fun fetchDocumentFile(id: String): ByteArray
}

/** Thrown for a base URL that is not https or a response that is not a success. */
class SyncHttpException(message: String) : Exception(message)

/**
 * [SyncClient] over Ktor. [baseUrl] is the bundle directory, for example https://example.org/bundle; it must be
 * https (plain http is refused, so content cannot be read or replaced in transit before the signature check).
 *
 * The engine is the caller's: production passes HttpClient() with no engine, which uses the engine on the target's
 * classpath (Java on the JVM, Darwin on iOS, OkHttp on Android once added); tests pass a MockEngine.
 * The client does not close [http].
 */
class KtorSyncClient(private val http: HttpClient, baseUrl: String) : SyncClient {

    private val base: String = requireHttps(baseUrl).trimEnd('/')

    override suspend fun fetchManifestBytes(): ByteArray = fetch(BUNDLE_MANIFEST_FILE)

    override suspend fun fetchSignatureBytes(): ByteArray = fetch(BUNDLE_SIGNATURE_FILE)

    override suspend fun fetchDocumentFile(id: String): ByteArray {
        // Ids are hex from the publisher; refuse anything that could change the path.
        if (id.isEmpty() || !id.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            throw SyncHttpException("refusing document id with unexpected characters: $id")
        }
        return fetch("$BUNDLE_DOCUMENTS_DIR/$id.json")
    }

    private suspend fun fetch(path: String): ByteArray {
        val response: HttpResponse = http.get("$base/$path")
        if (!response.status.isSuccess()) {
            throw SyncHttpException("GET $path returned ${response.status}")
        }
        return response.readRawBytes()
    }
}

/** Returns [baseUrl] if it is an https URL; throws [SyncHttpException] otherwise. */
internal fun requireHttps(baseUrl: String): String {
    if (!baseUrl.startsWith("https://", ignoreCase = true) || baseUrl.length <= "https://".length) {
        throw SyncHttpException("bundle base URL must use https: $baseUrl")
    }
    return baseUrl
}
