package com.sheltr.publisher

import java.nio.file.Path
import java.nio.file.Paths

/** The arguments of `publish`, as given. Relative paths are resolved by the caller. */
data class PublishArgs(
    val manifest: Path,
    val out: Path,
    val key: Path,
    val publicKey: Path?,
)

/** A malformed command line. The caller prints the message and usage, then exits 2. */
class UsageException(message: String) : RuntimeException(message)

private const val PUBLISH = "publish"
private val VALUE_FLAGS = setOf("--manifest", "--out", "--key", "--public-key")

/**
 * Parses `publish --manifest <file> --out <dir> --key <path> [--public-key <path>]`. Every flag takes exactly one
 * value. Missing, unknown, repeated or valueless arguments are a [UsageException]. Nothing is read from disk.
 */
fun parsePublish(args: List<String>): PublishArgs {
    if (args.isEmpty()) throw UsageException("missing command, expected 'publish'")
    if (args[0] != PUBLISH) throw UsageException("unknown command '${args[0]}', expected 'publish'")

    val values = mutableMapOf<String, String>()
    var index = 1
    while (index < args.size) {
        val flag = args[index]
        if (flag !in VALUE_FLAGS) throw UsageException("unknown argument '$flag'")
        if (flag in values) throw UsageException("$flag given more than once")
        val value = args.getOrNull(index + 1)
        if (value == null || value.isBlank() || value in VALUE_FLAGS) {
            throw UsageException("$flag needs a value")
        }
        values[flag] = value
        index += 2
    }

    for (required in listOf("--manifest", "--out", "--key")) {
        if (required !in values) throw UsageException("missing $required")
    }
    return PublishArgs(
        manifest = Paths.get(values.getValue("--manifest")),
        out = Paths.get(values.getValue("--out")),
        key = Paths.get(values.getValue("--key")),
        publicKey = values["--public-key"]?.let { Paths.get(it) },
    )
}
