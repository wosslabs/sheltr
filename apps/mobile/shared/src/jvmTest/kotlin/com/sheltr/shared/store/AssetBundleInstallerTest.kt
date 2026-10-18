package com.sheltr.shared.store

import com.sheltr.shared.bundle.BUNDLE_FORMAT_VERSION
import com.sheltr.shared.bundle.BundleChunkRef
import com.sheltr.shared.bundle.BundleDocument
import com.sheltr.shared.bundle.BundleManifest
import com.sheltr.shared.bundle.DocumentChunk
import com.sheltr.shared.bundle.DocumentFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * [AssetBundleInstaller] against the real generated sample bundle committed at
 * apps/mobile/android-sample-bundle (bundle.json + documents/<id>.json), published by tools/publisher from the
 * two approved corpus/manifest.yaml entries. Reading the real files (not inline fixtures) keeps this test
 * honest about what is actually shipped as a repository asset.
 */
class AssetBundleInstallerTest {

    private lateinit var dir: Path
    private lateinit var store: ContentStore

    /** Document ids are the first 32 hex characters of the SHA-256 of each entry's source URI (BundleDocument.id). */
    private val sampleDocumentIds = listOf("a3c9704e4f7b6933fba9d83eb7a47e51", "492064091b567181ed90815937854718")

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("sheltr-asset-installer")
        store = ContentStore(dir.toString(), JdbcStoreDriverFactory)
    }

    @AfterTest
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun tearDown() {
        store.close()
        dir.deleteRecursively()
    }

    @Test
    fun installsWhenStoreIsEmpty() {
        installSampleBundleIfEmpty(store, RealSampleAssets)

        assertNotNull(store.bundleVersion())
        for (id in sampleDocumentIds) {
            assertNotNull(store.document(id), "expected document $id from the sample bundle")
        }
    }

    @Test
    fun doesNothingWhenStoreAlreadyHasContent() {
        store.install(OTHER_MANIFEST, OTHER_SOURCE)
        val before = store.bundleVersion()

        installSampleBundleIfEmpty(store, RealSampleAssets)

        assertEquals(before, store.bundleVersion())
        assertNull(store.document(sampleDocumentIds.first()), "the pre-existing content was not replaced")
    }

    /**
     * Controller-added ruling (pre-flight review): a missing or malformed document asset must make
     * [installSampleBundleIfEmpty] throw, not leave a partially-installed store. The installer itself does no
     * staging; this proves it doesn't bypass [ContentStore.install]'s all-or-nothing staging/swap contract, which
     * is what actually provides the guarantee.
     */
    @Test
    fun malformedDocumentAssetLeavesStoreEmpty() {
        val malformedAssets = object : BundleAssetReader {
            override fun readManifestJson() = RealSampleAssets.readManifestJson()
            override fun readDocumentJson(documentId: String) = "{ not valid json"
        }

        assertFailsWith<Exception> { installSampleBundleIfEmpty(store, malformedAssets) }

        assertNull(store.bundleVersion(), "a failed install must leave the store exactly as it was: empty")
    }

    private object RealSampleAssets : BundleAssetReader {
        // Resolved relative to the shared module's project directory (Gradle's default test working directory),
        // one level up to apps/mobile, into the sibling android-sample-bundle directory.
        private val bundleDir = Path.of("..", "android-sample-bundle")

        override fun readManifestJson(): String = bundleDir.resolve("bundle.json").readText()

        override fun readDocumentJson(documentId: String): String =
            bundleDir.resolve("documents").resolve("$documentId.json").readText()
    }

    private companion object {
        val OTHER_MANIFEST = BundleManifest(
            BUNDLE_FORMAT_VERSION,
            "2026-01-01T00:00:00Z",
            listOf(
                BundleDocument(
                    id = "other-doc", title = "Other", agency = "Other Agency", publishedDate = "2026-01-01",
                    licence = "CC-BY-4.0", country = null, hazards = listOf("FLOOD"),
                    sourceUri = "https://example.org/other-doc", sourceType = "pdf",
                    checksum = "0".repeat(64), fileChecksum = "1".repeat(64),
                    chunks = listOf(BundleChunkRef("other-doc-0", "2".repeat(64))),
                ),
            ),
        )
        val OTHER_SOURCE = DocumentSource {
            DocumentFile("other-doc", listOf(DocumentChunk("other-doc-0", "Some pre-existing content.", null)))
        }
    }
}
