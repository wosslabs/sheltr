package com.sheltr.shared.retrieval

/**
 * Hazard words the keyword retriever recognises, and colloquial synonyms that map onto them. Lets a query such as
 * "quake safety" match documents about earthquakes.
 *
 * The phone module is JVM-only and cannot import the publisher's enum, so [TERMS] is copied by hand. The source of
 * truth is the `DisasterType` enum in tools/publisher/src/main/kotlin/com/sheltr/publisher/DisasterType.kt; each
 * entry name is lower-cased and split on '_'.
 */
object HazardVocabulary {

    val TERMS: Set<String> = setOf(
        "earthquake", "flood", "tropical", "cyclone", "tornado", "tsunami", "wildfire", "extreme", "heat", "winter",
        "storm", "landslide", "glacial", "lake", "volcano", "drought",
    )

    /** Colloquial or regional word to the hazard term it stands for. Every value is a member of [TERMS]. */
    val SYNONYMS: Map<String, String> = mapOf(
        "quake" to "earthquake",
        "twister" to "tornado",
        "hurricane" to "cyclone",
        "typhoon" to "cyclone",
        "bushfire" to "wildfire",
        "forestfire" to "wildfire",
        "firestorm" to "wildfire",
        "blizzard" to "storm",
        "mudslide" to "landslide",
        "dryspell" to "drought",
    )
}
