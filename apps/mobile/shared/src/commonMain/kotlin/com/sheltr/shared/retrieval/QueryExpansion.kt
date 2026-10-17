package com.sheltr.shared.retrieval

/** A word is a maximal run of letters or digits. Everything between words is kept exactly as written. */
private val WORD = Regex("[\\p{L}\\p{N}]+")

/**
 * Shortest word eligible for typo correction, and shortest TERMS entry eligible as a correction target. Below this,
 * ordinary English words land exactly one edit away from a short, generic TERMS entry too often to correct safely —
 * e.g. "food"/"head"/"like" are each distance 1 from "flood"/"heat"/"lake". 7 is the smallest length at which no
 * TERMS entry still collides this way (the one exception, "drought" vs "brought", is excluded separately below).
 */
private const val MIN_TYPO_LENGTH = 7

/**
 * Largest edit distance at which a word is treated as a typo of a hazard term. Kept at 1 on purpose: at 2, real words
 * such as "water" (distance 2 from "winter") would be silently rewritten.
 */
private const val MAX_TYPO_DISTANCE = 1

/**
 * Valid correction targets: [HazardVocabulary.TERMS] entries long enough to satisfy [MIN_TYPO_LENGTH] as a target,
 * minus "drought" specifically, which is otherwise distance 1 from the common word "brought". TERMS itself is left
 * untouched since other code checks membership in the full vocabulary.
 */
private val CORRECTION_TARGETS: Set<String> =
    HazardVocabulary.TERMS.filterTo(mutableSetOf()) { it.length >= MIN_TYPO_LENGTH && it != "drought" }

/**
 * Appends the canonical hazard term for each colloquial synonym in [text], so "quake" also searches "earthquake".
 * The original text is kept as written. A canonical term already present in [text] is not appended again.
 */
fun expandWithSynonyms(text: String): String {
    val presentWords = WORD.findAll(text).map { it.value.lowercase() }.toSet()
    val expansions = LinkedHashSet<String>()
    for (match in WORD.findAll(text)) {
        val canonicalTerm = HazardVocabulary.SYNONYMS[match.value.lowercase()] ?: continue
        if (canonicalTerm !in presentWords) expansions += canonicalTerm
    }
    return if (expansions.isEmpty()) text else "$text ${expansions.joinToString(" ")}"
}

/**
 * Replaces each word that is a single edit away from exactly one hazard term with that term, so "eartquake" searches
 * "earthquake". Words that are already vocabulary terms or synonyms, shorter than [MIN_TYPO_LENGTH], or ambiguous or
 * unrelated to every term are left unchanged.
 */
fun correctTypos(text: String): String =
    WORD.replace(text) { match -> correctWord(match.value) }

private fun correctWord(word: String): String {
    val lowerCaseWord = word.lowercase()
    if (word.length < MIN_TYPO_LENGTH) return word
    if (lowerCaseWord in HazardVocabulary.TERMS || lowerCaseWord in HazardVocabulary.SYNONYMS) return word

    val distanceByTerm = CORRECTION_TARGETS.associateWith { levenshtein(lowerCaseWord, it) }
    if (distanceByTerm.isEmpty()) return word
    val minimumDistance = distanceByTerm.values.min()
    val nearestTerms = distanceByTerm.filterValues { it == minimumDistance }.keys
    // Ambiguous tie: unreachable with the real vocabulary today (no two CORRECTION_TARGETS entries are within edit
    // distance 2 of each other), kept so a future vocabulary addition fails safe instead of picking arbitrarily.
    return if (minimumDistance <= MAX_TYPO_DISTANCE && nearestTerms.size == 1) nearestTerms.single() else word
}
