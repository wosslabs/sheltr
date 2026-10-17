# Retrieval Robustness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `apps/mobile/shared`'s keyword `Retriever` tolerant of colloquial hazard terms ("quake", "twister") and typed typos, without adding an embedding/semantic model.

**Architecture:** Two small pure-Kotlin helpers (a fixed hazard-term vocabulary + synonym map, and a bounded Levenshtein-based corrector) sit in front of the existing FTS5 `StoreReader.search`. Synonym expansion runs on every `rank()` call (safe: a no-op unless the query contains a known synonym). Typo correction runs only as a fallback inside `retrieve()`, when the first attempt is `Refusal`, so normal queries pay no extra cost and existing ranking tests are unaffected.

**Tech Stack:** Kotlin Multiplatform (commonMain/commonTest), no new dependencies.

**Spec:** This plan has no separate design doc; the decisions below are the spec (confirmed in conversation: default to prefix/synonym/typo-tolerant keyword matching, not a semantic model — LiteRT-LM stays a separate future effort per README's "Not done" list).

## Global Constraints

- No new third-party dependency.
- Do not change `RetrievalQuery`, `RetrievalResult`, `Passage`, or `KEYWORD_RELEVANCE_THRESHOLD` — only `Retriever`'s internals.
- `rank()` must return byte-identical results to today for any query with no synonym-table hit (existing `RetrieverTest`/`RetrieverStoreTest` cases use "water", which has none — they must keep passing unmodified).
- All new code lives under `com.sheltr.shared.retrieval`.

## Review Focus

- A query that is only a correctly-spelled common word (e.g. "water") must not be altered by synonym expansion or typo correction — both tables are keyed on hazard vocabulary only.
- Two vocabulary terms equally close (same edit distance) to a misspelled word must not be corrected (ambiguous correction is worse than no correction) — leave the original word.
- A one-word query that exactly matches a vocabulary word already must not be "corrected" to a different word (zero edit distance short-circuits).
- Synonym expansion must not duplicate the FTS5 query if the canonical term is already present in the text (e.g. "earthquake quake" should not search "earthquake quake earthquake").
- `retrieve()`'s typo-correction fallback must only fire when the first `rank()` result is actually unanswerable, and must not run at all when `correctTypos` returns the input unchanged (no wasted second `store.search`).

---

### Task 1: Hazard vocabulary and edit distance

**Files:**
- Create: `apps/mobile/shared/src/commonMain/kotlin/com/sheltr/shared/retrieval/HazardVocabulary.kt`
- Create: `apps/mobile/shared/src/commonMain/kotlin/com/sheltr/shared/retrieval/EditDistance.kt`
- Test: `apps/mobile/shared/src/commonTest/kotlin/com/sheltr/shared/retrieval/HazardVocabularyTest.kt`
- Test: `apps/mobile/shared/src/commonTest/kotlin/com/sheltr/shared/retrieval/EditDistanceTest.kt`

**Interfaces:**
- Produces: `object HazardVocabulary { val TERMS: Set<String>; val SYNONYMS: Map<String, String> }` — `TERMS` and the values of `SYNONYMS` are lower-case single words; `SYNONYMS` keys are lower-case colloquial words.
- Produces: `fun levenshtein(a: String, b: String): Int` (top-level function in `EditDistance.kt`).

- [ ] **Step 1: Write failing tests for `levenshtein`**

```kotlin
@Test fun identicalStringsHaveDistanceZero() = assertEquals(0, levenshtein("earthquake", "earthquake"))
@Test fun classicKittenSitting() = assertEquals(3, levenshtein("kitten", "sitting"))
@Test fun emptyStringDistanceIsLength() = assertEquals(3, levenshtein("", "abc"))
@Test fun singleSubstitution() = assertEquals(1, levenshtein("eartquake", "earthquake"))
```

- [ ] **Step 2: Run `gradle :apps:mobile:shared:jvmTest --tests "*.EditDistanceTest"` — expect FAIL (unresolved reference)**

- [ ] **Step 3: Implement `levenshtein` in `EditDistance.kt`**

Standard dynamic-programming edit distance (insert/delete/substitute, cost 1 each) over a single `IntArray` row, length `b.length + 1`. No library call exists for this on commonMain; write the DP loop.

- [ ] **Step 4: Run the test — expect PASS**

- [ ] **Step 5: Write failing tests for `HazardVocabulary`**

```kotlin
@Test fun termsCoverEveryDisasterTypeWord() {
    // DisasterType names from tools/publisher/src/main/kotlin/com/sheltr/publisher/DisasterType.kt,
    // lower-cased and split on '_': earthquake, flood, tropical, cyclone, tornado, tsunami, wildfire,
    // extreme, heat, winter, storm, landslide, glacial, lake, volcano, drought.
    assertTrue(HazardVocabulary.TERMS.containsAll(listOf("earthquake", "tornado", "tsunami", "wildfire", "drought")))
}
@Test fun synonymsMapToATerm() {
    assertEquals("earthquake", HazardVocabulary.SYNONYMS["quake"])
    assertEquals("tornado", HazardVocabulary.SYNONYMS["twister"])
    assertTrue(HazardVocabulary.SYNONYMS["hurricane"] in HazardVocabulary.TERMS)
    assertTrue(HazardVocabulary.SYNONYMS["typhoon"] in HazardVocabulary.TERMS)
}
```

- [ ] **Step 6: Run — expect FAIL**

- [ ] **Step 7: Implement `HazardVocabulary`**

`TERMS`: the lower-case word-split of every `DisasterType` entry name (`EARTHQUAKE`, `FLOOD`, `TROPICAL_CYCLONE`, `TORNADO`, `TSUNAMI`, `WILDFIRE`, `EXTREME_HEAT`, `WINTER_STORM`, `LANDSLIDE`, `GLACIAL_LAKE_FLOOD`, `VOLCANO`, `DROUGHT`). `SYNONYMS`: at minimum `quake`→`earthquake`, `twister`→`tornado`, `hurricane`→`cyclone`, `typhoon`→`cyclone`, `bushfire`→`wildfire`, `forestfire`→`wildfire`, `firestorm`→`wildfire`, `blizzard`→`storm`, `mudslide`→`landslide`, `dryspell`→`drought`. (This module does not import `tools/publisher`'s enum — it is a JVM-only module; copy the words as a literal list, with a comment pointing at `DisasterType.kt` as the source of truth.)

- [ ] **Step 8: Run both test files — expect PASS**

- [ ] **Step 9: Commit**

```bash
git add apps/mobile/shared/src/commonMain/kotlin/com/sheltr/shared/retrieval/HazardVocabulary.kt \
        apps/mobile/shared/src/commonMain/kotlin/com/sheltr/shared/retrieval/EditDistance.kt \
        apps/mobile/shared/src/commonTest/kotlin/com/sheltr/shared/retrieval/HazardVocabularyTest.kt \
        apps/mobile/shared/src/commonTest/kotlin/com/sheltr/shared/retrieval/EditDistanceTest.kt
git commit -m "feat(mobile): add hazard vocabulary and edit distance for fuzzy retrieval"
```

---

### Task 2: Query expansion (synonyms + typo correction)

**Files:**
- Create: `apps/mobile/shared/src/commonMain/kotlin/com/sheltr/shared/retrieval/QueryExpansion.kt`
- Test: `apps/mobile/shared/src/commonTest/kotlin/com/sheltr/shared/retrieval/QueryExpansionTest.kt`

**Interfaces:**
- Consumes: `HazardVocabulary.TERMS`, `HazardVocabulary.SYNONYMS`, `levenshtein` (Task 1).
- Produces: `fun expandWithSynonyms(text: String): String`, `fun correctTypos(text: String): String` (both top-level in `QueryExpansion.kt`).

- [ ] **Step 1: Write failing tests**

```kotlin
@Test fun expandAppendsCanonicalTermForSynonym() =
    assertEquals("quake near the river earthquake", expandWithSynonyms("quake near the river"))
@Test fun expandIsNoOpWithoutASynonym() =
    assertEquals("water near the river", expandWithSynonyms("water near the river"))
@Test fun expandDoesNotDuplicateAnAlreadyPresentCanonicalTerm() =
    assertEquals("earthquake quake", expandWithSynonyms("earthquake quake"))

@Test fun correctTyposFixesASingleTypo() =
    assertEquals("earthquake shelter", correctTypos("eartquake shelter"))
@Test fun correctTyposLeavesCorrectlySpelledWordsAlone() =
    assertEquals("water shelter", correctTypos("water shelter"))
@Test fun correctTyposLeavesExactVocabularyWordsAlone() =
    assertEquals("earthquake", correctTypos("earthquake"))
@Test fun correctTyposLeavesAmbiguousCorrectionsAlone() {
    // "fiod" is distance 1 from "flood" only if no other vocabulary word ties; pick two real
    // vocabulary words at equal distance from a crafted misspelling and assert the input is unchanged.
    // Concrete case: construct a 4-letter word equidistant (distance 2) from two TERMS entries, e.g.
    // "xxrm" is distance 2 from both "storm" (len 5) and "worm"-like... implementer: pick an actual
    // equidistant pair from HazardVocabulary.TERMS and assert correctTypos leaves it unchanged.
}
```

(Resolve the exact crafted string for the ambiguous-correction test against the real `HazardVocabulary.TERMS` set once Task 1 is in — pick any word within the module's vocabulary that is equidistant from two terms; if none exists within edit distance 2, assert instead that a word with no vocabulary term within distance 2 is left unchanged, e.g. `correctTyposLeavesUnrelatedMisspellingsAlone` for a word like "zzzzz".)

- [ ] **Step 2: Run — expect FAIL**

- [ ] **Step 3: Implement `expandWithSynonyms` and `correctTypos`**

Tokenize the same way `ftsQueryFor` in `StoreReader.kt` does (split on non-letter-or-digit, case-insensitive compare against the lower-case vocabulary, but preserve the original text's words and casing in the output — only *append* for synonyms, and only *replace* the matched token in place for typo correction).

`expandWithSynonyms`: for each word in `text`, if its lowercase form is a key in `SYNONYMS` and the mapped canonical term is not already a word in `text` (case-insensitive), append the canonical term once at the end (collect all expansions, append space-joined, each canonical term appended at most once even if two different synonym words map to the same term).

`correctTypos`: for each word of length ≥ 4 whose lowercase form is not already in `TERMS` or a key of `SYNONYMS`, compute `levenshtein` against every entry in `TERMS`; if exactly one term has the minimum distance and that minimum is ≤ 2, replace the word with that term (preserve the word's original case pattern is not required — tests above use lower-case fixtures); otherwise leave the word unchanged.

- [ ] **Step 4: Run — expect PASS**

- [ ] **Step 5: Commit**

```bash
git add apps/mobile/shared/src/commonMain/kotlin/com/sheltr/shared/retrieval/QueryExpansion.kt \
        apps/mobile/shared/src/commonTest/kotlin/com/sheltr/shared/retrieval/QueryExpansionTest.kt
git commit -m "feat(mobile): add synonym expansion and typo correction for retrieval queries"
```

---

### Task 3: Wire expansion and correction into `Retriever`

**Files:**
- Modify: `apps/mobile/shared/src/commonMain/kotlin/com/sheltr/shared/retrieval/Retriever.kt`
- Modify (add cases): `apps/mobile/shared/src/commonTest/kotlin/com/sheltr/shared/retrieval/RetrieverTest.kt`

**Interfaces:**
- Consumes: `expandWithSynonyms`, `correctTypos` (Task 2).
- Produces: no change to `Retriever`'s public signature (`retrieve(RetrievalQuery): RetrievalResult`, `rank(RetrievalQuery): List<Passage>`).

- [ ] **Step 1: Write a failing test for the typo-correction fallback**

Add to `RetrieverTest.kt` a `FakeStore` variant (or extend the existing one with a `respondTo: Map<String, List<SearchHit>>` so `search(query, limit)` returns `respondTo[query].orEmpty()` instead of the fixed `hits` list) and a test:

```kotlin
@Test fun typoInQueryIsCorrectedWhenFirstSearchRefuses() {
    val store = FakeStore(
        documents = listOf(document("d", country = null, hazards = listOf("EARTHQUAKE"))),
        respondTo = mapOf(
            "eartquake shelter" to emptyList(),
            "earthquake shelter" to listOf(hit("a", "d", -5.0)), // well above threshold once negated
        ),
    )
    val result = Retriever(store).retrieve(RetrievalQuery("eartquake shelter"))
    assertIs<RetrievalResult.Passages>(result)
    assertEquals("a", result.passages.first().chunk.id)
}

@Test fun noRetryWhenCorrectionDoesNotChangeTheQuery() {
    // "water" has no vocabulary term within distance 2, so correctTypos("water") == "water": the
    // fallback must not call store.search a second time with the same text. Use a FakeStore whose
    // respondTo map has no entry for "water" and assert Refusal (proves it did not loop or throw).
    val store = FakeStore(documents = emptyList(), respondTo = emptyMap())
    assertEquals(RetrievalResult.Refusal, Retriever(store).retrieve(RetrievalQuery("water")))
}
```

- [ ] **Step 2: Run `gradle :apps:mobile:shared:jvmTest --tests "*.RetrieverTest"` — expect FAIL**

- [ ] **Step 3: Modify `Retriever`**

In `rank()`, change the search call from `store.search(query.text, pool)` to `store.search(expandWithSynonyms(query.text), pool)`.

In `retrieve()`, after the existing `isAnswerable` check fails, compute `val corrected = correctTypos(query.text)`; if `corrected != query.text`, call `rank(query.copy(text = corrected))` and re-check `isAnswerable`; return `Passages` if it now passes, else `Refusal`. If `corrected == query.text`, return `Refusal` immediately (no second call).

- [ ] **Step 4: Run the full shared-module test suite — expect PASS**

Run: `gradle :apps:mobile:shared:jvmTest`
Expected: all tests pass, including the pre-existing `RetrieverTest`/`RetrieverStoreTest` cases (unaffected, since "water" has no synonym and `rank`'s output order logic is untouched).

- [ ] **Step 5: Commit**

```bash
git add apps/mobile/shared/src/commonMain/kotlin/com/sheltr/shared/retrieval/Retriever.kt \
        apps/mobile/shared/src/commonTest/kotlin/com/sheltr/shared/retrieval/RetrieverTest.kt
git commit -m "feat(mobile): fall back to typo-corrected query when keyword search refuses"
```
