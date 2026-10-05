package com.sheltr.publisher

import java.nio.file.Files
import java.nio.file.Path

/** The file whose presence marks the repository root. */
const val REPO_MARKER: String = "settings.gradle.kts"

/**
 * The directory that contains [REPO_MARKER], found by walking up from [start] (symlinks resolved). Null when no
 * ancestor has the marker, in which case there is no repository to keep a key out of.
 */
fun findRepoRoot(start: Path): Path? {
    var dir: Path? = start.toRealPath()
    while (dir != null) {
        if (Files.isRegularFile(dir.resolve(REPO_MARKER))) return dir
        dir = dir.parent
    }
    return null
}

/**
 * The absolute, normalised path of [path] with every symlink in its existing part resolved. The part that does not
 * exist yet is kept as written, so a key path can be checked before the file is created.
 */
fun realPathOf(path: Path): Path {
    val absolute = path.toAbsolutePath().normalize()
    val existing = generateSequence(absolute) { it.parent }.first { Files.exists(it) }
    return existing.toRealPath().resolve(existing.relativize(absolute)).normalize()
}

/**
 * Returns a refusal message when [key] is inside the repository that contains [cwd], and null otherwise. The key
 * path is resolved with symlinks followed, so a link from outside into the repository is refused too.
 */
fun keyInsideRepository(key: Path, cwd: Path): String? {
    val root = findRepoRoot(cwd) ?: return null
    if (!realPathOf(key).startsWith(root)) return null
    return "Refusing to read the signing key from inside the repository ($root). " +
        "Keep the private key outside the repository."
}
