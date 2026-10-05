package com.sheltr.shared.signing

/*
 * Generated from the publisher: BundleBuilder (tools/publisher) built one PDF entry with a fixed clock of
 * 2026-10-05T12:00:00Z and signed bundle.json with a fresh JDK Ed25519 key (signing/Ed25519.kt). The private key
 * was discarded. PUBLIC_KEY is the 32 raw bytes after the 12-byte X.509 prefix of the publisher's public key.
 * The PDF bytes differ from the bundle package fixture, so its source checksum differs; do not hand-edit.
 */
internal object SignedBundleFixture {

    /** bundle.json exactly as written by the publisher (UTF-8, no trailing newline). */
    const val BUNDLE_JSON: String = """{
    "formatVersion": 1,
    "bundleVersion": "2026-10-05T12:00:00Z",
    "documents": [
        {
            "id": "ece4699e6dbdd9f0b00437137cdb2573",
            "title": "Flood guide",
            "agency": "Example Agency",
            "publishedDate": "2024-03-15",
            "licence": "CC-BY-4.0",
            "country": "NO",
            "hazards": [
                "FLOOD"
            ],
            "sourceUri": "https://example.org/fixture-flood-guide.pdf",
            "sourceType": "pdf",
            "checksum": "693065cef8b521bdc29721c153286533e16971e804796a6f801388b69c686a0a",
            "fileChecksum": "b0906c50e44b8cc640e935446eab4dd77b4ce64eb9c80e366d4703e990e5e795",
            "chunks": [
                {
                    "id": "ece4699e6dbdd9f0b00437137cdb2573-0",
                    "checksum": "f7ea1e73ce643c95650ae22df1570325ff7a19c53ad26b18341d7f6564bea4ce"
                },
                {
                    "id": "ece4699e6dbdd9f0b00437137cdb2573-1",
                    "checksum": "5b23dc92adaafcfd0cb308003cfe67a6d5495166bcd4409209c8c873264362fa"
                }
            ]
        }
    ]
}"""

    /** Raw 32-byte Ed25519 public key. */
    val PUBLIC_KEY: ByteArray = hex("8ca4275e552f44b20745565bba202be8505f9753d129efabd106c2a1866e61da")

    /** bundle.json.sig exactly as written by the publisher (raw 64 bytes). */
    val SIGNATURE: ByteArray = hex(
        "315566711bfeb904e340aba382fab4adc2f6a2358f6f84648e175debe09de69f9f8bfd5f9e7baec8ece2ed8dda70a34bedc3c363b22f1578939501f924094c0c",
    )
}
