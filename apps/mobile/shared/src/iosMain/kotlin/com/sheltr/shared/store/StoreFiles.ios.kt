package com.sheltr.shared.store

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.ENOENT
import platform.posix.F_OK
import platform.posix.access
import platform.posix.errno
import platform.posix.rename
import platform.posix.strerror
import platform.posix.unlink
import kotlinx.cinterop.toKString

// UNTESTED: compiled for iosArm64 and iosSimulatorArm64 but no test runs these actuals yet (M4 runs jvmTest only).
// rename(2) replaces an existing target atomically on the same file system, as on the JVM.

@OptIn(ExperimentalForeignApi::class)
actual fun atomicReplaceFile(source: String, target: String) {
    if (rename(source, target) != 0) {
        error("rename $source -> $target failed: ${strerror(errno)?.toKString()}")
    }
}

@OptIn(ExperimentalForeignApi::class)
actual fun deleteFileIfExists(path: String) {
    if (unlink(path) != 0 && errno != ENOENT) {
        error("unlink $path failed: ${strerror(errno)?.toKString()}")
    }
}

actual fun fileExists(path: String): Boolean = access(path, F_OK) == 0
