import 'dart:typed_data';

import 'package:cryptography/cryptography.dart';
import 'package:cryptography/dart.dart';

/// Length in bytes of a raw Ed25519 public key.
const int ed25519PublicKeyLength = 32;

/// Length in bytes of an Ed25519 signature, as written to bundle.json.sig.
const int ed25519SignatureLength = 64;

/// Completes with true only if [signature] is a valid strict RFC 8032 Ed25519
/// signature of [message] under [publicKey] (32 raw bytes, not DER).
///
/// A key or signature of the wrong length, a non-canonical scalar (S greater
/// than or equal to the group order), or any failure in the crypto library
/// returns false. Never throws for bad input: false means the update must be
/// rejected and the last good content kept.
///
/// [DartEd25519.verify] is asynchronous, so this future completes with the
/// same true or false result.
Future<bool> verifySignature(
  Uint8List publicKey,
  Uint8List message,
  Uint8List signature,
) async {
  if (publicKey.length != ed25519PublicKeyLength) {
    return false;
  }
  if (signature.length != ed25519SignatureLength) {
    return false;
  }
  try {
    return await DartEd25519().verify(
      message,
      signature: Signature(
        signature,
        publicKey: SimplePublicKey(publicKey, type: KeyPairType.ed25519),
      ),
    );
  } catch (_) {
    return false;
  }
}
