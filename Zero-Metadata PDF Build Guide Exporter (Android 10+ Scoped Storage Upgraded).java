package com.buildgenie.utils;

import android.content.Context;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import com.buildgenie.ingestion.TextExtractor.ExtractedContent;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

public class PdfExporter {

    public static String generateBuildGuidePdf(Context context, String buildId, String templateAssetPath, List<ExtractedContent> files) throws Exception {
        // CRITICAL UPGRADE: Initialize PDFBox font/cmap resources before creating documents on Android
        PDFBoxResourceLoader.init(context.getApplicationContext());

        String rawTemplate = loadAssetText(context, templateAssetPath);
        String populatedMarkdown = fillPlaceholders(rawTemplate, files);

        String genericDate = PrivacyFilter.getGenericUtcDateStamp();
        String fileName = "build_" + buildId + "_" + genericDate + ".pdf";

        try (PDDocument document = new PDDocument()) {
            // Explicitly scrub all PDF metadata to prevent device/author fingerprinting
            PDDocumentInformation info = new PDDocumentInformation();
            info.setAuthor("");
            info.setCreator("");
            info.setProducer("");
            info.setTitle("Build Guide: " + buildId);
            Calendar epochCal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            epochCal.setTimeInMillis(0);
            info.setCreationDate(epochCal);
            info.setModificationDate(epochCal);
            document.setDocumentInformation(info);

            renderMarkdownLinesToPdf(document, populatedMarkdown);

            // CRITICAL UPGRADE: Use MediaStore on Android 10+ (API 29+) to write to /Download/BuildGenie/ without legacy storage permissions
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentResolver resolver = context.getContentResolver();
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                values.put(MediaStore.Downloads.MIME_TYPE, "application/pdf");
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/BuildGenie");

                Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    try (OutputStream out = resolver.openOutputStream(uri)) {
                        if (out != null) {
                            document.save(out);
                        }
                    }
                    return "/Download/BuildGenie/" + fileName;
                }
            }

            // Fallback for Android 8.0 - 9.0 (API 26 - 28)
            File outDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "BuildGenie");
            if (!outDir.exists()) outDir.mkdirs();
            File outFile = new File(outDir, fileName);
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                document.save(fos);
            }
            return outFile.getAbsolutePath();
        }
    }

    private static String fillPlaceholders(String template, List<ExtractedContent> files) {
        StringBuilder fileList = new StringBuilder();
        StringBuilder schemas = new StringBuilder();
        String primaryCsv = "dataset.csv";

        for (ExtractedContent f : files) {
            String safeName = PrivacyFilter.sanitizeFileName(f.fileName);
            fileList.append("- ./").append(safeName).append(" (").append(f.extension.toUpperCase()).append(")\n");
            schemas.append("- ").append(safeName).append(": ").append(f.detectedSchema).append("\n");
            if (f.extension.equals("csv")) {
                primaryCsv = safeName;
            }
        }

        return template
                .replace("{{file_list}}", fileList.toString().trim())
                .replace("{{schema}}", schemas.toString().trim())
                .replace("{{primary_csv}}", primaryCsv)
                .replace("{{expected_columns}}", "['text', 'label']");
    }

    private static void renderMarkdownLinesToPdf(PDDocument doc, String markdown) throws Exception {
        String[] lines = markdown.split("\n");
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        PDPageContentStream stream = new PDPageContentStream(doc, page);

        float y = 780f;
        final float margin = 50f;
        final float lineHeight = 15f;

        stream.beginText();
        stream.newLineAtOffset(margin, y);

        for (String line : lines) {
            if (y < 60f) {
                stream.endText();
                stream.close();
                page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                stream = new PDPageContentStream(doc, page);
                y = 780f;
                stream.beginText();
                stream.newLineAtOffset(margin, y);
            }

            if (line.startsWith("# ")) {
                stream.setFont(PDType1Font.HELVETICA_BOLD, 16);
                stream.showText(cleanPdfText(line.substring(2)));
            } else if (line.startsWith("## ") || line.startsWith("### ")) {
                stream.setFont(PDType1Font.HELVETICA_BOLD, 12);
                stream.showText(cleanPdfText(line.replaceFirst("^#+\\s*", "")));
            } else if (line.startsWith("```")) {
                continue; // Skip code fence markers in rendered PDF
            } else {
                stream.setFont(PDType1Font.COURIER, 10);
                stream.showText(cleanPdfText(line));
            }

            stream.newLineAtOffset(0, -lineHeight);
            y -= lineHeight;
        }

        stream.endText();
        stream.close();
    }

    private static String cleanPdfText(String input) {
        return input.replaceAll("[^\\x20-\\x7E]", "");
    }

    private static String loadAssetText(Context context, String assetPath) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (InputStream is = context.getAssets().open(assetPath);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }
}