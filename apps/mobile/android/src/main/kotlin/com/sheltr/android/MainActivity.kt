package com.sheltr.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.sheltr.android.ui.SheltrNavHost
import com.sheltr.android.ui.theme.SheltrTheme
import com.sheltr.shared.settings.AppSettings

/**
 * Hosts [SheltrNavHost] inside [SheltrTheme]. Reads [AppSettings] once from
 * [SheltrApplication.settingsStore] to pick the theme's `highContrast`/`textScale`, then hoists that settings
 * value as `mutableStateOf` so it can be updated without re-reading the store on every recomposition.
 *
 * [SettingsStore][com.sheltr.shared.settings.SettingsStore] is a synchronous port with no change notifications,
 * so there is no reactive settings `Flow` to collect here; instead, [SheltrNavHost]'s `onSettingsChanged`
 * callback is the mechanism by which a future write (Task 5's Settings screen, after calling
 * `settingsStore.put`) updates this hoisted state and triggers recomposition.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as SheltrApplication

        setContent {
            var settings by remember { mutableStateOf(app.settingsStore.get()) }

            SheltrTheme(highContrast = settings.highContrast, textScale = settings.textScale) {
                SheltrNavHost(
                    contentStore = app.contentStore,
                    settingsStore = app.settingsStore,
                    retriever = app.retriever,
                    syncEngine = { app.syncEngine },
                    publisherBaseUrl = app.publisherBaseUrl,
                    publisherPublicKey = app.publisherPublicKey,
                    onSettingsChanged = { updated -> settings = updated },
                )
            }
        }
    }
}
