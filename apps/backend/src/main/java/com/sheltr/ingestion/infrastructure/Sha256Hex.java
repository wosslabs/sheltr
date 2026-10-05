package com.sheltr.ingestion.infrastructure;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Lower-case hexadecimal SHA-256 of a byte array. Shared by the source connectors. */
final class Sha256Hex {

    private Sha256Hex() {
    }

    static String of(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest(bytes)) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
