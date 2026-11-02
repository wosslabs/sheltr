package com.sheltr.shared.retrieval

private val WORD = Regex("[\\p{L}\\p{N}]+")

/**
 * English function and question words. They carry no topic, but appear in many chunks, so searching them lets an
 * off-topic question ("how do I file my taxes") collect enough keyword score to pass the relevance gate. The corpus
 * is English (see TextToSpeechController); revisit this list if non-English documents are added.
 */
private val STOPWORDS: Set<String> = setOf(
    "a", "about", "after", "again", "all", "am", "an", "and", "any", "are", "as", "at", "be", "because", "been",
    "before", "being", "but", "by", "can", "could", "did", "do", "does", "doing", "done", "during", "for", "from",
    "get", "got", "had", "has", "have", "having", "he", "her", "here", "him", "his", "how", "i", "if", "in", "into",
    "is", "it", "its", "just", "keep", "know", "let", "make", "me", "more", "most", "my", "need", "no", "nor", "not", "now", "of", "off", "on",
    "once", "only", "or", "other", "our", "out", "over", "own", "same", "she", "should", "so", "some", "such",
    "take", "than", "that", "the", "their", "them", "then", "there", "these", "they", "this", "those", "through", "to",
    "too", "under", "until", "up", "use", "very", "was", "we", "were", "what", "when", "where", "which", "while", "who",
    "want", "whom", "why", "will", "with", "would", "you", "your",
)

/**
 * Removes [STOPWORDS] from [text], keeping the remaining words in order, separated by single spaces. Returns an
 * empty string when nothing is left, which the store treats as a query with no words.
 */
fun removeStopwords(text: String): String =
    WORD.findAll(text).map { it.value }.filter { it.lowercase() !in STOPWORDS }.joinToString(" ")

/** Distinct lower-case topical words of [text]: its words without [STOPWORDS]. */
internal fun contentTerms(text: String): Set<String> =
    WORD.findAll(text).map { it.value.lowercase() }.filterTo(linkedSetOf()) { it !in STOPWORDS }
