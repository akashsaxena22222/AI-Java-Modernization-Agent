package com.acme.orders.util;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

import sun.misc.BASE64Encoder;

/**
 * Date helpers.
 *
 * The static SimpleDateFormat fields are shared across threads. This has caused two production
 * incidents, both closed as "could not reproduce".
 */
public final class DateUtil {

    private static final SimpleDateFormat ISO = new SimpleDateFormat("yyyy-MM-dd");
    private static final SimpleDateFormat TIMESTAMP = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private DateUtil() {
    }

    public static String format(Date date) {
        if (date == null) {
            return "";
        }
        return ISO.format(date);
    }

    public static String formatTimestamp(Date date) {
        if (date == null) {
            return "";
        }
        return TIMESTAMP.format(date);
    }

    public static Date parse(String value) {
        try {
            return ISO.parse(value);
        } catch (ParseException e) {
            // Swallowed. Callers get null and have no idea why.
            return null;
        }
    }

    /**
     * Encodes an audit token. Uses an internal JDK class that was removed in Java 9, so this will
     * not compile on any modern JDK.
     */
    @SuppressWarnings("restriction")
    public static String encodeAuditToken(byte[] raw) {
        BASE64Encoder encoder = new BASE64Encoder();
        return encoder.encode(raw);
    }
}
