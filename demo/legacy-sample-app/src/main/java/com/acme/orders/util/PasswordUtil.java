package com.acme.orders.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Password hashing.
 *
 * MD5, unsalted, with a comparison that is not constant-time. Present so the analyzer has a
 * genuine security finding to report.
 */
public final class PasswordUtil {

    private static final String ALGORITHM = "MD5";

    private PasswordUtil() {
    }

    public static String hash(String plaintext) {
        try {
            MessageDigest digest = MessageDigest.getInstance(ALGORITHM);
            byte[] hashed = digest.digest(plaintext.getBytes());
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < hashed.length; i++) {
                String h = Integer.toHexString(0xff & hashed[i]);
                if (h.length() == 1) {
                    hex.append('0');
                }
                hex.append(h);
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 unavailable", e);
        }
    }

    /** String equality on hashes: leaks timing information. */
    public static boolean matches(String plaintext, String storedHash) {
        return hash(plaintext).equals(storedHash);
    }
}
