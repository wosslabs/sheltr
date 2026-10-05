package com.sheltr.shared.bundle

import kotlin.test.Test
import kotlin.test.assertEquals

class Sha256Test {

    @Test
    fun emptyInput() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha256Hex(ByteArray(0)))
    }

    @Test
    fun abc() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex("abc".encodeToByteArray()))
    }

    @Test
    fun fiftySixBytesNeedsAnExtraPaddingBlock() {
        // 56 bytes leave no room for the length field in the first padded block. Reference: shasum -a 256.
        assertEquals("b35439a4ac6f0948b6d6f9e3c6af0f5f590ce20f1bde7090ef7970686ec6738a", sha256Hex(ByteArray(56) { 'a'.code.toByte() }))
    }

    @Test
    fun millionAs() {
        assertEquals(
            "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
            sha256Hex(ByteArray(1_000_000) { 'a'.code.toByte() }),
        )
    }
}
