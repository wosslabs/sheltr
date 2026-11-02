package com.sheltr.android

import io.ktor.client.HttpClient
import org.junit.Test

/**
 * Bundle sync builds its client with `HttpClient()`, which throws if no engine is on the classpath. That made
 * "Sync now" fail on every call once, so this guards the dependency directly.
 */
class HttpEngineTest {
    @Test
    fun httpClientCanBeConstructedWithTheAppsClasspath() {
        HttpClient().close()
    }
}
