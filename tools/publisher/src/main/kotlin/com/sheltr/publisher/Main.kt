package com.sheltr.publisher

import com.sheltr.publisher.bundle.BUNDLE_MANIFEST_FILE
import com.sheltr.publisher.bundle.BUNDLE_SIGNATURE_FILE
import com.sheltr.publisher.bundle.BundleBuildException
import com.sheltr.publisher.bundle.BundleBuilder
import com.sheltr.publisher.fetch.HostAllowlistSourcePolicy
import com.sheltr.publisher.fetch.SourceConnector
import com.sheltr.publisher.fetch.SourcePolicy
import com.sheltr.publisher.html.HtmlPageConnector
import com.sheltr.publisher.manifest.CorpusManifest
import com.sheltr.publisher.manifest.SourceType
import com.sheltr.publisher.pdf.HttpPdfConnector
import com.sheltr.publisher.signing.loadPublicKey
import com.sheltr.publisher.signing.verify
import java.io.PrintStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

const val USAGE: String = """usage: publisher publish --manifest <file> --out <dir> --key <path> [--public-key <path>]

  --manifest    approved corpus manifest (YAML)
  --out         directory for the bundle; must not exist yet
  --key         Ed25519 private key (PKCS#8, DER or PEM); must be outside the repository
  --public-key  Ed25519 public key (X.509, DER or PEM); the built bundle is verified with it"""

const val EXIT_OK: Int = 0
const val EXIT_FAILURE: Int = 1
const val EXIT_USAGE: Int = 2

fun main(args: Array<String>) {
    exitProcess(runPublisher(args, Paths.get("").toAbsolutePath(), System.out, System.err))
}

/**
 * Runs the publisher command line and returns the exit code: 0 on success, 1 when the build or verification
 * fails (failed entries are listed on [err]), 2 for a malformed command line. [cwd] is the directory relative
 * paths and the repository search start from. [connectorsFor] builds the source connectors from the source policy;
 * tests replace it to serve fixtures without a network.
 */
fun runPublisher(
    args: Array<String>,
    cwd: Path,
    out: PrintStream,
    err: PrintStream,
    connectorsFor: (SourcePolicy) -> Map<SourceType, SourceConnector> = ::defaultConnectors,
): Int {
    val command = try {
        parsePublish(args.toList())
    } catch (e: UsageException) {
        err.println("publisher: ${e.message}")
        err.println(USAGE)
        return EXIT_USAGE
    }

    val keyPath = cwd.resolve(command.key)
    keyInsideRepository(keyPath, cwd)?.let {
        err.println("publisher: $it")
        return EXIT_FAILURE
    }

    val manifestPath = cwd.resolve(command.manifest)
    val manifest = try {
        Files.newInputStream(manifestPath).use { CorpusManifest.parse(it) }
    } catch (e: Exception) {
        err.println("publisher: cannot read manifest $manifestPath: ${e.message ?: e.javaClass.simpleName}")
        return EXIT_FAILURE
    }

    // Load the public key before the build, so a bad path fails before any download or write.
    val publicKey = command.publicKey?.let { cwd.resolve(it) }?.let { path ->
        try {
            loadPublicKey(path)
        } catch (e: Exception) {
            err.println("publisher: cannot read public key $path: ${e.message ?: e.javaClass.simpleName}")
            return EXIT_FAILURE
        }
    }

    // Every host in the approved manifest may be fetched; nothing else may.
    val policy = HostAllowlistSourcePolicy(manifest.documents.mapNotNull { hostOf(it.sourceUri) })
    val result = try {
        BundleBuilder(connectorsFor(policy), policy).build(manifest, cwd.resolve(command.out), keyPath)
    } catch (e: Exception) {
        (e as? BundleBuildException)?.failures.orEmpty().forEach { err.println("FAILED ${it.sourceUri}: ${it.reason}") }
        err.println("publisher: build failed: ${e.message ?: e.javaClass.simpleName}")
        return EXIT_FAILURE
    }

    result.failures.forEach { err.println("FAILED ${it.sourceUri}: ${it.reason}") }

    val bundleBytes = Files.readAllBytes(result.outputDir.resolve(BUNDLE_MANIFEST_FILE))
    val signature = Files.readAllBytes(result.outputDir.resolve(BUNDLE_SIGNATURE_FILE))
    val verified = if (publicKey == null) {
        false
    } else {
        val valid = try {
            verify(publicKey, bundleBytes, signature)
        } catch (e: Exception) {
            err.println("publisher: cannot verify the bundle: ${e.message ?: e.javaClass.simpleName}")
            return EXIT_FAILURE
        }
        if (!valid) {
            err.println("publisher: bundle signature does not verify with ${command.publicKey}")
            return EXIT_FAILURE
        }
        true
    }

    out.println("Bundle version: ${result.manifest.bundleVersion}")
    out.println("Documents: ${result.manifest.documents.size}")
    out.println(if (verified) "Signature: verified" else "Signature: not verified (no --public-key given)")
    out.println("Written to: ${result.outputDir}")
    if (result.failures.isNotEmpty()) {
        err.println("publisher: ${result.failures.size} entries failed and were left out of the bundle")
        return EXIT_FAILURE
    }
    return EXIT_OK
}

/** The connectors used against the network. */
fun defaultConnectors(policy: SourcePolicy): Map<SourceType, SourceConnector> = mapOf(
    SourceType.PDF to HttpPdfConnector(policy),
    SourceType.HTML to HtmlPageConnector(policy),
)

private fun hostOf(sourceUri: String): String? = try {
    URI.create(sourceUri).host
} catch (e: IllegalArgumentException) {
    null
}
