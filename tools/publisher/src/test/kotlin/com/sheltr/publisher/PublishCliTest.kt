package com.sheltr.publisher

import com.sheltr.publisher.bundle.BUNDLE_MANIFEST_FILE
import com.sheltr.publisher.bundle.BUNDLE_SIGNATURE_FILE
import com.sheltr.publisher.fetch.Checksum
import com.sheltr.publisher.fetch.RawContent
import com.sheltr.publisher.fetch.SourceConnector
import com.sheltr.publisher.fetch.sha256Hex
import com.sheltr.publisher.html.MainContentExtractor
import com.sheltr.publisher.manifest.SourceType
import com.sheltr.publisher.signing.generateKeyPair
import com.sheltr.publisher.signing.verify
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.KeyPair
import java.util.Base64
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PublishCliTest {

    private lateinit var scratch: Path
    private lateinit var cwd: Path
    private lateinit var keyPair: KeyPair
    private lateinit var keyPath: Path
    private lateinit var publicKeyPath: Path
    private lateinit var manifestPath: Path
    private lateinit var out: ByteArrayOutputStream
    private lateinit var err: ByteArrayOutputStream

    private val page = resource("/html/article-page.html")
    private val pageText: ByteArray = MainContentExtractor().extract(String(page, StandardCharsets.UTF_8))
        .toByteArray(StandardCharsets.UTF_8)
    private val pageChecksum = sha256Hex(pageText)

    private val goodUri = "https://guide.example.org/prepare"
    private val changedUri = "https://guide.example.org/changed"

    @BeforeTest
    fun setUp() {
        // A temp directory with no settings.gradle.kts above it: outside any repository.
        scratch = createTempDirectory("publisher-cli-")
        cwd = scratch.resolve("work").createDirectories()
        keyPair = generateKeyPair()
        keyPath = scratch.resolve("signing-key.der").also { it.writeBytes(keyPair.private.encoded) }
        publicKeyPath = scratch.resolve("signing-key.pub").also { it.writeBytes(keyPair.public.encoded) }
        manifestPath = scratch.resolve("manifest.yaml")
        out = ByteArrayOutputStream()
        err = ByteArrayOutputStream()
    }

    @AfterTest
    fun tearDown() {
        scratch.toFile().deleteRecursively()
    }

    // --- argument parsing ---

    @Test
    fun parsesAFullValidCommand() {
        val args = parsePublish(listOf("publish", "--manifest", "m.yaml", "--out", "dist", "--key", "k.der", "--public-key", "k.pub"))

        assertEquals(Paths.get("m.yaml"), args.manifest)
        assertEquals(Paths.get("dist"), args.out)
        assertEquals(Paths.get("k.der"), args.key)
        assertEquals(Paths.get("k.pub"), args.publicKey)
    }

    @Test
    fun publicKeyIsOptional() {
        val args = parsePublish(listOf("publish", "--key", "k.der", "--out", "dist", "--manifest", "m.yaml"))

        assertEquals(null, args.publicKey)
    }

    @Test
    fun missingArgumentsAreAUsageError() {
        assertUsageError(emptyList(), "missing command")
        assertUsageError(listOf("publish"), "missing --manifest")
        assertUsageError(listOf("publish", "--manifest", "m", "--out", "o"), "missing --key")
        assertUsageError(listOf("publish", "--manifest", "m", "--key", "k"), "missing --out")
    }

    @Test
    fun unknownCommandsAndArgumentsAreAUsageError() {
        assertUsageError(listOf("build", "--manifest", "m", "--out", "o", "--key", "k"), "unknown command 'build'")
        assertUsageError(listOf("publish", "--manifest", "m", "--out", "o", "--key", "k", "--verbose"), "unknown argument '--verbose'")
        assertUsageError(listOf("publish", "stray", "--manifest", "m", "--out", "o", "--key", "k"), "unknown argument 'stray'")
    }

    @Test
    fun flagsNeedExactlyOneValue() {
        assertUsageError(listOf("publish", "--manifest", "m", "--out", "o", "--key"), "--key needs a value")
        assertUsageError(listOf("publish", "--manifest", "--out", "o", "--key", "k"), "--manifest needs a value")
        assertUsageError(listOf("publish", "--manifest", "", "--out", "o", "--key", "k"), "--manifest needs a value")
        assertUsageError(
            listOf("publish", "--manifest", "m", "--out", "o", "--key", "k", "--key", "k2"),
            "--key given more than once",
        )
    }

    @Test
    fun runWithBadArgumentsPrintsUsageAndExitsTwo() {
        val code = runCli(arrayOf("publish", "--manifest", "m"))

        assertEquals(EXIT_USAGE, code)
        assertContains(err.toString(StandardCharsets.UTF_8), "missing --out")
        assertContains(err.toString(StandardCharsets.UTF_8), USAGE)
    }

    @Test
    fun runWithNoArgumentsExitsTwo() {
        assertEquals(EXIT_USAGE, runCli(arrayOf()))
    }

    // --- end to end ---

    @Test
    fun buildsVerifiesAndReportsOnAFixtureManifest() {
        writeManifest(entry(goodUri, pageChecksum))
        val dist = scratch.resolve("dist")

        val code = runCli(publishArgs(dist, withPublicKey = true), sources = mapOf(goodUri to pageText))

        assertEquals(EXIT_OK, code, err.toString(StandardCharsets.UTF_8))
        val stdout = out.toString(StandardCharsets.UTF_8)
        assertContains(stdout, "Documents: 1")
        assertContains(stdout, "Bundle version: ")
        assertContains(stdout, "Signature: verified")

        val bundle = dist.resolve(BUNDLE_MANIFEST_FILE).readBytes()
        assertTrue(verify(keyPair.public, bundle, dist.resolve(BUNDLE_SIGNATURE_FILE).readBytes()))
    }

    @Test
    fun reportsWhenNoPublicKeyWasGiven() {
        writeManifest(entry(goodUri, pageChecksum))

        val code = runCli(publishArgs(scratch.resolve("dist"), withPublicKey = false), sources = mapOf(goodUri to pageText))

        assertEquals(EXIT_OK, code)
        assertContains(out.toString(StandardCharsets.UTF_8), "Signature: not verified (no --public-key given)")
    }

    @Test
    fun aFailedEntryIsListedAndExitsOne() {
        writeManifest(entry(goodUri, pageChecksum), entry(changedUri, "0".repeat(64)))
        val dist = scratch.resolve("dist")

        val code = runCli(publishArgs(dist, withPublicKey = true), sources = mapOf(goodUri to pageText, changedUri to pageText))

        assertEquals(EXIT_FAILURE, code)
        val stderr = err.toString(StandardCharsets.UTF_8)
        assertContains(stderr, "FAILED $changedUri: Checksum mismatch for $changedUri")
        assertFalse(stderr.contains("FAILED $goodUri"))
        assertContains(out.toString(StandardCharsets.UTF_8), "Documents: 1")
    }

    @Test
    fun aBuildWithNoDocumentExitsOneAndLeavesNothingBehind() {
        writeManifest(entry(changedUri, "0".repeat(64)))
        val dist = scratch.resolve("dist")

        val code = runCli(publishArgs(dist, withPublicKey = true), sources = mapOf(changedUri to pageText))

        assertEquals(EXIT_FAILURE, code)
        assertContains(err.toString(StandardCharsets.UTF_8), "FAILED $changedUri")
        assertFalse(Files.exists(dist))
    }

    @Test
    fun aWrongPublicKeyFailsVerification() {
        writeManifest(entry(goodUri, pageChecksum))
        val otherKey = scratch.resolve("other.pub").also { it.writeBytes(generateKeyPair().public.encoded) }

        val code = runCli(
            arrayOf("publish", "--manifest", "$manifestPath", "--out", "dist", "--key", "$keyPath", "--public-key", "$otherKey"),
            sources = mapOf(goodUri to pageText),
        )

        assertEquals(EXIT_FAILURE, code)
        assertContains(err.toString(StandardCharsets.UTF_8), "signature does not verify")
    }

    @Test
    fun anUnreadablePublicKeyFailsBeforeAnyBuild() {
        writeManifest(entry(goodUri, pageChecksum))
        val dist = scratch.resolve("dist")

        val code = runCli(
            arrayOf("publish", "--manifest", "$manifestPath", "--out", "$dist", "--key", "$keyPath", "--public-key", "${scratch.resolve("missing.pub")}"),
            sources = mapOf(goodUri to pageText),
        )

        assertEquals(EXIT_FAILURE, code)
        assertContains(err.toString(StandardCharsets.UTF_8), "cannot read public key")
        assertFalse(Files.exists(dist))
    }

    @Test
    fun theKeyIsNeverPrinted() {
        writeManifest(entry(goodUri, pageChecksum), entry(changedUri, "0".repeat(64)))
        val encoded = Base64.getEncoder().encodeToString(keyPair.private.encoded)

        runCli(publishArgs(scratch.resolve("dist"), withPublicKey = true), sources = mapOf(goodUri to pageText, changedUri to pageText))

        val printed = out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8)
        assertFalse(printed.contains(encoded.takeLast(40)))
    }

    @Test
    fun refusesAKeyInsideTheRepositoryAndWritesNothing() {
        val repo = scratch.resolve("fake-repo").createDirectories()
        repo.resolve(REPO_MARKER).writeText("rootProject.name = \"fake\"\n")
        val repoKey = repo.resolve("signing-key.der").also { it.writeBytes(keyPair.private.encoded) }
        writeManifest(entry(goodUri, pageChecksum))
        val dist = repo.resolve("dist")

        val code = runPublisher(
            arrayOf("publish", "--manifest", "$manifestPath", "--out", "$dist", "--key", "$repoKey"),
            repo,
            PrintStream(out, true, StandardCharsets.UTF_8),
            PrintStream(err, true, StandardCharsets.UTF_8),
            connectorsFor = { fixtureConnectors(mapOf(goodUri to pageText)) },
        )

        assertEquals(EXIT_FAILURE, code)
        assertContains(err.toString(StandardCharsets.UTF_8), "inside the repository")
        assertFalse(Files.exists(dist))
    }

    @Test
    fun missingManifestExitsOne() {
        val code = runCli(publishArgs(scratch.resolve("dist"), withPublicKey = false), sources = emptyMap())

        assertEquals(EXIT_FAILURE, code)
        assertContains(err.toString(StandardCharsets.UTF_8), "cannot read manifest")
    }

    // --- helpers ---

    private fun runCli(args: Array<String>, sources: Map<String, ByteArray> = emptyMap()): Int = runPublisher(
        args,
        cwd,
        PrintStream(out, true, StandardCharsets.UTF_8),
        PrintStream(err, true, StandardCharsets.UTF_8),
        connectorsFor = { fixtureConnectors(sources) },
    )

    /** Connectors that serve fixture bytes for the URIs given; any other URI is an error. */
    private fun fixtureConnectors(sources: Map<String, ByteArray>): Map<SourceType, SourceConnector> {
        val connector = SourceConnector { uri ->
            val bytes = sources[uri] ?: throw RuntimeException("no fixture for $uri")
            RawContent(bytes, Checksum(sha256Hex(bytes)))
        }
        return mapOf(SourceType.HTML to connector, SourceType.PDF to connector)
    }

    private fun publishArgs(out: Path, withPublicKey: Boolean): Array<String> = buildList {
        addAll(listOf("publish", "--manifest", "$manifestPath", "--out", "$out", "--key", "$keyPath"))
        if (withPublicKey) addAll(listOf("--public-key", "$publicKeyPath"))
    }.toTypedArray()

    private fun assertUsageError(args: List<String>, expectedMessage: String) {
        val error = kotlin.test.assertFailsWith<UsageException> { parsePublish(args) }
        assertContains(error.message ?: "", expectedMessage)
    }

    private fun writeManifest(vararg entries: String) {
        manifestPath.writeText("documents:\n" + entries.joinToString("\n"))
    }

    private fun entry(uri: String, checksum: String): String = """
        |  - source_uri: "$uri"
        |    source_type: html
        |    title: "Prepare"
        |    source_agency: "Example Agency"
        |    hazards: [FLOOD]
        |    country: "GB"
        |    published_date: "2024-02-02"
        |    license: "Open Government Licence v3.0, https://example.org/licence"
        |    checksum: "$checksum"
        """.trimMargin()

    private fun resource(name: String): ByteArray {
        val stream = PublishCliTest::class.java.getResourceAsStream(name)
        kotlin.test.assertNotNull(stream, "fixture $name")
        return stream.use { it.readAllBytes() }
    }
}
