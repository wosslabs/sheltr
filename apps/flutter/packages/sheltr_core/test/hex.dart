import 'dart:typed_data';

Uint8List hexToBytes(String hex) {
  final clean = hex.trim();
  if (clean.length.isOdd) {
    throw FormatException('hex length must be even');
  }
  final out = Uint8List(clean.length ~/ 2);
  for (var i = 0; i < out.length; i++) {
    out[i] = int.parse(clean.substring(i * 2, i * 2 + 2), radix: 16);
  }
  return out;
}
