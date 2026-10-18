package com.sheltr.android

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.sheltr.android.ui.SheltrNavHost
import com.sheltr.android.ui.theme.SheltrTheme
import com.sheltr.shared.settings.AppSettings

/**
 * Hosts [SheltrNavHost] inside [SheltrTheme], but only once [SheltrApplication.sampleBundleReady] completes:
 * [com.sheltr.shared.store.ContentStore] is not thread safe against its own install, so no screen may be
 * composed -- and therefore read [SheltrApplication.contentStore] or [SheltrApplication.retriever] -- until the
 * first-run install attempt is done. Showing a brief loading message instead keeps that guarantee here, at the
 * one call site that builds [SheltrNavHost], rather than pushing an awaiting responsibility onto every screen.
 *
 * Reads [AppSettings] once from [SheltrApplication.settingsStore] to pick the theme's `highContrast`/`textScale`,
 * then hoists that settings value as `mutableStateOf` so it can be updated without re-reading the store on every
 * recomposition.
 *
 * [SettingsStore][com.sheltr.shared.settings.SettingsStore] is a synchronous port with no change notifications,
 * so there is no reactive settings `Flow` to collect here; instead, [SheltrNavHost]'s `onSettingsChanged`
 * callback is the mechanism by which a future write (Task 5's Settings screen, after calling
 * `settingsStore.put`) updates this hoisted state and triggers recomposition.
 *
 * [AppCompatActivity], not a plain `ComponentActivity`: `SettingsScreen`'s language picker calls
 * `AppCompatDelegate.setApplicationLocales`, which only takes effect pre-API-33 when its host Activity is an
 * `AppCompatActivity` (AppCompat's own documented per-app-language mechanism) -- on a plain `ComponentActivity`
 * that call is a silent no-op on roughly API 24-32, which is most of this app's `minSdk = 24` range. Compose
 * content set via `setContent {}` works identically under `AppCompatActivity`; this change does not move where
 * or how the UI is rendered. The manifest's `android:theme` must derive from AppCompat (see
 * `AndroidManifest.xml`) for this base class to work -- `Theme.Sheltr` (`res/values/themes.xml`) does, and is
 * also a `NoActionBar` variant so no stray platform action bar renders above the Compose content below.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as SheltrApplication

        setContent {
            var settings by remember { mutableStateOf(app.settingsStore.get()) }
            var sampleBundleReady by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                app.sampleBundleReady.await()
                sampleBundleReady = true
            }

            SheltrTheme(highContrast = settings.highContrast, textScale = settings.textScale) {
                // Renders the theme's background color behind everything: without this, body text and
                // backgrounds fall back to platform/Material defaults regardless of `highContrast`, since
                // nothing else in this tree actually paints `MaterialTheme.colorScheme.background`.
                Surface(color = MaterialTheme.colorScheme.background) {
                    if (sampleBundleReady) {
                        SheltrNavHost(
                            contentStore = app.contentStore,
                            settingsStore = app.settingsStore,
                            retriever = app.retriever,
                            syncEngine = { app.syncEngine },
                            publisherBaseUrl = app.publisherBaseUrl,
                            publisherPublicKey = app.publisherPublicKey,
                            onSettingsChanged = { updated -> settings = updated },
                        )
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.preparing_offline_content))
                        }
                    }
                }
            }
        }
    }
}
