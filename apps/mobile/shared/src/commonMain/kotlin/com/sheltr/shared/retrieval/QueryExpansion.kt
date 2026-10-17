package com.sheltr.shared.retrieval

/** A word is a maximal run of letters or digits. Everything between words is kept exactly as written. */
private val WORD = Regex("[\\p{L}\\p{N}]+")

/** Shortest word eligible for typo correction; shorter words are too ambiguous to correct safely. */
private const val MIN_TYPO_LENGTH = 4

/**
 * Largest edit distance at which a word is treated as a typo of a hazard term. Kept at 1 on purpose: at 2, real words
 * such as "water" (distance 2 from "winter") would be silently rewritten.
 */
private const val MAX_TYPO_DISTANCE = 1

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

    val distanceByTerm = HazardVocabulary.TERMS.associateWith { levenshtein(lowerCaseWord, it) }
    val minimumDistance = distanceByTerm.values.min()
    val nearestTerms = distanceByTerm.filterValues { it == minimumDistance }.keys
    return if (minimumDistance <= MAX_TYPO_DISTANCE && nearestTerms.size == 1) nearestTerms.single() else word
}
