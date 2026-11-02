import 'dart:typed_data';

import 'package:sheltr_core/signing/verifier.dart';
import 'package:test/test.dart';

import 'hex.dart';

// RFC 8032 section 7.1, TEST 1. Empty message.
final rfcPublicKey = hexToBytes(
  'd75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a',
);
final rfcMessage = Uint8List(0);
final rfcSignature = hexToBytes(
  'e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155'
  '5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b',
);

/// Ed25519 group order L = 2^252 + 27742317777372353535851937790883648493.
final groupOrder =
    BigInt.two.pow(252) +
    BigInt.parse('27742317777372353535851937790883648493');

Uint8List littleEndian(BigInt value, int length) {
  final out = Uint8List(length);
  var rest = value;
  for (var i = 0; i < length; i++) {
    out[i] = (rest & BigInt.from(0xff)).toInt();
    rest >>= 8;
  }
  return out;
}

BigInt littleEndianInt(Uint8List bytes) {
  var value = BigInt.zero;
  for (var i = bytes.length - 1; i >= 0; i--) {
    value = (value << 8) | BigInt.from(bytes[i]);
  }
  return value;
}

/// Replaces the scalar half of an Ed25519 signature. R stays the same.
Uint8List withScalar(Uint8List signature, BigInt scalar) {
  final out = Uint8List.fromList(signature);
  out.setRange(32, 64, littleEndian(scalar, 32));
  return out;
}

void main() {
  test('verifies RFC 8032 test vector 1', () async {
    expect(rfcPublicKey.length, 32);
    expect(rfcSignature.length, 64);
    expect(
      await verifySignature(rfcPublicKey, rfcMessage, rfcSignature),
      isTrue,
    );
  });

  test(
    'rejects a signature whose S is greater than or equal to the group order',
    () async {
      final scalar = littleEndianInt(rfcSignature.sublist(32));
      expect(scalar < groupOrder, isTrue);

      final malleable = withScalar(rfcSignature, scalar + groupOrder);
      expect(littleEndianInt(malleable.sublist(32)) >= groupOrder, isTrue);
      expect(
        await verifySignature(rfcPublicKey, rfcMessage, malleable),
        isFalse,
      );

      final atOrder = withScalar(rfcSignature, groupOrder);
      expect(await verifySignature(rfcPublicKey, rfcMessage, atOrder), isFalse);

      final highS = Uint8List.fromList(rfcSignature);
      highS.fillRange(32, 64, 0xff);
      expect(await verifySignature(rfcPublicKey, rfcMessage, highS), isFalse);
    },
  );

  test('returns false for a 33-byte key', () async {
    expect(
      await verifySignature(Uint8List(33), rfcMessage, rfcSignature),
      isFalse,
    );
  });

  test('returns false for a DER-encoded key', () async {
    // 44-byte X.509 SubjectPublicKeyInfo. The verifier takes 32 raw bytes.
    final der = Uint8List.fromList([
      ...hexToBytes('302a300506032b6570032100'),
      ...rfcPublicKey,
    ]);
    expect(der.length, 44);
    expect(await verifySignature(der, rfcMessage, rfcSignature), isFalse);
  });

  test('returns false for a tampered message, the wrong key, or a bad signature length', () async {
    expect(
      await verifySignature(
        rfcPublicKey,
        Uint8List.fromList([0]),
        rfcSignature,
      ),
      isFalse,
    );
    final otherKey = hexToBytes(
      '3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c',
    );
    expect(await verifySignature(otherKey, rfcMessage, rfcSignature), isFalse);
    expect(
      await verifySignature(
        rfcPublicKey,
        rfcMessage,
        rfcSignature.sublist(0, 63),
      ),
      isFalse,
    );
    expect(
      await verifySignature(rfcPublicKey, rfcMessage, Uint8List(65)),
      isFalse,
    );
    expect(
      await verifySignature(Uint8List(0), rfcMessage, rfcSignature),
      isFalse,
    );
    expect(
      await verifySignature(
        rfcPublicKey.sublist(0, 31),
        rfcMessage,
        rfcSignature,
      ),
      isFalse,
    );
    expect(
      await verifySignature(
        Uint8List(32)..fillRange(0, 32, 0xff),
        rfcMessage,
        rfcSignature,
      ),
      isFalse,
    );
  });
}
