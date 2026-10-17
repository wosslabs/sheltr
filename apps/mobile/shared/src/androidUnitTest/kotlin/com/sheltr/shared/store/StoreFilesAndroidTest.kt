package com.sheltr.shared.store

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * StoreFiles.android.kt's three actuals use only java.io.File -- no Context, no Android framework API -- so they
 * are genuinely host-testable, unlike AndroidStoreDriverFactory (see its doc comment). This is deliberately a
 * plain JUnit test, not a Robolectric one: there is nothing Android-specific here to shadow.
 *
 * The ContentStore-through-AndroidStoreDriverFactory round trip (schema creation, install, FTS5 search) is NOT
 * covered by this suite. requery's sqlite-android -- the dependency AndroidStoreDriverFactory needs for FTS5,
 * since Android's own bundled SQLite does not compile FTS5 in -- ships native libraries only for Android device
 * ABIs (arm64-v8a, armeabi-v7a, x86, x86_64), with no macOS/Linux/Windows build. Robolectric shadows the
 * android.database.sqlite and androidx.sqlite.db.framework call path with its own host-native SQLite, but it has
 * no mechanism to substitute a third-party library's own System.loadLibrary call, so requery's native code
 * cannot load under Robolectric (or any host JVM) at all. That round trip is written instead as
 * AndroidStoreDriverFactoryInstrumentedTest in the androidInstrumentedTest source set, to run on a real device
 * or emulator -- NOT EXECUTED in this environment, which has neither.
 */
class StoreFilesAndroidTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("sheltr-store-files")
    }

    @AfterTest
    @OptIn(ExperimentalPathApi::class)
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun fileExistsIsTrueOnlyForAnExistingRegularFile() {
        val path = dir.resolve("a.txt")
        assertFalse(fileExists(path.toString()))

        path.writeText("hi")
        assertTrue(fileExists(path.toString()))
    }

    @Test
    fun fileExistsIsFalseForADirectory() {
        assertFalse(fileExists(dir.toString()))
    }

    @Test
    fun deleteFileIfExistsRemovesAnExistingFileAndIsANoOpWhenAlreadyGone() {
        val path = dir.resolve("a.txt")
        path.writeText("hi")

        deleteFileIfExists(path.toString())
        assertFalse(path.exists())

        deleteFileIfExists(path.toString()) // already gone: must not throw
    }

    @Test
    fun atomicReplaceFileReplacesAnExistingTargetAndRemovesTheSource() {
        val source = dir.resolve("source.txt")
        val target = dir.resolve("target.txt")
        source.writeText("new content")
        target.writeText("old content")

        atomicReplaceFile(source.toString(), target.toString())

        assertFalse(source.exists(), "source no longer exists")
        assertEquals("new content", target.readText())
    }

    @Test
    fun atomicReplaceFileWorksWhenTargetDoesNotExist() {
        val source = dir.resolve("source.txt")
        val target = dir.resolve("target.txt")
        source.writeText("new content")

        atomicReplaceFile(source.toString(), target.toString())

        assertFalse(source.exists())
        assertEquals("new content", target.readText())
    }
}
