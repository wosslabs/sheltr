package com.sheltr.publisher.signing

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Ed25519Test {
    @TempDir
    lateinit var dir: Path

    // RFC 8032, section 7.1, TEST 1 (empty message).
    private val rfcSeed = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
    private val rfcPublic = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
    private val rfcMessage = ByteArray(0)
    private val rfcSignature = hex(
        "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
    )

    @Test
    fun rfc8032TestVector1Signs() {
        val privateKey = KeyFactory.getInstance("Ed25519")
            .generatePrivate(PKCS8EncodedKeySpec(PKCS8_PREFIX + rfcSeed))
        assertContentEquals(rfcSignature, sign(privateKey, rfcMessage))
    }

    @Test
    fun rfc8032TestVector1Verifies() {
        val publicKey = KeyFactory.getInstance("Ed25519")
            .generatePublic(X509EncodedKeySpec(X509_PREFIX + rfcPublic))
        assertTrue(verify(publicKey, rfcMessage, rfcSignature))
    }

    @Test
    fun generatedKeysRoundTrip() {
        val pair = generateKeyPair()
        val message = "manifest bytes".toByteArray()
        assertTrue(verify(pair.public, message, sign(pair.private, message)))
    }

    @Test
    fun tamperedMessageFails() {
        val pair = generateKeyPair()
        val message = "manifest bytes".toByteArray()
        val signature = sign(pair.private, message)
        val tampered = message.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertFalse(verify(pair.public, tampered, signature))
    }

    @Test
    fun wrongPublicKeyFails() {
        val signer = generateKeyPair()
        val other = generateKeyPair()
        val message = "manifest bytes".toByteArray()
        assertFalse(verify(other.public, message, sign(signer.private, message)))
    }

    @Test
    fun truncatedSignatureReturnsFalseWithoutThrowing() {
        val pair = generateKeyPair()
        val message = "manifest bytes".toByteArray()
        val signature = sign(pair.private, message)
        assertFalse(verify(pair.public, message, signature.copyOf(signature.size - 1)))
        assertFalse(verify(pair.public, message, ByteArray(0)))
    }

    @Test
    fun corruptedSignatureFails() {
        val pair = generateKeyPair()
        val message = "manifest bytes".toByteArray()
        val signature = sign(pair.private, message)
        val corrupted = signature.copyOf().also { it[10] = (it[10] + 1).toByte() }
        assertFalse(verify(pair.public, message, corrupted))
    }

    @Test
    fun keysLoadFromPemFiles() {
        val pair = generateKeyPair()
        val privatePath = dir.resolve("signing.pem")
        val publicPath = dir.resolve("signing.pub.pem")
        Files.writeString(privatePath, pem("PRIVATE KEY", pair.private.encoded))
        Files.writeString(publicPath, pem("PUBLIC KEY", pair.public.encoded))

        val message = "manifest bytes".toByteArray()
        val signature = sign(loadPrivateKey(privatePath), message)
        assertTrue(verify(loadPublicKey(publicPath), message, signature))
    }

    @Test
    fun keysLoadFromDerFiles() {
        val pair = generateKeyPair()
        val privatePath = dir.resolve("signing.der")
        val publicPath = dir.resolve("signing.pub.der")
        Files.write(privatePath, pair.private.encoded)
        Files.write(publicPath, pair.public.encoded)

        val message = "manifest bytes".toByteArray()
        val signature = sign(loadPrivateKey(privatePath), message)
        assertTrue(verify(loadPublicKey(publicPath), message, signature))
    }

    @Test
    fun pemFileWithWrongLabelIsRejected() {
        val pair = generateKeyPair()
        val path = dir.resolve("wrong-label.pem")
        Files.writeString(path, pem("PUBLIC KEY", pair.private.encoded))
        val error = runCatching { loadPrivateKey(path) }.exceptionOrNull()
        assertEquals(IllegalArgumentException::class, error?.let { it::class })
    }

    private fun pem(label: String, der: ByteArray): String {
        val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der)
        return "-----BEGIN $label-----\n$body\n-----END $label-----\n"
    }

    private companion object {
        // Fixed DER prefixes for Ed25519 (RFC 8410): PKCS#8 wraps a 32-byte seed, X.509 wraps a 32-byte key.
        val PKCS8_PREFIX = hex("302e020100300506032b657004220420")
        val X509_PREFIX = hex("302a300506032b6570032100")

        fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
