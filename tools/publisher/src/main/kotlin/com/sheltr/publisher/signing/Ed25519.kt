package com.sheltr.publisher.signing

import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.SignatureException
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/*
 * Ed25519 on the JDK's built-in provider (java.security, JDK 15+).
 * No third-party library is needed on the JVM. Keys are PKCS#8 (private)
 * and X.509 SubjectPublicKeyInfo (public), in DER or PEM.
 */

private const val ALGORITHM = "Ed25519"
private const val SIGNATURE_LENGTH = 64

/** Generates a new Ed25519 key pair. Used by tests and by key creation tooling. */
fun generateKeyPair(): KeyPair = KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair()

/** Signs [bytes] with [privateKey] and returns the 64-byte Ed25519 signature. */
fun sign(privateKey: PrivateKey, bytes: ByteArray): ByteArray {
    val signer = Signature.getInstance(ALGORITHM)
    signer.initSign(privateKey)
    signer.update(bytes)
    return signer.sign()
}

/**
 * Returns true only if [signature] is a valid Ed25519 signature of [bytes] under [publicKey].
 * A signature of the wrong length, or one the provider rejects as malformed, returns false
 * rather than throwing. A key of the wrong type is a caller error and throws.
 */
fun verify(publicKey: PublicKey, bytes: ByteArray, signature: ByteArray): Boolean {
    if (signature.size != SIGNATURE_LENGTH) return false
    val verifier = Signature.getInstance(ALGORITHM)
    verifier.initVerify(publicKey)
    verifier.update(bytes)
    return try {
        verifier.verify(signature)
    } catch (e: SignatureException) {
        false
    }
}

/** Loads a PKCS#8 Ed25519 private key from [path]. The caller chooses the path. */
fun loadPrivateKey(path: Path): PrivateKey {
    val der = decodeKeyFile(path, "PRIVATE KEY")
    return KeyFactory.getInstance(ALGORITHM).generatePrivate(PKCS8EncodedKeySpec(der))
}

/** Loads an X.509 Ed25519 public key from [path]. */
fun loadPublicKey(path: Path): PublicKey {
    val der = decodeKeyFile(path, "PUBLIC KEY")
    return KeyFactory.getInstance(ALGORITHM).generatePublic(X509EncodedKeySpec(der))
}

/** Returns the DER bytes of a key file: raw DER, or the base64 body of a PEM block with the given label. */
private fun decodeKeyFile(path: Path, label: String): ByteArray {
    val bytes = Files.readAllBytes(path)
    if (bytes.isEmpty() || bytes[0] != '-'.code.toByte()) return bytes
    val text = String(bytes, Charsets.US_ASCII)
    val begin = "-----BEGIN $label-----"
    val end = "-----END $label-----"
    val start = text.indexOf(begin)
    val stop = text.indexOf(end)
    require(start >= 0 && stop > start) { "$path is not a PEM '$label' block" }
    val body = text.substring(start + begin.length, stop)
    return Base64.getMimeDecoder().decode(body)
}
