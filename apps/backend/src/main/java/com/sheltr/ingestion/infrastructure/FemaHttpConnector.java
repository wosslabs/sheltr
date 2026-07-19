package com.sheltr.ingestion.infrastructure;

import com.sheltr.ingestion.domain.RawContent;
import com.sheltr.ingestion.domain.SourceConnector;
import com.sheltr.ingestion.domain.SourceMetadata;
import com.sheltr.knowledge.domain.Checksum;
import com.sheltr.knowledge.domain.DisasterType;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;

public class FemaHttpConnector implements SourceConnector {

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Override
    public RawContent fetch(String sourceUri) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(sourceUri)).GET().build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new RuntimeException("Failed to download " + sourceUri + ": HTTP " + response.statusCode());
            }
            byte[] bytes = response.body();
            return new RawContent(bytes, new Checksum(sha256Hex(bytes)));
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to download " + sourceUri, e);
        }
    }

    @Override
    public SourceMetadata metadata() {
        return new SourceMetadata(
                "Disaster Preparedness Guide for Caregivers",
                "FEMA",
                Set.of(DisasterType.values()));
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
