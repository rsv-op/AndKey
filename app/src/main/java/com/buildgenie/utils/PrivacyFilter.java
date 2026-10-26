package com.buildgenie.utils;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Pattern;

/**
 * Privacy-first helpers for zero-metadata PDF export.
 * - Strips absolute paths and PII from filenames
 * - Returns a generic UTC datestamp (not device-local) to avoid fingerprinting
 */
public final class PrivacyFilter {

    private PrivacyFilter() {}

    private static final Pattern UNSAFE_CHARS = Pattern.compile("[^a-zA-Z0-9._-]");
    private static final Pattern PII_LIKE = Pattern.compile("(?i)(email|phone|token|key|secret|password)");

    /**
     * Returns a sanitized filename containing only [a-zA-Z0-9._-].
     * Strips directory components and replaces unsafe chars with "_".
     * If the name looks like PII (e.g., contains "email"), it is replaced with "dataset.csv".
     */
    public static String sanitizeFileName(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "dataset.csv";
        // Strip directories
        String base = new File(raw).getName();
        if (PII_LIKE.matcher(base).find()) {
            // Avoid leaking PII-like filenames into PDF
            String ext = "";
            int dot = base.lastIndexOf('.');
            if (dot >= 0) ext = base.substring(dot);
            return "dataset" + (ext.isEmpty() ? ".csv" : ext);
        }
        String sanitized = UNSAFE_CHARS.matcher(base).replaceAll("_");
        // Avoid empty or dot-only
        if (sanitized.isEmpty() || sanitized.equals(".") || sanitized.equals("..")) {
            return "dataset.csv";
        }
        return sanitized;
    }

    /**
     * Returns a generic UTC date stamp used in PDF filenames.
     * Fixed to "20240101" style to avoid leaking build device date/timezone.
     * If you prefer a dynamic but still coarse date, switch to yyyyMMdd with UTC.
     */
    public static String getGenericUtcDateStamp() {
        // Generic, non-fingerprinting date — change to dynamic if needed:
        // SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd", Locale.ROOT);
        // sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        // return sdf.format(new Date());
        return "20240101";
    }

    public static String getGenericUtcIsoDate() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        // For metadata-scrubbed PDFs we still use epoch; this is for UI only
        return sdf.format(new Date());
    }
}
