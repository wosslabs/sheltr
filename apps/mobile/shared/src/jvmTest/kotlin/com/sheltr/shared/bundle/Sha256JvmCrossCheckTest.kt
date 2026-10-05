package com.sheltr.shared.bundle

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Compares the pure Kotlin implementation with the JDK over every padding boundary and a range of lengths. */
class Sha256JvmCrossCheckTest {

    @Test
    fun matchesJdkMessageDigestForLengthsAroundEveryBlockBoundary() {
        val lengths = (0..300) + listOf(1000, 4096, 65_537)
        for (length in lengths) {
            val input = ByteArray(length) { ((it * 31 + length * 7) and 0xff).toByte() }
            val expected = MessageDigest.getInstance("SHA-256").digest(input)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            assertEquals(expected, sha256Hex(input), "length $length")
        }
    }
}
