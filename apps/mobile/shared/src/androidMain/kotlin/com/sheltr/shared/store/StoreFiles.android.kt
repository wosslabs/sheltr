package com.sheltr.shared.store

import java.io.File

actual fun atomicReplaceFile(source: String, target: String) {
    // File.renameTo is a rename(2) on Android's filesystem, which atomically replaces an existing target --
    // the same one-step guarantee the JVM actual gets from Files.move(ATOMIC_MOVE) and the iOS actual gets from
    // its own rename. No pre-delete is needed or wanted: deleting the target first would open a window where
    // neither the old nor the new file exists, which is exactly what this call must not do.
    if (!File(source).renameTo(File(target))) {
        error("rename $source -> $target failed")
    }
}

actual fun deleteFileIfExists(path: String) {
    val file = File(path)
    if (file.exists()) file.delete()
}

actual fun fileExists(path: String): Boolean = File(path).isFile
