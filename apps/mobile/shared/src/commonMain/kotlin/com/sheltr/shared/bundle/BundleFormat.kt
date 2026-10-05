package com.sheltr.shared.bundle

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * The corpus bundle as the publisher writes it (tools/publisher, bundle/BundleFormat.kt and BundleBuilder.kt).
 * The field names and nullability must match the publisher exactly; the publisher's classes are named
 * ChunkRef and BundleChunk, the names here are the shared module's, and the JSON is identical.
 *
 *   bundle.json          BundleManifest (signed by bundle.json.sig)
 *   documents/<id>.json  DocumentFile, one per document; BundleDocument.fileChecksum covers its exact bytes
 *
 * Every checksum is lower-case hex SHA-256.
 */

/** Version of the bundle layout. This app reads only this version. */
const val BUNDLE_FORMAT_VERSION: Int = 1

const val BUNDLE_MANIFEST_FILE: String = "bundle.json"
const val BUNDLE_SIGNATURE_FILE: String = "bundle.json.sig"
const val BUNDLE_DOCUMENTS_DIR: String = "documents"

@Serializable
data class BundleManifest(
    val formatVersion: Int,
    /** UTC build time, ISO-8601 to the second, for example 2026-10-05T12:00:00Z. */
    val bundleVersion: String,
    val documents: List<BundleDocument>,
)

@Serializable
data class BundleDocument(
    /** First 32 hex characters of the SHA-256 of the source URI. Stable across builds. */
    val id: String,
    val title: String,
    val agency: String,
    /** ISO date, YYYY-MM-DD. */
    val publishedDate: String,
    val licence: String,
    /** ISO 3166-1 alpha-2 code, or null for global guidance. */
    val country: String?,
    /** DisasterType names. */
    val hazards: List<String>,
    val sourceUri: String,
    /** pdf or html. */
    val sourceType: String,
    /** The approved content checksum: SHA-256 of the PDF bytes, or of the extracted HTML text. */
    val checksum: String,
    /** SHA-256 of the exact bytes of documents/<id>.json. */
    val fileChecksum: String,
    val chunks: List<BundleChunkRef>,
)

@Serializable
data class BundleChunkRef(
    /** "<documentId>-<index>", index from 0. */
    val id: String,
    /** SHA-256 of the chunk content in UTF-8. */
    val checksum: String,
)

@Serializable
data class DocumentFile(
    val id: String,
    val chunks: List<DocumentChunk>,
)

@Serializable
data class DocumentChunk(
    val id: String,
    val content: String,
    /** 1-based PDF page, or null for HTML. */
    val pageNumber: Int?,
)

/**
 * The JSON settings the publisher uses for every bundle file. Nulls are written explicitly and the layout is
 * pretty-printed with four-space indentation, so re-encoding a parsed file reproduces the publisher's bytes.
 */
val BundleJson: Json = Json {
    prettyPrint = true
    encodeDefaults = true
    explicitNulls = true
}
