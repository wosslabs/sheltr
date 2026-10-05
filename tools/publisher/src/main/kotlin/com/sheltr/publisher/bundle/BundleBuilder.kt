package com.sheltr.publisher.bundle

import com.sheltr.publisher.chunking.ChunkDraft
import com.sheltr.publisher.chunking.Chunker
import com.sheltr.publisher.fetch.SourceConnector
import com.sheltr.publisher.fetch.SourcePolicy
import com.sheltr.publisher.fetch.sha256Hex
import com.sheltr.publisher.manifest.CorpusManifest
import com.sheltr.publisher.manifest.SourceType
import com.sheltr.publisher.pdf.PdfContentExtractor
import com.sheltr.publisher.signing.loadPrivateKey
import com.sheltr.publisher.signing.sign
import com.sheltr.publisher.text.PageText
import com.sheltr.publisher.text.TextCleaner
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.temporal.ChronoUnit

/** One manifest entry that was left out of the bundle, and why. */
data class EntryFailure(val sourceUri: String, val reason: String)

/** The outcome of a build that produced a bundle. */
data class BuildResult(
    val outputDir: Path,
    val manifest: BundleManifest,
    val failures: List<EntryFailure>,
)

/** Thrown when no bundle is produced. Nothing is left at the output path. */
class BundleBuildException(message: String, val failures: List<EntryFailure> = emptyList(), cause: Throwable? = null) :
    RuntimeException(message, cause)

/** First 32 hex characters of the SHA-256 of [sourceUri]. Stable for the same URI. */
fun documentId(sourceUri: String): String = sha256Hex(sourceUri.toByteArray(StandardCharsets.UTF_8)).take(32)

/**
 * Builds a signed corpus bundle from an approved manifest. Every entry in the manifest is approved.
 *
 * Per entry: check the source policy, fetch with the connector for its source type, compare the approved checksum
 * (case-insensitive) with the content checksum, then extract (PDF), clean and chunk. An entry that fails any step
 * is reported in [BuildResult.failures] and left out; the other entries still build.
 *
 * The bundle is written to a temporary sibling of the output directory and renamed into place only after it is
 * complete and signed. If the build fails (unreadable key, output already present, no entry built, I/O error),
 * the temporary directory is removed and nothing exists at the output path.
 */
class BundleBuilder(
    private val connectors: Map<SourceType, SourceConnector>,
    private val policy: SourcePolicy,
    private val clock: Clock = Clock.systemUTC(),
) {

    private val pdfExtractor = PdfContentExtractor()

    fun build(manifest: CorpusManifest, outputDir: Path, privateKeyPath: Path): BuildResult {
        val output = outputDir.toAbsolutePath().normalize()
        if (Files.exists(output)) {
            throw BundleBuildException("Output already exists: $output")
        }
        // Fail fast, before any download. The key is read only from the path the caller gives.
        val privateKey = try {
            loadPrivateKey(privateKeyPath)
        } catch (e: Exception) {
            throw BundleBuildException("Cannot read the signing key at $privateKeyPath", cause = e)
        }

        val parent = output.parent ?: throw BundleBuildException("Output path has no parent: $output")
        Files.createDirectories(parent)
        val staging = Files.createTempDirectory(parent, ".${output.fileName}.tmp-")
        var moved = false
        try {
            val documentsDir = Files.createDirectory(staging.resolve(BUNDLE_DOCUMENTS_DIR))
            val documents = mutableListOf<BundleDocument>()
            val failures = mutableListOf<EntryFailure>()
            for (entry in manifest.documents) {
                val chunks = try {
                    chunksFor(entry)
                } catch (e: Exception) {
                    failures += EntryFailure(entry.sourceUri, e.message ?: e.javaClass.simpleName)
                    continue
                }
                documents += writeDocument(entry, chunks, documentsDir)
            }
            if (documents.isEmpty()) {
                throw BundleBuildException("No document built; nothing to publish", failures.toList())
            }

            val bundle = BundleManifest(
                formatVersion = BUNDLE_FORMAT_VERSION,
                bundleVersion = clock.instant().truncatedTo(ChronoUnit.SECONDS).toString(),
                documents = documents.toList(),
            )
            val bundleBytes = BundleJson.encodeToString(BundleManifest.serializer(), bundle)
                .toByteArray(StandardCharsets.UTF_8)
            Files.write(staging.resolve(BUNDLE_MANIFEST_FILE), bundleBytes)
            Files.write(staging.resolve(BUNDLE_SIGNATURE_FILE), sign(privateKey, bundleBytes))

            try {
                Files.move(staging, output, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: FileAlreadyExistsException) {
                throw BundleBuildException("Output already exists: $output", cause = e)
            }
            moved = true
            return BuildResult(output, bundle, failures.toList())
        } finally {
            if (!moved) {
                deleteRecursively(staging)
            }
        }
    }

    private fun chunksFor(entry: CorpusManifest.Document): List<ChunkDraft> {
        val uri = entry.sourceUri
        val sourceType = entry.metadata.sourceType
        if (!policy.allows(uri)) {
            throw IllegalArgumentException(SourcePolicy.rejectionMessage(uri))
        }
        val connector = connectors[sourceType]
            ?: throw UnsupportedOperationException("Unsupported source type $sourceType, supported: ${connectors.keys}")
        val raw = connector.fetch(uri)
        if (!entry.checksum.equals(raw.checksum.hex, ignoreCase = true)) {
            throw IllegalStateException(
                "Checksum mismatch for $uri: approved ${entry.checksum} but downloaded ${raw.checksum.hex}. " +
                    "The document changed since approval and needs re-approval.",
            )
        }
        val chunks = when (sourceType) {
            SourceType.HTML -> Chunker.chunkUnpaged(TextCleaner.clean(String(raw.bytes, StandardCharsets.UTF_8)))
            SourceType.PDF -> Chunker.chunk(
                pdfExtractor.extract(raw.bytes).map { PageText(it.pageNumber, TextCleaner.clean(it.text)) },
            )
        }
        if (chunks.isEmpty()) {
            val hint = if (sourceType == SourceType.PDF) {
                " Scanned or image-only PDFs are not supported."
            } else {
                " The page has no main text."
            }
            throw IllegalStateException("No extractable text in $uri.$hint")
        }
        return chunks
    }

    private fun writeDocument(entry: CorpusManifest.Document, drafts: List<ChunkDraft>, dir: Path): BundleDocument {
        val id = documentId(entry.sourceUri)
        val chunks = drafts.mapIndexed { index, draft -> BundleChunk("$id-$index", draft.content, draft.pageNumber) }
        val fileBytes = BundleJson.encodeToString(DocumentFile.serializer(), DocumentFile(id, chunks))
            .toByteArray(StandardCharsets.UTF_8)
        Files.write(dir.resolve("$id.json"), fileBytes)
        val metadata = entry.metadata
        return BundleDocument(
            id = id,
            title = metadata.title,
            agency = metadata.sourceAgency,
            publishedDate = metadata.publishedDate.toString(),
            licence = metadata.license,
            country = metadata.country,
            hazards = metadata.disasterTypes.map { it.name },
            sourceUri = entry.sourceUri,
            sourceType = metadata.sourceType.name.lowercase(),
            checksum = entry.checksum.lowercase(),
            fileChecksum = sha256Hex(fileBytes),
            chunks = chunks.map { ChunkRef(it.id, sha256Hex(it.content.toByteArray(StandardCharsets.UTF_8))) },
        )
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
