package com.sheltr.android

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

/**
 * Smoke test for [SheltrNavHost]: proves the NavHost actually renders and starts on the Home route, not just
 * that the composable compiles. Tasks 2-5 replace each stub screen; this test should keep passing unchanged as
 * long as the Home route's test tag survives.
 */
@RunWith(RobolectricTestRunner::class)
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
