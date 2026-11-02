/// The outcome of one sync run. Only [SyncUpdated] changes the content.
sealed class SyncResult {
  const SyncResult();

  String get kind;
}

/// The published bundle is the one installed.
///
/// Nothing was downloaded beyond the manifest and signature.
final class SyncUpToDate extends SyncResult {
  const SyncUpToDate({required this.bundleVersion});

  final String bundleVersion;

  @override
  String get kind => 'upToDate';
}

/// [bundleVersion] is now active.
///
/// [documentsDownloaded] document files were fetched. The rest were reused.
final class SyncUpdated extends SyncResult {
  const SyncUpdated({
    required this.bundleVersion,
    required this.documentsDownloaded,
  });

  final String bundleVersion;
  final int documentsDownloaded;

  @override
  String get kind => 'updated';
}

/// bundle.json did not verify against the public key.
///
/// The content was not touched.
final class SyncRejectedSignature extends SyncResult {
  const SyncRejectedSignature();

  @override
  String get kind => 'rejectedSignature';
}

/// Network, format, checksum, or store error.
///
/// The previous content stays active and unchanged. This is the only result
/// that carries a [reason].
final class SyncFailed extends SyncResult {
  const SyncFailed({required this.reason});

  final String reason;

  @override
  String get kind => 'failed';
}
