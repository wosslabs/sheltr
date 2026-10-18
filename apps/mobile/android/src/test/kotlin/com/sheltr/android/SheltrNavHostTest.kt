package com.sheltr.android

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import com.sheltr.android.ui.SheltrNavHost
import com.sheltr.shared.retrieval.Retriever
import com.sheltr.shared.settings.InMemorySettingsStore
import com.sheltr.shared.store.ContentStore
import com.sheltr.shared.store.StoreDriverFactory
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Smoke test for [SheltrNavHost]: proves the NavHost actually renders and starts on the Home route, not just
 * that the composable compiles. Tasks 2-5 replace each stub screen; this test should keep passing unchanged as
 * long as the Home route's test tag survives.
 *
 * `@Config(application = Application::class)` overrides the manifest's `SheltrApplication` with a bare
 * [Application] for this test: without it, Robolectric would instantiate the real `SheltrApplication` to back
 * the test's [ComponentActivity], whose `onCreate` would start the real first-run sample-bundle install -- which
 * opens a real [com.sheltr.shared.store.AndroidStoreDriverFactory] driver and so requires requery's native SQLite
 * library, unavailable under Robolectric (see that factory's own doc comment). This test exercises [SheltrNavHost]
 * in isolation with fakes below, not that install path, so the real `SheltrApplication` must not run here at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SheltrNavHostTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun startsOnHomeRoute() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storeDirectory = File(context.cacheDir, "nav-host-test-${System.nanoTime()}").apply { mkdirs() }
        // No content.db exists in this fresh directory, so ContentStore never calls the driver factory (see
        // ContentStore.openActive): the stub screens under test don't read the store, so a driver that refuses
        // to open anything is fine here, and lets this test avoid AndroidStoreDriverFactory's requery native
        // library, which cannot load under Robolectric.
        val contentStore = ContentStore(
            storeDirectory.absolutePath,
            StoreDriverFactory { _, _ -> error("not expected to be called: the stub screens under test don't open the store") },
        )

        compose.setContent {
            SheltrNavHost(
                contentStore = contentStore,
                settingsStore = InMemorySettingsStore(),
                retriever = Retriever(contentStore),
                syncEngine = { error("not expected to be called in this test") },
                publisherBaseUrl = null,
                publisherPublicKey = null,
                onSettingsChanged = {},
            )
        }

        compose.onNodeWithTag("home-screen").assertExists()
    }
}
