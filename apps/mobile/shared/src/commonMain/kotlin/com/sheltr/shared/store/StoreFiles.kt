package com.sheltr.shared.store

/**
 * Replaces [target] with [source] in one step: afterwards [target] is either the old file or the complete new
 * file, never a partial one, and [source] no longer exists. Both paths must be on the same file system.
 * Throws if the replacement could not be made; [target] is then unchanged.
 */
expect fun atomicReplaceFile(source: String, target: String)

/** Deletes [path] if it exists. */
expect fun deleteFileIfExists(path: String)

/** True if a regular file exists at [path]. */
expect fun fileExists(path: String): Boolean
