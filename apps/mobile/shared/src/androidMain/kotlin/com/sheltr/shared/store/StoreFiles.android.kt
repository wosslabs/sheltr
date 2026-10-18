package com.sheltr.shared.store

import java.io.File

actual fun atomicReplaceFile(source: String, target: String) {
    // File.renameTo alone is a rename(2) on Android's filesystem, same as the JVM actual's
    // Files.move(ATOMIC_MOVE) -- but only when the target does not already exist: renameTo's overwrite behavior
    // on an existing destination is undocumented and inconsistent across devices, so the target is deleted first.
    // That means this is NOT the same one-step guarantee as the JVM actual: between the delete and the rename
    // there is a real window where target exists at neither the old nor the new content. A crash in that window
    // is already handled by ContentStore (openActive treats a missing active file as "no content installed"),
    // but callers should not assume this is atomic the way the JVM/iOS actuals are.
    val targetFile = File(target)
    if (targetFile.exists()) {
        check(targetFile.delete()) { "could not delete existing $target before replacing it" }
    }
    if (!File(source).renameTo(targetFile)) {
        error("rename $source -> $target failed")
    }
}

actual fun deleteFileIfExists(path: String) {
    val file = File(path)
    if (file.exists()) file.delete()
}

actual fun fileExists(path: String): Boolean = File(path).isFile
