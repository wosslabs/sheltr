package com.sheltr.shared.retrieval

import com.sheltr.shared.store.StoreReader
import com.sheltr.shared.store.StoredChunk
import com.sheltr.shared.store.StoredDocument

/**
 * Minimum keyword relevance (the negated FTS5 bm25 score, see [Passage.score]) the best passage must reach for the
 * app to answer. Below it the app refuses.
 *
 * The threshold is on the bm25 scale, which is unbounded and grows with term rarity. 1.0 is chosen on the bm25 scale: FTS5 gives
 * a term found in more than half of the chunks an idf of 1e-6, and a single occurrence of a term found in about a
 * quarter of the chunks scores near 1, so a query that matches only common words is refused while one rare query
 * word is enough to answer. This is a starting value; it must be measured on an evaluation set.
 */
const val KEYWORD_RELEVANCE_THRESHOLD: Double = 1.0

/**
 * Share of the question's topical words that a passage must contain to count as an answer. Without it a single
 * generic word the corpus happens to use ("cover", "change", "world") is enough to answer an off-topic question.
 * Chosen with RetrievalEvaluationTest: at 0.5 two-word off-topic questions with one corpus word ("lose weight")
 * still pass; at 0.6 they are refused and every answerable question of the set is still answered.
 */
const val MIN_TERM_COVERAGE: Double = 0.6

data class RetrievalQuery(
    /** The user's words. FTS5 syntax in it is not interpreted (see StoreReader.search). */
    val text: String,
    /** ISO 3166-1 alpha-2 code from settings, or null. */
    val country: String? = null,
    /** DisasterType names currently active for the user. */
    val activeHazards: Set<String> = emptySet(),
    val limit: Int = 5,
)

data class Passage(
    val chunk: StoredChunk,
    val document: StoredDocument,
    /** Keyword relevance: the FTS5 bm25 score negated, so higher is better. Compared with the threshold. */
    val score: Double,
    /** [score] times the country and hazard factors. Used only for ordering, never for the threshold. */
    val rankScore: Double,
)

sealed interface RetrievalResult {
    /** Passages best first. At least one has a [Passage.score] at or above the threshold. */
    data class Passages(val passages: List<Passage>) : RetrievalResult

    /** No passage reached the threshold. The app shows [MESSAGE] and no guidance. */
    data object Refusal : RetrievalResult {
        /** Text shown when no source passes the relevance check. */
        const val MESSAGE: String =
            "I don't have enough reliable information from trusted sources to answer that question."
    }
}

/**
 * Keyword retrieval: FTS5 ranking from the store, then a multiplicative country and
 * hazard boost that only reorders, then the relevance gate on the unboosted score.
 */
class Retriever(
    private val store: StoreReader,
    private val threshold: Double = KEYWORD_RELEVANCE_THRESHOLD,
) {

    fun retrieve(query: RetrievalQuery): RetrievalResult {
        require(query.limit > 0) { "limit must be positive" }
        val passages = rank(query)
        if (isAnswerable(passages, threshold, query.text)) return RetrievalResult.Passages(passages)
        // Keyword search found nothing good enough: retry once with likely typos fixed, e.g. "eartquake" ->
        // "earthquake". Most queries have no typo to fix; skip the second search when correction is a no-op.
        val corrected = correctTypos(query.text)
        if (corrected == query.text) return RetrievalResult.Refusal
        val correctedPassages = rank(query.copy(text = corrected))
        return if (isAnswerable(correctedPassages, threshold, corrected)) {
            RetrievalResult.Passages(correctedPassages)
        } else {
            RetrievalResult.Refusal
        }
    }

    /** Ranked passages without the relevance gate. */
    fun rank(query: RetrievalQuery): List<Passage> {
        val boosting = query.country != null || query.activeHazards.isNotEmpty()
        // With a boost, rank a larger pool so a boosted passage just below the cut can move up.
        val pool = if (boosting) query.limit * POOL_MULTIPLIER else query.limit
        val documents = mutableMapOf<String, StoredDocument>()
        // Append known synonyms' canonical terms (e.g. "quake" -> also "earthquake") so the store search can match
        // either wording; stopwords are dropped so only topical words are searched.
        val passages = store.search(removeStopwords(expandWithSynonyms(query.text)), pool).map { hit ->
            val document = documents.getOrPut(hit.chunk.documentId) {
                checkNotNull(store.document(hit.chunk.documentId)) { "chunk ${hit.chunk.id} has no document" }
            }
            val score = -hit.score
            val factor = Boost.country(document.country, query.country) *
                Boost.hazard(document.hazards, query.activeHazards)
            Passage(hit.chunk, document, score, score * factor)
        }
        if (!boosting) return passages
        // sortedByDescending is stable: equal boosted scores keep the keyword order.
        return passages.sortedByDescending { it.rankScore }.take(query.limit)
    }

    private companion object {
        /** How much larger the ranked pool is when a boost applies. */
        const val POOL_MULTIPLIER = 5
    }
}

/**
 * True when some passage reaches [threshold] and also contains at least [MIN_TERM_COVERAGE] of the topical words
 * of [questionText]. No passages: false. A question with no topical words is never answerable.
 */
internal fun isAnswerable(passages: List<Passage>, threshold: Double, questionText: String): Boolean {
    val terms = contentTerms(questionText)
    if (terms.isEmpty()) return false
    return passages.any { it.score >= threshold && coverage(terms, it.chunk.content) >= MIN_TERM_COVERAGE }
}

/** A term counts as present when the chunk has a form of it or, for a colloquial synonym, of the canonical term. */
private fun coverage(terms: Set<String>, content: String): Double {
    val present = contentTerms(content)
    val covered = terms.count { term ->
        present.any { sameWord(term, it) } ||
            HazardVocabulary.SYNONYMS[term]?.let { canonical -> present.any { sameWord(canonical, it) } } == true
    }
    return covered.toDouble() / terms.size
}

/**
 * True when [a] and [b] are the same word up to an ending ("aftershocks"/"aftershock", "evacuate"/"evacuation"):
 * they share a prefix of at least 4 letters that covers all but at most 1 letter of the shorter word. The store
 * does no stemming, so exact comparison would refuse questions that merely use another form of a word.
 */
internal fun sameWord(a: String, b: String): Boolean {
    if (a == b) return true
    val shared = a.commonPrefixWith(b).length
    return shared >= 4 && shared >= minOf(a.length, b.length) - 1
}
