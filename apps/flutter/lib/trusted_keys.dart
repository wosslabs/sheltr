import 'package:sheltr_core/sheltr_core.dart';

/// Publisher keys compiled into this build. Empty until a release sets them.
///
/// At most two entries, each `{ id, publicKeyHex, notAfter }`. `id` is the
/// lower-case SHA-256 of the raw 32-byte public key, which the bundle names
/// in `signedBy`. See `docs/publishing.md`.
const compiledTrustedKeys = <TrustedKey>[];
