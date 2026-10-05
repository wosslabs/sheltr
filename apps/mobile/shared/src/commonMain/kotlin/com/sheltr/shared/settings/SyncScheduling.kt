package com.sheltr.shared.settings

/**
 * Whether an automatic sync should start now. Pure: the platform scheduler (A2, I2) supplies the clock and the
 * network state and does the actual work.
 *
 * - Manual sync is never automatic.
 * - A sync is allowed only on an unmetered network, unless [AppSettings.allowMobileData] is true.
 * - If the app has never synced, a sync is due as soon as one is allowed.
 * - Otherwise it is due once [nowEpochSeconds] is at least the interval after [lastSyncEpochSeconds].
 */
fun nextSyncDue(
    settings: AppSettings,
    lastSyncEpochSeconds: Long?,
    nowEpochSeconds: Long,
    onUnmeteredNetwork: Boolean,
): Boolean {
    val interval = settings.syncFrequency.intervalSeconds ?: return false
    if (!onUnmeteredNetwork && !settings.allowMobileData) return false
    if (lastSyncEpochSeconds == null) return true
    return nowEpochSeconds - lastSyncEpochSeconds >= interval
}
