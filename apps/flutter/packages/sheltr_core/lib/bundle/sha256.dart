import 'dart:typed_data';

import 'package:cryptography/dart.dart';

/// Lower-case hex SHA-256 of [bytes].
String sha256Hex(Uint8List bytes) {
  final digest = const DartSha256().hashSync(bytes).bytes;
  final hex = StringBuffer();
  for (final byte in digest) {
    hex.write(byte.toRadixString(16).padLeft(2, '0'));
  }
  return hex.toString();
}
