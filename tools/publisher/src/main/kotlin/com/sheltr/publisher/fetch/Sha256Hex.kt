package com.sheltr.publisher.fetch

import java.security.MessageDigest

/** Lower-case hexadecimal SHA-256 of [bytes]. */
internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
