package com.sheltr.ingestion.domain;

import com.sheltr.knowledge.domain.Checksum;

import java.util.Arrays;

public record RawContent(byte[] bytes, Checksum checksum) {
    public RawContent {
        bytes = bytes == null ? new byte[0] : Arrays.copyOf(bytes, bytes.length);
    }

    @Override
    public byte[] bytes() {
        return Arrays.copyOf(bytes, bytes.length);
    }
}
