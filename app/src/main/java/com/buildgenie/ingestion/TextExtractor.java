package com.buildgenie.ingestion;

import android.content.Context;
import android.net.Uri;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Offline TextExtractor — detects schema & signals without network.
 * Handles: csv/tsv, jsonl/json, yaml/yml, txt/md, pdf (via PDFBox text fallback), docx (via POI text fallback).
 * Keeps memory low for largeHeap devices (streams, not full load).
 */
public class TextExtractor {

    public static class ExtractedContent {
        public final String fileName;
        public final String extension;
        public final String detectedSchema;   // e.g., "CSV: [text,label,category] (3 cols, 2 rows)"
        public final List<String> detectedSignals; // e.g., ["csv_with_text_label","long_doc"]
        public final String previewText;      // first 2000 chars for RAG

        public ExtractedContent(String fileName, String extension, String detectedSchema, List<String> detectedSignals, String previewText) {
            this.fileName = fileName;
            this.extension = extension;
            this.detectedSchema = detectedSchema;
            this.detectedSignals = detectedSignals;
            this.previewText = previewText;
        }
    }

    public static ExtractedContent extract(Context context, File file) {
        String name = file.getName();
        String ext = getExtension(name);
        String preview = "";
        String schema = "Unknown";
        List<String> signals = new ArrayList<>();

        try {
            preview = readPreview(file, 3000);
            schema = detectSchema(name, ext, file, preview);
            signals = detectSignals(ext, schema, preview, file);
        } catch (Exception e) {
            schema = "Error reading: " + e.getMessage();
            signals.add("any_file");
        }

        return new ExtractedContent(name, ext, schema, signals, preview);
    }

    public static ExtractedContent extractFromUri(Context context, Uri uri, String displayName) {
        String ext = getExtension(displayName);
        // For SAF URIs we copy to cache then extract — simplified here:
        // In production, open InputStream via contentResolver and detect similarly.
        // Fallback: create lightweight in-memory entry
        return new ExtractedContent(displayName, ext, "SAF File: " + ext.toUpperCase(), Arrays.asList("any_file", ext + "_doc"), "");
    }

    // ---------- Detection ----------

    private static String detectSchema(String fileName, String ext, File file, String preview) throws Exception {
        switch (ext) {
            case "csv":
            case "tsv": {
                String delimiter = ext.equals("tsv") ? "\t" : ",";
                BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
                String header = br.readLine();
                br.close();
                if (header == null || header.trim().isEmpty()) return "CSV: empty file";
                String[] cols = header.split(delimiter, -1);
                // Count lines roughly
                int rows = 0;
                BufferedReader br2 = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
                while (br2.readLine() != null) rows++;
                br2.close();
                return String.format(Locale.ROOT, "CSV: %s (%d cols, %d rows)", Arrays.toString(cols), cols.length, Math.max(0, rows - 1));
            }
            case "jsonl":
            case "json": {
                // Detect prompt/completion pairs
                if (preview.contains("\"prompt\"") && preview.contains("\"completion\"")) {
                    return "JSONL: [prompt, completion] pairs — fine-tune corpus";
                }
                if (preview.trim().startsWith("{") || preview.trim().startsWith("[")) {
                    // Extract top-level keys from first object
                    int start = preview.indexOf("{");
                    int end = preview.indexOf("}", start);
                    if (start >= 0 && end > start) {
                        String snippet = preview.substring(start, Math.min(end + 1, start + 300));
                        return "JSON: keys snippet " + snippet.replaceAll("\\s+", " ");
                    }
                    return "JSON: object array";
                }
                return "JSON: unknown structure";
            }
            case "yaml":
            case "yml": {
                // Extract top-level keys like learning_rate, epochs
                StringBuilder keys = new StringBuilder();
                for (String line : preview.split("\n")) {
                    line = line.trim();
                    if (line.contains(":") && !line.startsWith("#")) {
                        String k = line.split(":", 2)[0].trim();
                        if (!k.isEmpty()) keys.append(k).append(", ");
                    }
                }
                String kStr = keys.toString().replaceAll(", $", "");
                return kStr.isEmpty() ? "YAML: config" : "YAML: [" + kStr + "]";
            }
            case "pdf":
                return "PDF: document (" + preview.length() + " chars extracted, " + countWords(preview) + " words)";
            case "docx":
                return "DOCX: office document (" + countWords(preview) + " words)";
            case "md":
                return "Markdown: " + countWords(preview) + " words, " + preview.split("\n").length + " lines";
            case "txt":
                return "Text: " + countWords(preview) + " words";
            default:
                return ext.toUpperCase() + ": " + fileName + " (" + file.length() + " bytes)";
        }
    }

    private static List<String> detectSignals(String ext, String schema, String preview, File file) {
        List<String> sigs = new ArrayList<>();
        String lowerSchema = schema.toLowerCase(Locale.ROOT);
        String lowerPreview = preview.toLowerCase(Locale.ROOT);

        // General
        sigs.add("any_file");
        sigs.add(ext + "_doc");
        if (preview.length() > 2000) sigs.add("long_doc");
        if (preview.length() > 500) sigs.add("medium_doc");

        // CSV/TSV specifics
        if (ext.equals("csv") || ext.equals("tsv")) {
            sigs.add("csv_with_text");
            if (lowerSchema.contains("text") && lowerSchema.contains("label")) {
                sigs.add("csv_with_text_label");
                sigs.add("text_column");
                sigs.add("label_column");
            }
            if (lowerSchema.contains("category") || lowerPreview.contains("category")) sigs.add("category_column");
            if (lowerSchema.contains("prompt")) sigs.add("prompt_column");
        }

        // JSONL
        if (ext.equals("jsonl") || ext.equals("json")) {
            if (lowerPreview.contains("prompt") && lowerPreview.contains("completion")) {
                sigs.add("prompt_completion_pairs");
                sigs.add("jsonl_prompts");
            }
        }

        // YAML
        if (ext.equals("yaml") || ext.equals("yml")) {
            sigs.add("yaml_config");
            if (lowerPreview.contains("learning_rate") || lowerPreview.contains("epochs")) {
                sigs.add("training_config");
            }
        }

        // PDF / doc
        if (ext.equals("pdf")) sigs.add("pdf_doc");
        if (ext.equals("docx")) sigs.add("docx_doc");
        if (ext.equals("md")) sigs.add("md_doc");

        return sigs;
    }

    // ---------- I/O Helpers ----------

    private static String readPreview(File file, int maxChars) throws Exception {
        String ext = getExtension(file.getName());
        // For binary PDF/DOCX, try real extraction if libraries present; fallback to text scan
        if (ext.equals("pdf")) {
            try {
                // Attempt PDFBox extraction if available at runtime
                return extractPdfText(file, maxChars);
            } catch (Throwable t) {
                // Fallback: read as binary and extract printable strings
            }
        }
        if (ext.equals("docx") || ext.equals("xlsx")) {
            try {
                return extractOfficeText(file, maxChars);
            } catch (Throwable t) {
                // fallback
            }
        }
        // Plain text read
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null && sb.length() < maxChars) {
                sb.append(line).append("\n");
            }
        }
        String out = sb.toString();
        return out.length() > maxChars ? out.substring(0, maxChars) : out;
    }

    private static String extractPdfText(File file, int maxChars) throws Exception {
        // Uses PDFBox-Android if present; reflection avoids hard compile dependency
        // Simplified: try direct class load
        try (InputStream is = new FileInputStream(file)) {
            // If PDFBox is on classpath, this will work
            Class<?> loaderClass = Class.forName("com.tom_roush.pdfbox.pdmodel.PDDocument");
            Object doc = loaderClass.getMethod("load", InputStream.class).invoke(null, is);
            Class<?> stripperClass = Class.forName("com.tom_roush.pdfbox.text.PDFTextStripper");
            Object stripper = stripperClass.getDeclaredConstructor().newInstance();
            String text = (String) stripperClass.getMethod("getText", loaderClass).invoke(stripper, doc);
            loaderClass.getMethod("close").invoke(doc);
            return text.length() > maxChars ? text.substring(0, maxChars) : text;
        }
    }

    private static String extractOfficeText(File file, int maxChars) throws Exception {
        // Stub — POI extraction would go here; keep lightweight for now
        return readTextFallback(file, maxChars);
    }

    private static String readTextFallback(File file, int maxChars) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null && sb.length() < maxChars) {
                // Filter non-printable
                line = line.replaceAll("[^\\x20-\\x7E]", "");
                if (!line.trim().isEmpty()) sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    private static String getExtension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static int countWords(String text) {
        if (text == null || text.trim().isEmpty()) return 0;
        return text.trim().split("\\s+").length;
    }
}
