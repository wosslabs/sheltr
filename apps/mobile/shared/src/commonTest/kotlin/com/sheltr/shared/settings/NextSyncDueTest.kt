package com.sheltr.shared.settings

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NextSyncDueTest {

    private val now = 10_000_000L

    private fun settings(frequency: SyncFrequency, allowMobileData: Boolean = false) =
        AppSettings(syncFrequency = frequency, allowMobileData = allowMobileData)

    @Test
    fun manualNeverAutoSyncs() {
        val manual = settings(SyncFrequency.MANUAL, allowMobileData = true)
        assertFalse(nextSyncDue(manual, lastSyncEpochSeconds = null, nowEpochSeconds = now, onUnmeteredNetwork = true))
        assertFalse(nextSyncDue(manual, lastSyncEpochSeconds = 0L, nowEpochSeconds = now, onUnmeteredNetwork = true))
    }

    @Test
    fun neverSyncedAndUnmeteredIsDue() {
        assertTrue(nextSyncDue(settings(SyncFrequency.WEEKLY), null, now, onUnmeteredNetwork = true))
    }

    @Test
    fun neverSyncedAndMeteredIsNotDueUnlessMobileDataAllowed() {
        assertFalse(nextSyncDue(settings(SyncFrequency.WEEKLY), null, now, onUnmeteredNetwork = false))
        assertTrue(nextSyncDue(settings(SyncFrequency.WEEKLY, allowMobileData = true), null, now, onUnmeteredNetwork = false))
    }

    @Test
    fun meteredNetworkBlocksSyncEvenWhenIntervalHasPassed() {
        val last = now - 10L * 604_800L
        assertFalse(nextSyncDue(settings(SyncFrequency.WEEKLY), last, now, onUnmeteredNetwork = false))
    }

    @Test
    fun dueExactlyAtTheIntervalNotOneSecondBefore() {
        val daily = settings(SyncFrequency.DAILY)
        val last = now - 86_400L
        assertTrue(nextSyncDue(daily, last, now, onUnmeteredNetwork = true))
        assertFalse(nextSyncDue(daily, last + 1, now, onUnmeteredNetwork = true))
    }

    @Test
    fun weeklyIntervalBoundary() {
        val weekly = settings(SyncFrequency.WEEKLY)
        assertTrue(nextSyncDue(weekly, now - 604_800L, now, onUnmeteredNetwork = true))
        assertFalse(nextSyncDue(weekly, now - 604_799L, now, onUnmeteredNetwork = true))
    }

    @Test
    fun monthlyIntervalUses30Days() {
        val monthly = settings(SyncFrequency.MONTHLY)
        val thirtyDays = 30L * 86_400L
        assertTrue(nextSyncDue(monthly, now - thirtyDays, now, onUnmeteredNetwork = true))
        assertFalse(nextSyncDue(monthly, now - thirtyDays + 1, now, onUnmeteredNetwork = true))
    }

    @Test
    fun lastSyncInTheFutureIsNotDue() {
        assertFalse(nextSyncDue(settings(SyncFrequency.DAILY), now + 500, now, onUnmeteredNetwork = true))
    }
}
