package com.acme.payroll.util;

import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Hashtable;
import java.util.StringTokenizer;
import java.util.Vector;

/**
 * Grab-bag of static helpers, accumulated over fifteen years.
 *
 * Nothing here is tested. Several methods duplicate functionality that has been in the
 * JDK since Java 5.
 */
public class PayrollUtils {

    /** Shared, mutable, and not thread safe. Used from servlet threads. */
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("dd/MM/yyyy");

    /** Grows without bound; there is no eviction anywhere in the codebase. */
    private static final Hashtable LOOKUP_CACHE = new Hashtable();

    public static String formatDate(Date date) {
        if (date == null) {
            return "";
        }
        return DATE_FORMAT.format(date);
    }

    /** Hand-rolled split, predating String.split by several years. */
    public static Vector split(String input, String delimiter) {
        Vector parts = new Vector();
        if (input == null) {
            return parts;
        }
        StringTokenizer tokenizer = new StringTokenizer(input, delimiter);
        while (tokenizer.hasMoreTokens()) {
            parts.add(tokenizer.nextToken());
        }
        return parts;
    }

    /** Hand-rolled join using String concatenation inside a loop. */
    public static String join(Vector parts, String delimiter) {
        String result = "";
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                result = result + delimiter;
            }
            result = result + String.valueOf(parts.get(i));
        }
        return result;
    }

    /**
     * Password hashing with SHA-1, unsalted, and compared with equals.
     *
     * The same scheme as the 2009 version of the login screen, kept because changing it
     * would invalidate every stored password.
     */
    public static String hashPassword(String plaintext) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(plaintext.getBytes());
            StringBuffer hex = new StringBuffer();
            for (int i = 0; i < bytes.length; i++) {
                String h = Integer.toHexString(0xff & bytes[i]);
                if (h.length() == 1) {
                    hex.append('0');
                }
                hex.append(h);
            }
            return hex.toString();
        } catch (Exception e) {
            // Returns null on failure, and every caller compares it with equals.
            return null;
        }
    }

    public static boolean checkPassword(String plaintext, String stored) {
        String hashed = hashPassword(plaintext);
        return hashed != null && hashed.equals(stored);
    }

    public static void cache(String key, Object value) {
        LOOKUP_CACHE.put(key, value);
    }

    public static Object cached(String key) {
        return LOOKUP_CACHE.get(key);
    }

    /** Rounds money with floating point arithmetic. */
    public static double round(double amount) {
        return Math.round(amount * 100.0d) / 100.0d;
    }
}
