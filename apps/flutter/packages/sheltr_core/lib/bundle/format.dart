// The corpus bundle as the publisher writes it.
// Field names and nullability match contract/bundle-format.md.
// Every checksum is lower-case hex SHA-256.

/// Version of the bundle layout. This app reads only this version.
const int bundleFormatVersion = 2;

const String bundleManifestFile = 'bundle.json';
const String bundleSignatureFile = 'bundle.json.sig';
const String bundleDocumentsDir = 'documents';

class BundleManifest {
  const BundleManifest({
    required this.formatVersion,
    required this.sequence,
    required this.bundleVersion,
    required this.documents,
    this.cards = const [],
  });

  final int formatVersion;

  /// Strictly increasing publication counter.
  final int sequence;

  /// UTC build time, ISO-8601 to the second, for example 2026-10-05T12:00:00Z.
  final String bundleVersion;

  final List<BundleDocument> documents;

  /// Quoted action cards. Empty when the bundle has none, including old bundles
  /// that omit the field.
  final List<BundleCard> cards;
}

class BundleCard {
  const BundleCard({
    required this.id,
    required this.hazard,
    required this.phase,
    required this.country,
    required this.title,
    required this.steps,
  });

  final String id;

  /// Disaster type name.
  final String hazard;

  /// `before`, `during`, or `after`.
  final String phase;

  /// ISO 3166-1 alpha-2, or null.
  final String? country;
  final String title;
  final List<BundleCardStep> steps;
}

class BundleCardStep {
  const BundleCardStep({
    required this.text,
    required this.documentId,
    required this.chunkId,
  });

  /// Verbatim substring of the cited chunk.
  final String text;
  final String documentId;
  final String chunkId;
}

class BundleDocument {
  const BundleDocument({
    required this.id,
    required this.title,
    required this.agency,
    required this.publishedDate,
    required this.reviewedOn,
    required this.expiresOn,
    required this.licence,
    required this.country,
    required this.hazards,
    required this.sourceUri,
    required this.sourceType,
    required this.checksum,
    required this.fileChecksum,
    required this.chunks,
  });

  /// First 32 hex characters of the SHA-256 of the source URI.
  final String id;
  final String title;
  final String agency;

  /// ISO date, YYYY-MM-DD.
  final String publishedDate;

  /// ISO date, YYYY-MM-DD.
  final String reviewedOn;

  /// ISO date, YYYY-MM-DD. Past this date the document is stale; sync still installs it.
  final String expiresOn;
  final String licence;

  /// ISO 3166-1 alpha-2, or null for global guidance.
  final String? country;

  /// Disaster type names.
  final List<String> hazards;
  final String sourceUri;

  /// `pdf` or `html`.
  final String sourceType;

  /// SHA-256 of the approved PDF bytes, or of the extracted HTML text.
  final String checksum;

  /// SHA-256 of the exact bytes of `documents/<id>.json`.
  final String fileChecksum;
  final List<BundleChunkRef> chunks;
}

class BundleChunkRef {
  const BundleChunkRef({required this.id, required this.checksum});

  /// `<documentId>-<index>`, index from 0.
  final String id;

  /// SHA-256 of the chunk content in UTF-8.
  final String checksum;
}

class DocumentFile {
  const DocumentFile({required this.id, required this.chunks});

  final String id;
  final List<DocumentChunk> chunks;
}

class DocumentChunk {
  const DocumentChunk({
    required this.id,
    required this.content,
    required this.pageNumber,
  });

  final String id;
  final String content;

  /// 1-based PDF page, or null for HTML.
  final int? pageNumber;
}
