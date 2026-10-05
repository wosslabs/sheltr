package com.sheltr.shared.bundle

/*
 * Pure Kotlin SHA-256 (FIPS 180-4). No platform dependency, so the same code runs on JVM, iOS and Android.
 * Verified against the standard vectors in Sha256Test and against java.security.MessageDigest in jvmTest.
 */
internal object Sha256 {

    // Constants are Long so the values above Int.MAX_VALUE need no sign conversion in the source.
    private val K: LongArray = longArrayOf(
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
    )

    private val INITIAL: LongArray = longArrayOf(
        0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
    )

    /** The 32-byte digest of [message]. */
    fun digest(message: ByteArray): ByteArray {
        val state = IntArray(8) { INITIAL[it].toInt() }
        val w = IntArray(64)

        val fullBlocks = message.size / 64
        for (block in 0 until fullBlocks) {
            compress(state, w, message, block * 64)
        }

        // Padding: 0x80, zeros, then the 64-bit big-endian message length in bits. The tail is one block when the
        // remaining bytes leave room for the 0x80 marker and the 8 length bytes (<= 55), otherwise two.
        val rest = message.size - fullBlocks * 64
        val tailLength = if (rest < 56) 64 else 128
        val tail = ByteArray(tailLength)
        message.copyInto(tail, 0, fullBlocks * 64, message.size)
        tail[rest] = 0x80.toByte()
        val bitLength = message.size.toLong() * 8
        for (i in 0 until 8) {
            tail[tailLength - 1 - i] = (bitLength ushr (8 * i)).toByte()
        }
        compress(state, w, tail, 0)
        if (tailLength == 128) {
            compress(state, w, tail, 64)
        }

        val out = ByteArray(32)
        for (i in 0 until 8) {
            val v = state[i]
            out[4 * i] = (v ushr 24).toByte()
            out[4 * i + 1] = (v ushr 16).toByte()
            out[4 * i + 2] = (v ushr 8).toByte()
            out[4 * i + 3] = v.toByte()
        }
        return out
    }

    private fun compress(state: IntArray, w: IntArray, data: ByteArray, offset: Int) {
        for (i in 0 until 16) {
            val p = offset + 4 * i
            w[i] = ((data[p].toInt() and 0xff) shl 24) or
                ((data[p + 1].toInt() and 0xff) shl 16) or
                ((data[p + 2].toInt() and 0xff) shl 8) or
                (data[p + 3].toInt() and 0xff)
        }
        for (i in 16 until 64) {
            val x = w[i - 15]
            val y = w[i - 2]
            val s0 = x.rotateRight(7) xor x.rotateRight(18) xor (x ushr 3)
            val s1 = y.rotateRight(17) xor y.rotateRight(19) xor (y ushr 10)
            w[i] = w[i - 16] + s0 + w[i - 7] + s1
        }

        var a = state[0]
        var b = state[1]
        var c = state[2]
        var d = state[3]
        var e = state[4]
        var f = state[5]
        var g = state[6]
        var h = state[7]

        for (i in 0 until 64) {
            val bigS1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
            val ch = (e and f) xor (e.inv() and g)
            val t1 = h + bigS1 + ch + K[i].toInt() + w[i]
            val bigS0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
            val maj = (a and b) xor (a and c) xor (b and c)
            val t2 = bigS0 + maj
            h = g
            g = f
            f = e
            e = d + t1
            d = c
            c = b
            b = a
            a = t1 + t2
        }

        state[0] += a
        state[1] += b
        state[2] += c
        state[3] += d
        state[4] += e
        state[5] += f
        state[6] += g
        state[7] += h
    }
}

private const val HEX_DIGITS = "0123456789abcdef"

/** Lower-case hexadecimal SHA-256 of [bytes], the checksum form used by the bundle format. */
fun sha256Hex(bytes: ByteArray): String {
    val digest = Sha256.digest(bytes)
    val chars = CharArray(digest.size * 2)
    for (i in digest.indices) {
        val v = digest[i].toInt() and 0xff
        chars[2 * i] = HEX_DIGITS[v ushr 4]
        chars[2 * i + 1] = HEX_DIGITS[v and 0x0f]
    }
    return chars.concatToString()
}
