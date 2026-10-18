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
 * cache -- so that:
 *   - Rapid re-submission from Search never shows a stale result: each submit calls [put] again and gets a
 *     fresh id; an id left over from an earlier submit the user never navigated away from (e.g. they backed out
 *     of Search before Answer ever composed) is simply an orphaned entry, never confused with a newer one.
 *   - Task 4's `AnswerViewModel` is expected to call [take] exactly once (e.g. from its `init`) and hold the
 *     result in its own state from then on; a configuration-change recomposition re-reads the ViewModel's own
 *     held state (which survives rotation), not this holder, so a second [take] of the same id correctly
 *     returns null rather than silently re-serving data an earlier caller already consumed.
 *
 * Durability: process memory only, like the in-flight navigation stack itself. It does not survive process
 * death -- if the process dies after Search submits but before Answer reads, Android does not replay the
 * in-flight navigate() call on restart, so there is nothing to read back anyway; the user lands back on a
 * cold-started Home/Search and simply searches again. This is the same level of durability Task 1 already
 * accepted for `MainActivity`'s own in-process settings state ("no reactive settings Flow needed" simplicity
 * level) and is appropriate here too: a navigation payload is not business state (per CLAUDE.md's "do not
 * treat conversation history as authoritative business state" -- the same reasoning extends to this transient,
 * non-authoritative handoff, which Answer can always reproduce by re-querying if it were ever lost).
 *
 * Thread safety: [SearchViewModel][com.sheltr.android.ui.search.SearchViewModel]'s `onSubmit` calls [put] only
 * after hopping back onto the main thread following its `Dispatchers.IO` retrieval, so in practice this is
 * always touched from the main thread -- but a [ConcurrentHashMap] costs nothing here and removes any doubt,
 * including for Task 4's `take` call.
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
