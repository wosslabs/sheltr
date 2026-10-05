package com.sheltr.shared.bundle

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BundleParserTest {

    private val manifest: BundleManifest = parseBundleManifest(PublisherFixture.BUNDLE_JSON)
    private val document: BundleDocument = manifest.documents.single()
    private val documentBytes: ByteArray = PublisherFixture.DOCUMENT_JSON.encodeToByteArray()

    @Test
    fun manifestFromThePublisherParses() {
        assertEquals(1, manifest.formatVersion)
        assertEquals("2026-10-05T12:00:00Z", manifest.bundleVersion)
        assertEquals(1, manifest.documents.size)

        assertEquals(PublisherFixture.DOCUMENT_ID, document.id)
        assertEquals("Flood guide", document.title)
        assertEquals("Example Agency", document.agency)
        assertEquals("2024-03-15", document.publishedDate)
        assertEquals("CC-BY-4.0", document.licence)
        assertEquals("NO", document.country)
        assertEquals(listOf("FLOOD"), document.hazards)
        assertEquals("https://example.org/fixture-flood-guide.pdf", document.sourceUri)
        assertEquals("pdf", document.sourceType)
        assertEquals(64, document.checksum.length)
        assertEquals(
            listOf("${PublisherFixture.DOCUMENT_ID}-0", "${PublisherFixture.DOCUMENT_ID}-1"),
            document.chunks.map { it.id },
        )
    }

    @Test
    fun manifestReencodesToThePublisherBytes() {
        assertEquals(PublisherFixture.BUNDLE_JSON, BundleJson.encodeToString(BundleManifest.serializer(), manifest))
    }

    @Test
    fun documentFileReencodesToThePublisherBytes() {
        val file = parseDocumentFileBytes(documentBytes)
        assertEquals(PublisherFixture.DOCUMENT_JSON, BundleJson.encodeToString(DocumentFile.serializer(), file))
    }

    @Test
    fun fileChecksumMatchesThePublisherFile() {
        assertEquals(document.fileChecksum, documentFileChecksum(documentBytes))
        verifyDocumentFile(document, documentBytes)
    }

    @Test
    fun anyChangeToTheFileFailsTheFileChecksum() {
        val tampered = (PublisherFixture.DOCUMENT_JSON + " ").encodeToByteArray()
        val error = assertFailsWith<BundleFormatException> { verifyDocumentFile(document, tampered) }
        assertContains(error.message.orEmpty(), "does not match the manifest")
    }

    @Test
    fun documentFileChunksMatchTheManifest() {
        val file = parseDocumentFileBytes(documentBytes)
        assertEquals(document.id, file.id)
        assertEquals(document.chunks.map { it.id }, file.chunks.map { it.id })
        assertEquals(listOf(1, 2), file.chunks.map { it.pageNumber })
        for ((ref, chunk) in document.chunks.zip(file.chunks)) {
            assertEquals(ref.checksum, sha256Hex(chunk.content.encodeToByteArray()), "checksum of ${chunk.id}")
        }
    }

    @Test
    fun unsupportedFormatVersionIsRejectedWithAClearMessage() {
        val version2 = PublisherFixture.BUNDLE_JSON.replace("\"formatVersion\": 1", "\"formatVersion\": 2")
        val error = assertFailsWith<BundleFormatException> { parseBundleManifest(version2) }
        assertContains(error.message.orEmpty(), "Unsupported bundle formatVersion 2")
        assertContains(error.message.orEmpty(), "reads formatVersion 1")
    }

    @Test
    fun missingFormatVersionIsRejected() {
        val missing = PublisherFixture.BUNDLE_JSON.replace("\"formatVersion\": 1,", "")
        val error = assertFailsWith<BundleFormatException> { parseBundleManifest(missing) }
        assertContains(error.message.orEmpty(), "(missing)")
    }

    @Test
    fun blankDocumentIdIsRejected() {
        val blank = PublisherFixture.BUNDLE_JSON.replaceFirst("\"id\": \"${PublisherFixture.DOCUMENT_ID}\"", "\"id\": \" \"")
        val error = assertFailsWith<BundleFormatException> { parseBundleManifest(blank) }
        assertContains(error.message.orEmpty(), "document id must not be blank")
    }

    @Test
    fun blankChunkIdInManifestIsRejected() {
        val blank = PublisherFixture.BUNDLE_JSON.replaceFirst("\"${PublisherFixture.DOCUMENT_ID}-0\"", "\"\"")
        val error = assertFailsWith<BundleFormatException> { parseBundleManifest(blank) }
        assertContains(error.message.orEmpty(), "chunk id")
    }

    @Test
    fun blankIdInDocumentFileIsRejected() {
        val blank = PublisherFixture.DOCUMENT_JSON.replaceFirst("\"${PublisherFixture.DOCUMENT_ID}-1\"", "\"\"")
        val error = assertFailsWith<BundleFormatException> { parseDocumentFile(blank) }
        assertContains(error.message.orEmpty(), "chunk id")
    }

    @Test
    fun malformedJsonIsRejected() {
        assertFailsWith<BundleFormatException> { parseBundleManifest("{") }
        assertFailsWith<BundleFormatException> { parseBundleManifest("[1, 2]") }
        assertFailsWith<BundleFormatException> { parseDocumentFile("{\"id\": \"x\"}") }
    }

    @Test
    fun nullCountryAndPageNumberParse() {
        val text = """
            {
                "formatVersion": 1,
                "bundleVersion": "2026-10-05T12:00:00Z",
                "documents": [
                    {
                        "id": "abc",
                        "title": "Global storm page",
                        "agency": "Agency",
                        "publishedDate": "2025-01-02",
                        "licence": "CC-BY-4.0",
                        "country": null,
                        "hazards": [],
                        "sourceUri": "https://example.org/storm",
                        "sourceType": "html",
                        "checksum": "00",
                        "fileChecksum": "00",
                        "chunks": [{"id": "abc-0", "checksum": "00"}]
                    }
                ]
            }
        """.trimIndent()
        val parsed = parseBundleManifest(text)
        assertNull(parsed.documents.single().country)

        val file = parseDocumentFile("""{"id": "abc", "chunks": [{"id": "abc-0", "content": "Stay indoors.", "pageNumber": null}]}""")
        assertNull(file.chunks.single().pageNumber)
        assertTrue(file.chunks.single().content.contains("Stay indoors."))
    }
}
