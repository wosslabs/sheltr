package com.sheltr.shared.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import java.util.WeakHashMap
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * [SettingsStore] backed by AndroidX DataStore (Preferences). One [Preferences.Key][androidx.datastore.preferences.core.Preferences.Key]
 * per [AppSettings] field; a key missing from the preferences file (first run, or a file written before a field
 * such as [AppSettings.highContrast] existed) falls back to that field's [AppSettings] default rather than
 * failing, so partially-written preference files stay valid across app updates that add fields.
 *
 * [SettingsStore] is a synchronous port (see its doc comment); DataStore's API is suspend-based, so [get] and
 * [put] block with [runBlocking] to bridge the two. [context] is captured as [Context.getApplicationContext] to
 * avoid leaking an `Activity`, matching [com.sheltr.shared.store.AndroidStoreDriverFactory]'s established pattern.
 *
 * AndroidX DataStore enforces, per process, at most one live [DataStore] instance per backing file -- a second
 * one throws `IllegalStateException` the moment it's used. The usual AndroidX sample pattern (a top-level
 * `by preferencesDataStore(name = ...)` property) satisfies that by caching globally, but it caches the *first*
 * `applicationContext` it is ever handed and reuses that one DataStore for the lifetime of the process no matter
 * which `Context` later callers pass in -- fine for a real app with exactly one `Application`, but wrong for
 * anything that can see more than one application Context in the same process, such as this test suite under
 * Robolectric (one fresh Context per `@Test` method). [instances] instead caches per `applicationContext`
 * identity: constructing more [AndroidSettingsStore]s against the *same* Context reuses one DataStore for that
 * Context (satisfying AndroidX's one-instance-per-file rule), while a *different* Context -- a different real
 * `Application`, or Robolectric's fresh one in another test -- gets its own, independent DataStore and file.
 */
class AndroidSettingsStore(context: Context) : SettingsStore {
    private val appContext = context.applicationContext
    private val dataStore: DataStore<Preferences> = synchronized(instances) {
        instances.getOrPut(appContext) {
            PreferenceDataStoreFactory.create(
                produceFile = { appContext.preferencesDataStoreFile(PREFERENCES_FILE_NAME) },
            )
        }
    }

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

        // WeakHashMap so a Context (and its DataStore) can be collected once nothing else holds the Context --
        // relevant mainly to long-running test suites that construct a fresh application Context per test.
        // Access is manually synchronized at the call site above, not via Collections.synchronizedMap: a
        // synchronized wrapper only makes individual calls atomic, not the check-then-create sequence
        // getOrPut performs, which is exactly the race this guards against.
        val instances = WeakHashMap<Context, DataStore<Preferences>>()
    }
}
