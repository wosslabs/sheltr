package com.sheltr.shared.store

import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

actual fun atomicReplaceFile(source: String, target: String) {
    // ATOMIC_MOVE is a rename(2) on POSIX file systems: it replaces an existing target in one step.
    // It throws AtomicMoveNotSupportedException rather than falling back to copy-and-delete.
    Files.move(Paths.get(source), Paths.get(target), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}

actual fun deleteFileIfExists(path: String) {
    Files.deleteIfExists(Paths.get(path))
}

actual fun fileExists(path: String): Boolean = Files.isRegularFile(Paths.get(path))
