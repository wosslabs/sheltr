package com.sheltr.shared.bundle

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** A bundle file that does not follow the format: unsupported version, blank id, malformed JSON, bad checksum. */
class BundleFormatException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/**
 * Parses bundle.json. Rejects any formatVersion other than [BUNDLE_FORMAT_VERSION] before the rest of the file is
 * decoded, so a future layout gets a clear message instead of a field error. Rejects blank document and chunk ids.
 */
fun parseBundleManifest(text: String): BundleManifest {
    val root = try {
        BundleJson.parseToJsonElement(text).jsonObject
    } catch (e: SerializationException) {
        throw BundleFormatException("bundle.json is not valid JSON: ${e.message}", e)
    } catch (e: IllegalArgumentException) {
        throw BundleFormatException("bundle.json must be a JSON object", e)
    }
    val rawVersion = root["formatVersion"]
    val version = (rawVersion as? JsonPrimitive)?.intOrNull
    if (version != BUNDLE_FORMAT_VERSION) {
        throw BundleFormatException(
            "Unsupported bundle formatVersion ${rawVersion ?: "(missing)"}; this app reads formatVersion $BUNDLE_FORMAT_VERSION only.",
        )
    }

    val manifest = decode("bundle.json") { BundleJson.decodeFromString(BundleManifest.serializer(), text) }
    for (document in manifest.documents) {
        requireNotBlank(document.id, "document id")
        for (chunk in document.chunks) {
            requireNotBlank(chunk.id, "chunk id in document ${document.id}")
        }
    }
    return manifest
}

/** Parses documents/<id>.json from its text. Use [parseDocumentFileBytes] when the bytes are also needed for checksums. */
fun parseDocumentFile(text: String): DocumentFile {
    val file = decode("document file") { BundleJson.decodeFromString(DocumentFile.serializer(), text) }
    requireNotBlank(file.id, "document file id")
    for (chunk in file.chunks) {
        requireNotBlank(chunk.id, "chunk id in document ${file.id}")
    }
    return file
}

/** Parses documents/<id>.json from the exact bytes read from disk (UTF-8). */
fun parseDocumentFileBytes(bytes: ByteArray): DocumentFile = parseDocumentFile(bytes.decodeToString())

/**
 * The fileChecksum rule: SHA-256 (lower-case hex) of the exact bytes of documents/<id>.json, as stored. Compute it
 * over the bytes read from disk, never over a re-encoded string.
 */
fun documentFileChecksum(fileBytes: ByteArray): String = sha256Hex(fileBytes)

/** Throws [BundleFormatException] unless [fileBytes] are the bytes the manifest entry [document] describes. */
fun verifyDocumentFile(document: BundleDocument, fileBytes: ByteArray) {
    val actual = documentFileChecksum(fileBytes)
    if (actual != document.fileChecksum) {
        throw BundleFormatException(
            "Document file ${document.id} does not match the manifest: expected fileChecksum ${document.fileChecksum}, got $actual.",
        )
    }
}

private inline fun <T> decode(what: String, block: () -> T): T = try {
    block()
} catch (e: SerializationException) {
    throw BundleFormatException("$what does not match the bundle format: ${e.message}", e)
}

private fun requireNotBlank(value: String, what: String) {
    if (value.isBlank()) {
        throw BundleFormatException("A $what must not be blank.")
    }
}
