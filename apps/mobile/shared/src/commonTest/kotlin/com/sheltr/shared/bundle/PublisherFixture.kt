package com.sheltr.shared.bundle

/*
 * Generated from the publisher: BundleBuilder run in a publisher test with one PDF entry (two pages),
 * a fixed clock of 2026-10-05T12:00:00Z, and the bytes it wrote. Do not hand-edit; regenerate with the publisher.
 */
internal object PublisherFixture {

    /** bundle.json exactly as written by the publisher. */
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
            "checksum": "6d5a2a06d51e0cb85ef56188c4003243e92ef9ebcc44742a5b6ab72606c1eb15",
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

    /** documents/<id>.json exactly as written by the publisher. */
    const val DOCUMENT_JSON: String = """{
    "id": "ece4699e6dbdd9f0b00437137cdb2573",
    "chunks": [
        {
            "id": "ece4699e6dbdd9f0b00437137cdb2573-0",
            "content": "Boil water for one minute before drinking.",
            "pageNumber": 1
        },
        {
            "id": "ece4699e6dbdd9f0b00437137cdb2573-1",
            "content": "Evacuate immediately if instructed by officials.",
            "pageNumber": 2
        }
    ]
}"""

    const val DOCUMENT_ID: String = "ece4699e6dbdd9f0b00437137cdb2573"
}
