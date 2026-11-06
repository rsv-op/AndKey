package com.andkey1;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Single-pass stream inspector: hashes the whole stream, keeps the first
 * {@link HeaderParser#PREVIEW_BYTES} for header parsing and, for text files, the
 * first {@link #TEXT_PREVIEW_BYTES} decoded as UTF-8.
 *
 * <p>Because a {@code ZipInputStream} can never be rewound, size + SHA-256 +
 * header must all come from the same pass. Nothing is ever loaded whole: a
 * multi-gigabyte model contributes 4 MiB of preview and a running digest.</p>
 */
public final class StreamInspector {

    /** Per-file text preview embedded into TASK_PACKET.md. */
    public static final int TEXT_PREVIEW_BYTES = 64 * 1024;

    /** Safety cap for one archive entry (512 MiB). */
    public static final long MAX_ENTRY_BYTES = 512L * 1024 * 1024;

    /** Safety cap for a directly picked file (8 GiB) — cancellation is available. */
    public static final long MAX_FILE_BYTES = 8L * 1024 * 1024 * 1024;

    private static final int CHUNK = 64 * 1024;

    /** Extensions whose content is worth showing to an LLM. */
    private static final String[] TEXT_EXTENSIONS = {
            "md", "txt", "py", "kt", "kts", "java", "c", "h", "hpp", "cpp", "cc", "cs",
            "js", "ts", "tsx", "jsx", "json", "yaml", "yml", "toml", "xml", "gradle",
            "sh", "bat", "ps1", "csv", "ini", "cfg", "conf", "properties", "pro",
            "sql", "rs", "go", "rb", "php", "swift", "dart", "patch", "diff"
    };

    /** Cooperative cancellation, checked once per chunk. */
    public interface Cancel {
        boolean isCancelled();
    }

    /** Thrown when the user cancels a long ingest. */
    public static final class CancelledException extends IOException {
        public CancelledException() {
            super("cancelled by user");
        }
    }

    public static final class Result {
        public String name;
        public long sizeBytes;
        public String sha256;
        public HeaderParser.Header header = new HeaderParser.Header();
        public boolean textCaptured;
        public String textPreview = "";
        public long textShownBytes;
        public boolean textTruncated;
    }

    private StreamInspector() {
    }

    public static boolean isTextName(String name) {
        String ext = extensionOf(name);
        for (String candidate : TEXT_EXTENSIONS) {
            if (candidate.equals(ext)) {
                return true;
            }
        }
        return false;
    }

    public static String languageFor(String name) {
        String ext = extensionOf(name);
        switch (ext) {
            case "md":
                return "markdown";
            case "py":
                return "python";
            case "kt":
            case "kts":
                return "kotlin";
            case "java":
                return "java";
            case "c":
            case "h":
            case "hpp":
            case "cpp":
            case "cc":
                return "cpp";
            case "js":
            case "jsx":
                return "javascript";
            case "ts":
            case "tsx":
                return "typescript";
            case "json":
                return "json";
            case "yaml":
            case "yml":
                return "yaml";
            case "toml":
                return "toml";
            case "xml":
                return "xml";
            case "gradle":
                return "groovy";
            case "sh":
                return "bash";
            case "bat":
                return "bat";
            case "ps1":
                return "powershell";
            case "csv":
                return "csv";
            case "sql":
                return "sql";
            case "rs":
                return "rust";
            case "go":
                return "go";
            default:
                return "text";
        }
    }

    public static String extensionOf(String name) {
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        int dot = name.lastIndexOf('.');
        if (dot <= slash || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.US);
    }

    /**
     * Read {@code in} to the end exactly once.
     *
     * @param maxBytes     hard cap; exceeding it throws {@link IOException}
     * @param captureText  keep a UTF-8 preview (caller decides by extension)
     * @param cancel       optional cancellation hook
     */
    public static Result inspect(InputStream in, String name, long maxBytes,
                                 boolean captureText, Cancel cancel) throws IOException {
        MessageDigest digest = sha256Digest();
        ByteArrayOutputStream preview = new ByteArrayOutputStream(64 * 1024);
        ByteArrayOutputStream text = captureText ? new ByteArrayOutputStream(16 * 1024) : null;
        byte[] buf = new byte[CHUNK];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            if (cancel != null && cancel.isCancelled()) {
                throw new CancelledException();
            }
            digest.update(buf, 0, n);
            total += n;
            if (total > maxBytes) {
                throw new IOException("input exceeds the " + maxBytes + " byte cap (" + total + " read)");
            }
            int room = HeaderParser.PREVIEW_BYTES - preview.size();
            if (room > 0) {
                preview.write(buf, 0, Math.min(n, room));
            }
            if (text != null) {
                int textRoom = TEXT_PREVIEW_BYTES - text.size();
                if (textRoom > 0) {
                    text.write(buf, 0, Math.min(n, textRoom));
                }
            }
        }

        Result r = new Result();
        r.name = name;
        r.sizeBytes = total;
        r.sha256 = hex(digest.digest());
        r.header = HeaderParser.parse(preview.toByteArray(), total);
        if (text != null) {
            r.textCaptured = true;
            r.textPreview = new String(text.toByteArray(), StandardCharsets.UTF_8);
            r.textShownBytes = text.size();
            r.textTruncated = total > text.size();
        }
        return r;
    }

    public static String sha256Hex(byte[] data) {
        MessageDigest digest = sha256Digest();
        digest.update(data);
        return hex(digest.digest());
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String hex(byte[] sha) {
        StringBuilder sb = new StringBuilder(sha.length * 2);
        for (byte b : sha) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
