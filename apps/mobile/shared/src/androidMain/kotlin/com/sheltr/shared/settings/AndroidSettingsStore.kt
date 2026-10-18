package com.sheltr.shared.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * [SettingsStore] backed by AndroidX DataStore (Preferences). One [Preferences.Key][androidx.datastore.preferences.core.Preferences.Key]
 * per [AppSettings] field; a key missing from the preferences file (first run, or a file written before a field
 * such as [AppSettings.highContrast] existed) falls back to that field's [AppSettings] default rather than
 * failing, so partially-written preference files stay valid across app updates that add fields. A preferences
 * file DataStore cannot even parse (on-disk corruption) is handled the same way: a `corruptionHandler` replaces
 * it with an empty one instead of letting [get] throw, so this store keeps its "always returns valid settings"
 * guarantee even then.
 *
 * [SettingsStore] is a synchronous port (see its doc comment); DataStore's API is suspend-based, so [get] and
 * [put] block with [runBlocking] to bridge the two. [context] is narrowed to [Context.getApplicationContext] on
 * construction, this class's own defensive choice to avoid leaking an `Activity` into the stored [dataStore]'s
 * closure.
 */
class AndroidSettingsStore(context: Context) : SettingsStore {
    private val appContext = context.applicationContext
    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        produceFile = { appContext.preferencesDataStoreFile(PREFERENCES_FILE_NAME) },
    )

    override fun get(): AppSettings = runBlocking {
        val preferences = dataStore.data.first()
        val defaults = AppSettings()
        AppSettings(
            syncFrequency = preferences[Keys.syncFrequency]?.let { stored ->
                runCatching { SyncFrequency.valueOf(stored) }.getOrDefault(defaults.syncFrequency)
            } ?: defaults.syncFrequency,
            allowMobileData = preferences[Keys.allowMobileData] ?: defaults.allowMobileData,
            country = preferences[Keys.country] ?: defaults.country,
            textScale = preferences[Keys.textScale]?.let { stored ->
                runCatching { TextScale.valueOf(stored) }.getOrDefault(defaults.textScale)
            } ?: defaults.textScale,
            highContrast = preferences[Keys.highContrast] ?: defaults.highContrast,
        )
    }

    override fun put(settings: AppSettings) {
        runBlocking {
            dataStore.edit { preferences ->
                preferences[Keys.syncFrequency] = settings.syncFrequency.name
                preferences[Keys.allowMobileData] = settings.allowMobileData
                if (settings.country != null) {
                    preferences[Keys.country] = settings.country
                } else {
                    preferences.remove(Keys.country)
                }
                preferences[Keys.textScale] = settings.textScale.name
                preferences[Keys.highContrast] = settings.highContrast
            }
        }
    }

    private object Keys {
        val syncFrequency = stringPreferencesKey("sync_frequency")
        val allowMobileData = booleanPreferencesKey("allow_mobile_data")
        val country = stringPreferencesKey("country")
        val textScale = stringPreferencesKey("text_scale")
        val highContrast = booleanPreferencesKey("high_contrast")
    }

    private companion object {
        const val PREFERENCES_FILE_NAME = "app_settings"
    }
}
