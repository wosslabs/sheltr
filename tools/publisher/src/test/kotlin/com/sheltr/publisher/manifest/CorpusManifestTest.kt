package com.sheltr.publisher.manifest

import com.sheltr.publisher.DisasterType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.time.LocalDate

class CorpusManifestTest {

    @Test
    fun parsesDocumentsWithBothQuotedAndUnquotedDates() {
        val manifest = parse(VALID)

        assertEquals(2, manifest.documents.size)
        val flood = manifest.documents[0]
        assertEquals("https://example.org/flood.pdf", flood.sourceUri)
        assertEquals("abc123", flood.checksum)
        assertEquals(setOf(DisasterType.FLOOD, DisasterType.LANDSLIDE), flood.metadata.disasterTypes)
        assertEquals(LocalDate.of(2021, 3, 15), flood.metadata.publishedDate)
        assertEquals(LocalDate.of(2019, 11, 2), manifest.documents[1].metadata.publishedDate)
    }

    @Test
    fun parsesRelatedHazards() {
        val related = parse(VALID).relatedHazards[0]

        assertEquals(DisasterType.EARTHQUAKE, related.hazard)
        assertEquals(DisasterType.LANDSLIDE, related.relatedHazard)
        assertEquals("https://example.org/quake.pdf", related.sourceUri)
    }

    @Test
    fun acceptsAnEmptyManifest() {
        val manifest = parse("documents: []\nrelated_hazards: []\n")

        assertTrue(manifest.documents.isEmpty())
        assertTrue(manifest.relatedHazards.isEmpty())
    }

    @Test
    fun rejectsADuplicateSourceUriAndNamesIt() {
        val duplicated = VALID.replace(
            "https://example.org/quake.pdf\n    title",
            "https://example.org/flood.pdf\n    title",
        )

        assertRejects(duplicated, "Duplicate source_uri", "https://example.org/flood.pdf")
    }

    @Test
    fun rejectsAnUnknownHazardAndNamesTheEntry() {
        assertRejects(VALID.replace("[EARTHQUAKE]", "[METEOR]"), "METEOR", "https://example.org/quake.pdf")
    }

    @Test
    fun rejectsAnEntryWithoutALicence() {
        val withoutLicence = VALID.replaceFirst("    license: Example licence, https://example.org/terms\n", "")

        assertRejects(withoutLicence, "license", "https://example.org/flood.pdf")
    }

    @Test
    fun rejectsARelatedHazardWhoseSourceIsNotInTheManifest() {
        val dangling = VALID.replace(
            "    source_uri: https://example.org/quake.pdf\n    note",
            "    source_uri: https://example.org/missing.pdf\n    note",
        )

        assertRejects(dangling, "https://example.org/missing.pdf")
    }

    @Test
    fun parsesACountryCodeAndAnHtmlSourceType() {
        val page = parse(
            VALID.replace(
                "    license: Example licence, https://example.org/terms\n    checksum: abc123",
                "    license: Example licence, https://example.org/terms\n    country: GB\n    source_type: html\n    checksum: abc123",
            ),
        ).documents[0]

        assertEquals("GB", page.country)
        assertEquals("GB", page.metadata.country)
        assertEquals(SourceType.HTML, page.metadata.sourceType)
    }

    @Test
    fun treatsGlobalAsNoCountryAndDefaultsToPdf() {
        val guide = parse(
            VALID.replace("    checksum: abc123", "    country: GLOBAL\n    checksum: abc123"),
        ).documents[0]

        assertNull(guide.country)
        assertNull(guide.metadata.country)
        assertEquals(SourceType.PDF, guide.metadata.sourceType)
    }

    @Test
    fun entriesWithoutCountryOrSourceTypeStillParse() {
        val flood = parse(VALID).documents[0]

        assertNull(flood.country)
        assertEquals(SourceType.PDF, flood.metadata.sourceType)
    }

    @Test
    fun rejectsAMalformedOrUnknownCountryAndNamesTheEntry() {
        for (country in listOf("gbr", "1A", "gb", "XX")) {
            val bad = VALID.replace("    checksum: abc123", "    country: $country\n    checksum: abc123")

            assertRejects(bad, country, "https://example.org/flood.pdf")
        }
    }

    @Test
    fun rejectsAnUnquotedNorwayCodeWhichYamlReadsAsBoolean() {
        val bad = VALID.replace("    checksum: abc123", "    country: NO\n    checksum: abc123")

        assertRejects(bad, "https://example.org/flood.pdf")
    }

    @Test
    fun rejectsAnUnsupportedSourceTypeAndNamesTheEntry() {
        for (sourceType in listOf("docx", "HTML", "txt")) {
            val bad = VALID.replace("    checksum: abc123", "    source_type: $sourceType\n    checksum: abc123")

            assertRejects(bad, sourceType, "https://example.org/flood.pdf")
        }
    }

    private fun parse(yaml: String): CorpusManifest =
        CorpusManifest.parse(yaml.toByteArray(Charsets.UTF_8).inputStream())

    private fun assertRejects(yaml: String, vararg messageParts: String) {
        val error = assertFailsWith<IllegalArgumentException> { parse(yaml) }
        for (part in messageParts) {
            assertTrue(
                error.message.orEmpty().contains(part),
                "expected message to contain '$part' but was '${error.message}'",
            )
        }
    }

    private companion object {
        val VALID = """
            documents:
              - source_uri: https://example.org/flood.pdf
                title: Flood Guide
                source_agency: Example Agency
                hazards: [FLOOD, LANDSLIDE]
                published_date: 2021-03-15
                license: Example licence, https://example.org/terms
                checksum: abc123
              - source_uri: https://example.org/quake.pdf
                title: Earthquake Guide
                source_agency: Example Agency
                hazards: [EARTHQUAKE]
                published_date: "2019-11-02"
                license: Example licence, https://example.org/terms
                checksum: def456
            related_hazards:
              - hazard: EARTHQUAKE
                related_hazard: LANDSLIDE
                source_uri: https://example.org/quake.pdf
                note: Stated on page 4
        """.trimIndent() + "\n"
    }
}
