package com.sheltr.android.ui

import com.sheltr.shared.retrieval.RetrievalResult
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Carries a [RetrievalResult] from Search to Answer across a single `NavController.navigate` call.
 *
 * Why this exists: Compose Navigation arguments are Bundle-backed (string/primitive/Parcelable);
 * [RetrievalResult] wraps shared-module model types (`Passage`, `StoredChunk`, `StoredDocument`) that aren't
 * Parcelable and have no reason to become so just to cross a navigation boundary internal to this one app.
 * Making them Parcelable would leak an Android framework concern into the shared module's domain types
 * (CLAUDE.md's "framework-specific annotations should remain outside the domain model").
 *
 * Mechanism: [put] stores a result under a freshly generated id and returns that id; the caller
 * (`SearchViewModel`'s `onSubmit` callback, wired up in [SheltrNavHost]) passes that id as `Route.Answer`'s
 * `resultId` nav argument. [take] removes and returns the entry for a given id -- a one-time read, not a
 * cache -- so that rapid re-submission from Search never shows a stale result: each submit calls [put] again
 * and gets a fresh id, and an id left over from an earlier submit the user never navigated away from (e.g.
 * they backed out of Search before Answer ever composed) is simply an orphaned entry, never confused with a
 * newer one.
 *
 * **Durability -- this is the part that is easy to get wrong, and an earlier version of this doc comment did:**
 * this holder is process memory only and does *not* survive process death. That is not the same as saying
 * "Answer is never reached after process death" -- it is reached, and with the *same* `resultId`. Navigation
 * Compose's own back stack (the sequence of route strings and their arguments, via `NavController`'s
 * integration with `SavedStateRegistry`) *is* restored across process death; that is the exact mechanism that
 * lets Android recreate an app's UI after it reclaims a backgrounded process. A disaster-prep user switching to
 * Maps or a phone call mid-search, then returning, is an ordinary case this must survive without crashing or
 * silently mis-answering (plan's own Review Focus: "Rotating the device or backgrounding the app mid-search...
 * must not lose the in-flight query or crash").
 *
 * So: after a process restart, Answer's composable *will* run again with the *same* `resultId` -- but
 * [take] on that id now runs for the first time in a fresh, empty map, and returns `null`. **A `null` from
 * [take] is therefore not evidence of "no answer was found" (that is what [RetrievalResult.Refusal] means) --
 * it only ever means "this process never had this id," which happens both for an unknown/already-consumed id
 * *and* for a perfectly valid resubmission-worthy one after a process restart.** `Route.Answer` carries
 * `text`/`hazard` (Bundle-safe strings, unlike [RetrievalResult] itself) alongside `resultId` for exactly this
 * reason: the only way to tell these two `null` cases apart, and to recover correctly from the second one, is
 * for the caller to re-run the deterministic local retrieval with the same inputs rather than show a generic
 * failure. **Contract for `AnswerViewModel`:** on `take(resultId) == null`, re-run
 * `retriever.retrieve(RetrievalQuery(text, settingsStore.get().country, hazard?.let { setOf(it) } ?:
 * emptySet()))` (re-reading `country` fresh, not carried in the route) instead of treating it as a refusal --
 * retrieval is a deterministic local FTS5 query over a corpus that does not change between submit and
 * restore, so the re-run is an equally valid result, not a stale one.
 *
 * Thread safety: [SearchViewModel][com.sheltr.android.ui.search.SearchViewModel]'s `onSubmit` calls [put] only
 * after hopping back onto the main thread following its `Dispatchers.IO` retrieval, so in practice this is
 * always touched from the main thread -- but a [ConcurrentHashMap] costs nothing here and removes any doubt,
 * including for the `take` call.
 */
object RetrievalResultHolder {
    private val results = ConcurrentHashMap<String, RetrievalResult>()

    /** Stores [result] under a freshly generated id and returns that id. */
    fun put(result: RetrievalResult): String {
        val id = UUID.randomUUID().toString()
        results[id] = result
        return id
    }

    /** Removes and returns the result stored under [id], or null if none exists (already taken, or unknown). */
    fun take(id: String): RetrievalResult? = results.remove(id)
}
