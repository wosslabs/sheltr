package com.sheltr.shared.store

import java.io.File

actual fun atomicReplaceFile(source: String, target: String) {
    // File.renameTo is a rename(2) on Android's filesystem: it replaces an existing target in one step, same
    // guarantee as the JVM actual's Files.move(ATOMIC_MOVE). Delete target first: renameTo's overwrite behavior
    // on an existing destination is undocumented and inconsistent across devices.
    val targetFile = File(target)
    if (targetFile.exists()) targetFile.delete()
    if (!File(source).renameTo(targetFile)) {
        error("rename $source -> $target failed")
    }
}

actual fun deleteFileIfExists(path: String) {
    val file = File(path)
    if (file.exists()) file.delete()
}

actual fun fileExists(path: String): Boolean = File(path).isFile
