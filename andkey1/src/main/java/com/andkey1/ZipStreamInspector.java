package com.andkey1;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/**
 * Walks a {@code .zip} straight from its stream — no extraction to disk — and
 * sorts every entry into models (header + hash) or text files (capped preview +
 * hash).
 *
 * <p>Guards: entry count, per-entry size, total uncompressed size, suspicious
 * compression ratio (zip bomb) and unsafe paths (zip slip). Encrypted or corrupt
 * archives are reported instead of crashing.</p>
 */
public final class ZipStreamInspector {

    public static final class Limits {
        public int maxEntries = 10_000;
        public long maxEntryBytes = StreamInspector.MAX_ENTRY_BYTES;
        public long maxTotalBytes = 4L * 1024 * 1024 * 1024;
        public long maxCompressionRatio = 200L;
    }

    public static final class Report {
        public final List<InspectedFile> files = new ArrayList<>();
        public final List<InspectedText> texts = new ArrayList<>();
        public final List<String> skipped = new ArrayList<>();
        public int entries;
    }

    public interface Progress {
        void entry(String path, int index);
    }

    private ZipStreamInspector() {
    }

    public static Report inspect(InputStream rawStream, String archiveName, Limits limits,
                                 Progress progress, StreamInspector.Cancel cancel) throws IOException {
        Limits caps = limits == null ? new Limits() : limits;
        Report report = new Report();
        ZipInputStream zis = new ZipInputStream(new BufferedInputStream(rawStream, 128 * 1024));
        long consumed = 0;
        int index = 0;
        try {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (cancel != null && cancel.isCancelled()) {
                    throw new StreamInspector.CancelledException();
                }
                index++;
                if (index > caps.maxEntries) {
                    report.skipped.add(archiveName + " — entry limit " + caps.maxEntries
                            + " reached; remaining entries were not inspected");
                    break;
                }
                String entryName = entry.getName();
                if (entry.isDirectory()) {
                    continue;
                }
                String path = archiveName + "!/" + entryName;
                if (entryName.startsWith("/") || entryName.contains("..")) {
                    report.skipped.add(path + " — unsafe path, skipped (zip-slip guard)");
                    continue;
                }
                long declared = entry.getSize();
                long compressed = entry.getCompressedSize();
                if (compressed > 0 && declared > 0
                        && declared / Math.max(compressed, 1L) > caps.maxCompressionRatio) {
                    report.skipped.add(path + " — suspicious compression ratio (zip-bomb guard)");
                    continue;
                }
                if (declared > 0 && consumed + declared > caps.maxTotalBytes) {
                    report.skipped.add(path + " — archive exceeds the " + caps.maxTotalBytes
                            + " byte total cap; remaining entries were not inspected");
                    break;
                }
                if (progress != null) {
                    progress.entry(path, index);
                }
                boolean text = StreamInspector.isTextName(entryName);
                StreamInspector.Result r = StreamInspector.inspect(
                        zis, path, caps.maxEntryBytes, text, cancel);
                consumed += r.sizeBytes;
                if (text) {
                    report.texts.add(new InspectedText(path, r.sizeBytes, r.sha256,
                            r.textPreview, r.textShownBytes, r.textTruncated));
                } else if (r.header.parseError == null) {
                    report.files.add(new InspectedFile(path, r.header, r.sizeBytes, r.sha256,
                            false, "inside " + archiveName));
                } else {
                    report.files.add(new InspectedFile(path, r.header, r.sizeBytes, r.sha256,
                            false, "inside " + archiveName + " — " + r.header.parseError));
                }
                report.entries = index;
            }
        } catch (ZipException e) {
            report.skipped.add(archiveName + " — unreadable archive entry (" + e.getMessage()
                    + "); encrypted or corrupt archives are unsupported");
        }
        return report;
    }
}
