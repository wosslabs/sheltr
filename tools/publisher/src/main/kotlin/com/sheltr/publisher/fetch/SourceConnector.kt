package com.sheltr.publisher.fetch

/** Fetches one source and returns its content with a checksum. */
fun interface SourceConnector {
    fun fetch(sourceUri: String): RawContent
}
