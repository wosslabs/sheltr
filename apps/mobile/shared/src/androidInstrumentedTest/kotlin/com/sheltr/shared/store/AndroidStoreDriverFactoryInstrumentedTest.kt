package com.sheltr.shared.store

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sheltr.shared.bundle.BUNDLE_FORMAT_VERSION
import com.sheltr.shared.bundle.BundleChunkRef
import com.sheltr.shared.bundle.BundleDocument
import com.sheltr.shared.bundle.BundleManifest
import com.sheltr.shared.bundle.DocumentChunk
import com.sheltr.shared.bundle.DocumentFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.runner.RunWith

/**
 * NOT EXECUTED in this environment: there is no Android emulator or device here, and setting one up is out of
 * scope for this task. This test requires `gradle :apps:mobile:shared:connectedAndroidTest` against a real
 * device or emulator (or a CI runner that provides one).
 *
 * It is the only test that can actually prove AndroidStoreDriverFactory works end to end: requery's native
 * SQLite (needed because Android's own bundled SQLite lacks FTS5 -- see AndroidStoreDriverFactory's doc comment)
 * ships device-ABI native libraries only, so it cannot run under Robolectric or any host-JVM test (confirmed
 * while building this suite: an earlier Robolectric version of this same test, since removed in favor of this
 * instrumented one, failed with UnsatisfiedLinkError the moment the database was first opened). Until this test
 * is run on a device or emulator, the FTS5-via-requery path -- this app's only retrieval mechanism, on Android --
 * is unverified.
 *
 * Mirrors the jvmTest ContentStoreTest and androidUnitTest StoreFilesAndroidTest fixture shape: install a tiny
 * manifest and document, close, reopen a fresh ContentStore on the same directory, and confirm the document,
 * its chunks, and an FTS5 search all survive.
 */
@RunWith(AndroidJUnit4::class)
class AndroidStoreDriverFactoryInstrumentedTest {

    @Test
    fun opensReopensAndSearchesTheSameFile() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // minSdk 24: java.nio.file/kotlin.io.path need API 26+, so plain java.io.File is used here, same as the
        // production actuals in StoreFiles.android.kt.
        val dir = File(context.cacheDir, "sheltr-android-store-${System.nanoTime()}")
        check(dir.mkdirs()) { "could not create test directory $dir" }
        try {
            val store = ContentStore(dir.toString(), AndroidStoreDriverFactory(context))
            store.install(MANIFEST, SOURCE)
            store.close()

            val reopened = ContentStore(dir.toString(), AndroidStoreDriverFactory(context))
            try {
                val document = assertNotNull(reopened.document("doc-a"))
                assertEquals("Flood guide", document.title)
                assertEquals("NO", document.country)
                assertEquals(listOf("FLOOD"), document.hazards)
                assertEquals(listOf("doc-a-0", "doc-a-1"), reopened.chunks("doc-a").map { it.id })

                // A real FTS5 MATCH query, not just successful schema creation: requery's bundled SQLite is what
                // makes the "fts5" virtual table module available at all on Android.
                assertEquals(listOf("doc-a-1"), reopened.search("sandbags").map { it.chunk.id })
                assertEquals(emptyList(), reopened.search("volcano"), "word in no chunk")
            } finally {
                reopened.close()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    private companion object {
        val FILE = DocumentFile(
            "doc-a",
            listOf(
                DocumentChunk("doc-a-0", "Move to higher ground.", pageNumber = 1),
                DocumentChunk("doc-a-1", "Place sandbags at doors.", pageNumber = 2),
            ),
        )

        val MANIFEST = BundleManifest(
            BUNDLE_FORMAT_VERSION,
            "2026-10-01T00:00:00Z",
            listOf(
                BundleDocument(
                    id = "doc-a",
                    title = "Flood guide",
                    agency = "Example Agency",
                    publishedDate = "2024-03-15",
                    licence = "CC-BY-4.0",
                    country = "NO",
                    hazards = listOf("FLOOD"),
                    sourceUri = "https://example.org/doc-a",
                    sourceType = "pdf",
                    checksum = "0".repeat(64),
                    fileChecksum = "1".repeat(64),
                    chunks = FILE.chunks.map { BundleChunkRef(it.id, "2".repeat(64)) },
                ),
            ),
        )

        val SOURCE = DocumentSource { FILE }
    }
}
