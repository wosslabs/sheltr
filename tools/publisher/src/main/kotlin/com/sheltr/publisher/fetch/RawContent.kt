package com.sheltr.publisher.fetch

/** The bytes produced for one source, with the SHA-256 (lower-case hex) of those bytes. */
class RawContent(bytes: ByteArray, val checksum: Checksum) {

    private val data: ByteArray = bytes.copyOf()

    val bytes: ByteArray
        get() = data.copyOf()
}

data class Checksum(val hex: String) {
    init {
        require(hex.isNotBlank()) { "Checksum hex must not be blank" }
    }
}
