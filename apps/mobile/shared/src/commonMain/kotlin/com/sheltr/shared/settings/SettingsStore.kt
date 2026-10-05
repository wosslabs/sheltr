package com.sheltr.shared.settings

/**
 * Reads and writes [AppSettings]. Platform implementations (Android DataStore, iOS UserDefaults) come with
 * Phase A and Phase I; this module only defines the contract and [InMemorySettingsStore].
 */
interface SettingsStore {
    /** Returns the stored settings, or [AppSettings] defaults when nothing has been stored. */
    fun get(): AppSettings

    fun put(settings: AppSettings)
}

/** Settings held in memory. For tests and previews; nothing survives a restart. */
class InMemorySettingsStore(initial: AppSettings = AppSettings()) : SettingsStore {
    private var current: AppSettings = initial

    override fun get(): AppSettings = current

    override fun put(settings: AppSettings) {
        current = settings
    }
}
