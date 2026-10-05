package com.sheltr.shared.signing

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.EdDSA

/*
 * Ed25519 verification of the publisher's signature over bundle.json (RFC 8032, pure Ed25519).
 * The curve arithmetic is done by the platform through cryptography-kotlin: the JDK provider on the JVM,
 * CryptoKit on iOS. Nothing here implements Ed25519 itself.
 */

/** Length in bytes of a raw Ed25519 public key. */
const val ED25519_PUBLIC_KEY_LENGTH: Int = 32

/** Length in bytes of an Ed25519 signature, as written to bundle.json.sig. */
const val ED25519_SIGNATURE_LENGTH: Int = 64

/**
 * Returns true only if [signature] is a valid Ed25519 signature of [message] under [publicKey]
 * (32 raw bytes, not DER). A key or signature of the wrong length, a key the platform rejects,
 * or any failure in the crypto library returns false. Never throws for bad input: a false result
 * means the update must be rejected and the last good content kept.
 */
fun verifySignature(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
    if (publicKey.size != ED25519_PUBLIC_KEY_LENGTH) return false
    if (signature.size != ED25519_SIGNATURE_LENGTH) return false
    return try {
        val key = CryptographyProvider.Default
            .get(EdDSA)
            .publicKeyDecoder(EdDSA.Curve.Ed25519)
            .decodeFromByteArrayBlocking(EdDSA.PublicKey.Format.RAW, publicKey)
        key.signatureVerifier().tryVerifySignatureBlocking(message, signature)
    } catch (e: Exception) {
        // Includes provider errors for malformed keys or signatures; a failed check is never an accepted update.
        false
    }
}
