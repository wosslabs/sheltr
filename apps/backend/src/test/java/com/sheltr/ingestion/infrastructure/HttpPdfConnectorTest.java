package com.sheltr.ingestion.infrastructure;

import com.sheltr.ingestion.domain.RawContent;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;

import static org.assertj.core.api.Assertions.assertThat;

class HttpPdfConnectorTest {

    private static final String CAREGIVERS_GUIDE_URL =
            "https://www.ready.gov/sites/default/files/2024-02/ready-gov_caregivers-preparedness-guide.pdf";

    @Test
    void downloadsRealPdfAndComputesCorrectChecksum() throws Exception {
        HttpPdfConnector connector = new HttpPdfConnector();

        RawContent content = connector.fetch(CAREGIVERS_GUIDE_URL);

        assertThat(content.bytes()).isNotEmpty();
        assertThat(content.bytes().length).isGreaterThan(100_000); // real PDF is ~2.5MB
        // PDF files start with the magic bytes "%PDF"
        assertThat(new String(content.bytes(), 0, 4)).isEqualTo("%PDF");

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] expectedHash = digest.digest(content.bytes());
        StringBuilder expectedHex = new StringBuilder();
        for (byte b : expectedHash) {
            expectedHex.append(String.format("%02x", b));
        }
        assertThat(content.checksum().hex()).isEqualTo(expectedHex.toString());
    }

}
