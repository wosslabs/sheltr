package com.sheltr.shared.signing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SignatureVerifierTest {

    // RFC 8032 section 7.1, TEST 1: empty message.
    private val rfcPublicKey = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
    private val rfcMessage = ByteArray(0)
    private val rfcSignature = hex(
        "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
    )

    // RFC 8032 section 7.1, TEST 2: a different key pair, used as the wrong key.
    private val otherPublicKey = hex("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c")

    @Test
    fun rfc8032TestVector1Verifies() {
        assertEquals(32, rfcPublicKey.size)
        assertEquals(64, rfcSignature.size)
        assertTrue(verifySignature(rfcPublicKey, rfcMessage, rfcSignature))
    }

    @Test
    fun tamperedMessageFails() {
        assertFalse(verifySignature(rfcPublicKey, byteArrayOf(0), rfcSignature))
        assertFalse(verifySignature(rfcPublicKey, "x".encodeToByteArray(), rfcSignature))
    }

    @Test
    fun wrongKeyFails() {
        assertFalse(verifySignature(otherPublicKey, rfcMessage, rfcSignature))
        val flipped = rfcPublicKey.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        assertFalse(verifySignature(flipped, rfcMessage, rfcSignature))
    }

    @Test
    fun truncatedOrCorruptedSignatureFails() {
        assertFalse(verifySignature(rfcPublicKey, rfcMessage, rfcSignature.copyOf(63)))
        assertFalse(verifySignature(rfcPublicKey, rfcMessage, rfcSignature.copyOf(65)))
        assertFalse(verifySignature(rfcPublicKey, rfcMessage, ByteArray(0)))
        for (index in listOf(0, 31, 32, 63)) {
            val corrupted = rfcSignature.copyOf().also { it[index] = (it[index].toInt() xor 0x01).toByte() }
            assertFalse(verifySignature(rfcPublicKey, rfcMessage, corrupted), "byte $index flipped")
        }
        // S >= L (non-canonical scalar) must be rejected, not accepted or thrown.
        val highS = rfcSignature.copyOf().also { for (i in 32 until 64) it[i] = 0xFF.toByte() }
        assertFalse(verifySignature(rfcPublicKey, rfcMessage, highS))
    }

    @Test
    fun badKeyInputReturnsFalseInsteadOfThrowing() {
        assertFalse(verifySignature(ByteArray(0), rfcMessage, rfcSignature))
        assertFalse(verifySignature(rfcPublicKey.copyOf(31), rfcMessage, rfcSignature))
        assertFalse(verifySignature(rfcPublicKey.copyOf(33), rfcMessage, rfcSignature))
        // 44-byte X.509 DER is not accepted: the function takes the 32 raw bytes only.
        val der = hex("302a300506032b6570032100") + rfcPublicKey
        assertFalse(verifySignature(der, rfcMessage, rfcSignature))
        // A 32-byte value that is not a valid curve point.
        assertFalse(verifySignature(ByteArray(32) { 0xFF.toByte() }, rfcMessage, rfcSignature))
    }

    @Test
    fun publisherSignedBundleVerifies() {
        val bundle = SignedBundleFixture.BUNDLE_JSON.encodeToByteArray()
        assertTrue(verifySignature(SignedBundleFixture.PUBLIC_KEY, bundle, SignedBundleFixture.SIGNATURE))
    }

    @Test
    fun publisherSignedBundleWithOneByteChangedFails() {
        val bundle = SignedBundleFixture.BUNDLE_JSON.encodeToByteArray()
        for (index in listOf(0, bundle.size / 2, bundle.size - 1)) {
            val changed = bundle.copyOf().also { it[index] = (it[index].toInt() xor 0x01).toByte() }
            assertFalse(verifySignature(SignedBundleFixture.PUBLIC_KEY, changed, SignedBundleFixture.SIGNATURE), "byte $index changed")
        }
        // Dropping the last byte is also a change.
        assertFalse(verifySignature(SignedBundleFixture.PUBLIC_KEY, bundle.copyOf(bundle.size - 1), SignedBundleFixture.SIGNATURE))
    }

    @Test
    fun publisherSignatureDoesNotVerifyUnderAnotherKey() {
        val bundle = SignedBundleFixture.BUNDLE_JSON.encodeToByteArray()
        assertFalse(verifySignature(rfcPublicKey, bundle, SignedBundleFixture.SIGNATURE))
    }
}

internal fun hex(text: String): ByteArray {
    require(text.length % 2 == 0) { "odd hex length" }
    return ByteArray(text.length / 2) { i -> text.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
