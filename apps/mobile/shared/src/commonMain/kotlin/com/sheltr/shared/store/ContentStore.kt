package com.sheltr.shared.store

import app.cash.sqldelight.db.SqlDriver
import com.sheltr.shared.bundle.BUNDLE_FORMAT_VERSION
import com.sheltr.shared.bundle.BundleDocument
import com.sheltr.shared.bundle.BundleManifest
import com.sheltr.shared.bundle.DocumentFile
import com.sheltr.shared.store.db.SheltrDatabase

/** Opens a SQLite database file. Platform specific: JdbcStoreDriverFactory on the JVM, the native driver on iOS. */
fun interface StoreDriverFactory {
    /**
     * Opens the database file at [path]. If [create] is true the file is new and the implementation must create
     * the schema (SheltrDatabase.Schema); otherwise the file already holds a complete database.
     */
    fun open(path: String, create: Boolean): SqlDriver
}

/** Supplies the verified document file for one manifest entry, for example by reading documents/<id>.json. */
fun interface DocumentSource {
    fun load(document: BundleDocument): DocumentFile
}

/** Installs a new bundle as the active content. */
interface StoreUpdater {
    /**
     * Builds the content of [manifest] in a staging file, checks the staged counts against the manifest and only
     * then replaces the active file. On any failure the previous content stays active and unchanged and the
     * exception is thrown (a StoreUpdateException, or the exception thrown by [documents]).
     */
    fun install(manifest: BundleManifest, documents: DocumentSource)
}

/** When the content was last confirmed current by a sync. */
interface SyncStateStore {
    /** UTC epoch seconds of the last sync that ended Updated or UpToDate, or null if none has (or no content). */
    fun lastSyncEpochSeconds(): Long?

    /**
     * Records a successful sync at [epochSeconds] in the active content. Throws [StoreUpdateException] if no
     * content is installed: the time belongs to an installed bundle.
     */
    fun recordSync(epochSeconds: Long)
}

class StoreUpdateException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The on-device content: one SQLite file at <directory>/content.db holding one complete bundle.
 *
 * Updates never touch the active file. [install] writes <directory>/content.db.staging, verifies it, closes the
 * active database and renames the staging file over it ([atomicReplaceFile]), then reopens it. A crash or error at
 * any point before the rename leaves the old file as it was; a leftover staging file is deleted on the next open.
 *
 * Not thread safe: the caller serialises [install] with reads (the sync job and the UI share one instance).
 */
class ContentStore(
    directory: String,
    private val drivers: StoreDriverFactory,
) : StoreReader, StoreUpdater, SyncStateStore, AutoCloseable {

    val activePath: String = "$directory/$ACTIVE_FILE"
    val stagingPath: String = "$directory/$ACTIVE_FILE.staging"

    private var activeDriver: SqlDriver? = null
    private var active: SheltrDatabase? = null

    init {
        deleteStaging()
        openActive()
    }

    override fun bundleVersion(): String? = active?.contentQueries?.bundleVersion()?.executeAsOneOrNull()

    override fun document(id: String): StoredDocument? {
        val queries = active?.contentQueries ?: return null
        // Two queries, not one nested in the other's mapper: the JDBC driver closes the cursor's connection per query.
        val row = queries.documentById(id).executeAsOneOrNull() ?: return null
        val hazards = queries.hazardsForDocument(id).executeAsList()
        return with(row) {
            StoredDocument(this.id, title, agency, published_date, licence, country, hazards, source_uri, source_type, checksum, file_checksum)
        }
    }

    override fun documents(): List<StoredDocument> {
        val queries = active?.contentQueries ?: return emptyList()
        // Separate queries for the same reason as in [document].
        val rows = queries.allDocuments().executeAsList()
        val hazardsByDocument = queries.allDocumentHazards().executeAsList()
            .groupBy({ it.document_id }, { it.hazard })
        return rows.map { row ->
            with(row) {
                StoredDocument(
                    id, title, agency, published_date, licence, country, hazardsByDocument[id].orEmpty(),
                    source_uri, source_type, checksum, file_checksum,
                )
            }
        }
    }

    override fun chunks(documentId: String): List<StoredChunk> =
        active?.contentQueries?.chunksForDocument(documentId, ::storedChunk)?.executeAsList().orEmpty()

    override fun search(query: String, limit: Int): List<SearchHit> {
        require(limit > 0) { "limit must be positive" }
        val db = active ?: return emptyList()
        val match = ftsQueryFor(query) ?: return emptyList()
        return db.contentQueries.search(match, limit.toLong()) { id, documentId, content, pageNumber, position, score ->
            SearchHit(storedChunk(id, documentId, content, pageNumber, position), score)
        }.executeAsList()
    }

    override fun lastSyncEpochSeconds(): Long? =
        active?.contentQueries?.lastSync()?.executeAsOneOrNull()?.last_sync_epoch_seconds

    override fun recordSync(epochSeconds: Long) {
        val queries = active?.contentQueries ?: throw StoreUpdateException("no content installed to record a sync on")
        queries.setLastSync(epochSeconds)
    }

    override fun install(manifest: BundleManifest, documents: DocumentSource) {
        if (manifest.formatVersion != BUNDLE_FORMAT_VERSION) {
            throw StoreUpdateException("unsupported bundle format ${manifest.formatVersion}")
        }
        deleteStaging()
        try {
            stage(manifest, documents)
            verifyStaged(manifest)
        } catch (e: Throwable) {
            deleteStaging()
            throw e
        }
        swap()
    }

    override fun close() {
        closeActive()
    }

    private fun stage(manifest: BundleManifest, documents: DocumentSource) {
        val driver = drivers.open(stagingPath, create = true)
        try {
            val queries = SheltrDatabase(driver).contentQueries
            queries.transaction {
                for (doc in manifest.documents) {
                    val file = documents.load(doc)
                    if (file.id != doc.id) {
                        throw StoreUpdateException("document file ${file.id} does not match manifest entry ${doc.id}")
                    }
                    queries.insertDocument(
                        doc.id, doc.title, doc.agency, doc.publishedDate, doc.licence, doc.country,
                        doc.sourceUri, doc.sourceType, doc.checksum, doc.fileChecksum,
                    )
                    for (hazard in doc.hazards.distinct()) queries.insertDocumentHazard(doc.id, hazard)
                    file.chunks.forEachIndexed { position, chunk ->
                        queries.insertChunk(chunk.id, doc.id, chunk.content, chunk.pageNumber?.toLong(), position.toLong())
                    }
                }
                queries.setBundleVersion(manifest.bundleVersion, manifest.formatVersion.toLong())
            }
        } finally {
            driver.close()
        }
    }

    /** Reopens the closed staging file, as the active file would be opened, and checks it against the manifest. */
    private fun verifyStaged(manifest: BundleManifest) {
        val expectedDocuments = manifest.documents.size.toLong()
        val expectedChunks = manifest.documents.sumOf { it.chunks.size }.toLong()
        val expectedHazards = manifest.documents.sumOf { it.hazards.distinct().size }.toLong()
        val driver = drivers.open(stagingPath, create = false)
        try {
            val queries = SheltrDatabase(driver).contentQueries
            val documents = queries.countDocuments().executeAsOne()
            val chunks = queries.countChunks().executeAsOne()
            val indexed = queries.countIndexedChunks().executeAsOne()
            val hazards = queries.countDocumentHazards().executeAsOne()
            val version = queries.bundleVersion().executeAsOneOrNull()
            if (documents != expectedDocuments) {
                throw StoreUpdateException("staged $documents documents, manifest lists $expectedDocuments")
            }
            if (chunks != expectedChunks) {
                throw StoreUpdateException("staged $chunks chunks, manifest lists $expectedChunks")
            }
            if (hazards != expectedHazards) {
                throw StoreUpdateException("staged $hazards document hazards, manifest lists $expectedHazards")
            }
            if (indexed != chunks) {
                throw StoreUpdateException("keyword index has $indexed rows for $chunks chunks")
            }
            if (version != manifest.bundleVersion) {
                throw StoreUpdateException("staged bundle version $version, manifest has ${manifest.bundleVersion}")
            }
        } finally {
            driver.close()
        }
    }

    private fun swap() {
        closeActive()
        try {
            atomicReplaceFile(stagingPath, activePath)
        } catch (e: Throwable) {
            deleteStaging()
            openActive() // the old file is unchanged; keep serving it
            throw StoreUpdateException("could not replace the active content", e)
        }
        openActive()
    }

    private fun openActive() {
        if (!fileExists(activePath)) return
        val driver = drivers.open(activePath, create = false)
        activeDriver = driver
        active = SheltrDatabase(driver)
    }

    private fun closeActive() {
        active = null
        activeDriver?.close()
        activeDriver = null
    }

    private fun deleteStaging() {
        for (suffix in STAGING_SIDE_FILES) deleteFileIfExists(stagingPath + suffix)
    }

    private companion object {
        const val ACTIVE_FILE = "content.db"

        /** The staging database and the SQLite journal files that may sit beside it after a crash. */
        val STAGING_SIDE_FILES = listOf("", "-journal", "-wal", "-shm")

        fun storedChunk(id: String, documentId: String, content: String, pageNumber: Long?, position: Long) =
            StoredChunk(id, documentId, content, pageNumber?.toInt(), position.toInt())
    }
}
