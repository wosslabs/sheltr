package com.sheltr.publisher.manifest

import com.sheltr.publisher.DisasterType
import org.yaml.snakeyaml.Yaml
import java.io.InputStream
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.Date
import java.util.Locale

/** The approved corpus, as written in corpus/manifest.yaml. */
data class CorpusManifest(
    val documents: List<Document>,
    val relatedHazards: List<RelatedHazard>,
) {
    data class Document(
        val sourceUri: String,
        val metadata: Metadata,
        val checksum: String,
    ) {
        /** The ISO 3166-1 alpha-2 country, or null for global guidance. */
        val country: String? get() = metadata.country
    }

    data class Metadata(
        val title: String,
        val sourceAgency: String,
        val disasterTypes: Set<DisasterType>,
        val publishedDate: LocalDate,
        val license: String,
        val country: String?,
        val sourceType: SourceType,
    ) {
        init {
            require(title.isNotBlank()) { "title must not be blank" }
            require(sourceAgency.isNotBlank()) { "sourceAgency must not be blank" }
            require(license.isNotBlank()) { "license must not be blank" }
            require(disasterTypes.isNotEmpty()) { "disasterTypes must not be empty" }
        }
    }

    data class RelatedHazard(
        val hazard: DisasterType,
        val relatedHazard: DisasterType,
        val sourceUri: String,
        val note: String?,
    )

    companion object {
        private const val GLOBAL = "GLOBAL"
        private val ISO_COUNTRY_CODES: Set<String> = Locale.getISOCountries().toSet()

        fun parse(input: InputStream): CorpusManifest {
            val loaded: Any? = Yaml().load(input)
            val root = loaded as? Map<*, *>
                ?: throw IllegalArgumentException("Manifest must be a YAML mapping with a 'documents' list")

            val documents = mutableListOf<Document>()
            val uris = mutableSetOf<String>()
            for (entry in mappings(root["documents"], "documents")) {
                val uri = text(entry, "source_uri", "(entry without source_uri)")
                require(uris.add(uri)) { "Duplicate source_uri in manifest: $uri" }
                val sourceType = sourceType(entry["source_type"], uri)
                val metadata = Metadata(
                    title = text(entry, "title", uri),
                    sourceAgency = text(entry, "source_agency", uri),
                    disasterTypes = hazards(entry["hazards"], uri),
                    publishedDate = date(entry["published_date"], uri),
                    license = text(entry, "license", uri),
                    country = country(entry["country"], uri),
                    sourceType = sourceType,
                )
                documents += Document(uri, metadata, text(entry, "checksum", uri))
            }

            val relatedHazards = mutableListOf<RelatedHazard>()
            for (entry in mappings(root["related_hazards"], "related_hazards")) {
                val uri = text(entry, "source_uri", "(related hazard without source_uri)")
                require(uri in uris) {
                    "Related hazard cites $uri, which is not a document in the manifest"
                }
                relatedHazards += RelatedHazard(
                    hazard = hazard(text(entry, "hazard", uri), uri),
                    relatedHazard = hazard(text(entry, "related_hazard", uri), uri),
                    sourceUri = uri,
                    note = entry["note"]?.toString(),
                )
            }
            return CorpusManifest(documents.toList(), relatedHazards.toList())
        }

        private fun mappings(value: Any?, key: String): List<Map<*, *>> {
            if (value == null) return emptyList()
            val list = value as? List<*> ?: throw IllegalArgumentException("'$key' must be a list")
            return list.map { item ->
                item as? Map<*, *> ?: throw IllegalArgumentException("Every item under '$key' must be a mapping")
            }
        }

        private fun text(entry: Map<*, *>, key: String, uri: String): String {
            val value = entry[key]
            if (value == null || value.toString().isBlank()) {
                throw IllegalArgumentException("Manifest entry $uri is missing '$key'")
            }
            return value.toString().trim()
        }

        private fun hazards(value: Any?, uri: String): Set<DisasterType> {
            val list = value as? List<*>
            if (list == null || list.isEmpty()) {
                throw IllegalArgumentException("Manifest entry $uri is missing 'hazards'")
            }
            // Sorted by enum ordinal, like the server's EnumSet.
            return list.map { hazard("$it", uri) }.toSortedSet()
        }

        // Absent means global guidance. The literal GLOBAL also maps to null.
        private fun country(value: Any?, uri: String): String? {
            if (value == null) return null
            if (value !is String) {
                throw IllegalArgumentException(
                    "Manifest entry $uri has country '$value'; quote the code (for example \"NO\") so YAML keeps it as text"
                )
            }
            if (value == GLOBAL) return null
            if (value in ISO_COUNTRY_CODES) return value
            throw IllegalArgumentException(
                "Manifest entry $uri has country '$value', expected an uppercase ISO 3166-1 alpha-2 code or GLOBAL"
            )
        }

        private fun sourceType(value: Any?, uri: String): SourceType = when (value) {
            null, "pdf" -> SourceType.PDF
            "html" -> SourceType.HTML
            else -> throw IllegalArgumentException(
                "Manifest entry $uri has source_type '$value', expected pdf or html"
            )
        }

        private fun hazard(name: String, uri: String): DisasterType =
            DisasterType.entries.firstOrNull { it.name == name.trim() }
                ?: throw IllegalArgumentException("Manifest entry $uri has unknown hazard '$name'")

        // YAML reads an unquoted 2021-03-15 as a date object and a quoted one as text; accept both.
        private fun date(value: Any?, uri: String): LocalDate = when (value) {
            is Date -> value.toInstant().atZone(ZoneOffset.UTC).toLocalDate()
            is String -> try {
                LocalDate.parse(value.trim())
            } catch (e: DateTimeParseException) {
                throw IllegalArgumentException(
                    "Manifest entry $uri has published_date '$value', expected YYYY-MM-DD", e
                )
            }
            else -> throw IllegalArgumentException("Manifest entry $uri is missing 'published_date'")
        }
    }
}
