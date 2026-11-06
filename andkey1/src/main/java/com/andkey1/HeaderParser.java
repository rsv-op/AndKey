package com.andkey1;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Zero-execution header parser for model containers, working on an in-memory
 * preview buffer (the first {@link #PREVIEW_BYTES} of a file/entry).
 *
 * <p>Deliberately free of Android imports so the same code is exercised by JVM
 * unit tests in CI and reused for both directly picked files and entries inside
 * a {@code .zip} stream (where random access is impossible).</p>
 *
 * <ul>
 *   <li><b>GGUF</b> — magic {@code GGUF}: version, tensor count, KV count and up to
 *       {@link #MAX_KVS} metadata pairs (architecture, name, quantization …).</li>
 *   <li><b>LiteRT family</b> — FlatBuffer with {@code TFL3} identifier at offset 4:
 *       root offset, model version, description, subgraph / op-code / buffer counts.</li>
 *   <li><b>ZIP</b> — {@code PK} magic, entries are left to {@link ZipStreamInspector}.</li>
 *   <li><b>unknown</b> — first 16 bytes as hex.</li>
 * </ul>
 */
public final class HeaderParser {

    /** How many bytes of a file/entry are buffered for header parsing. */
    public static final int PREVIEW_BYTES = 4 * 1024 * 1024;

    private static final int MAX_KVS = 32;
    private static final long MAX_STRING_BYTES = 1024L * 1024;
    private static final long MAX_ARRAY_ELEMENTS = 1L << 20;

    private static final String[] GGUF_TYPES = {
            "uint8", "int8", "uint16", "int16", "uint32", "int32",
            "float32", "bool", "string", "array", "uint64", "int64", "float64"
    };

    private HeaderParser() {
    }

    /** One metadata key/value pair from a GGUF header. */
    public static final class Kv {
        public final String key;
        public final String type;
        public final String value;

        public Kv(String key, String type, String value) {
            this.key = key;
            this.type = type;
            this.value = value;
        }
    }

    /** Everything the inspector learned; fields stay null when not applicable. */
    public static final class Header {
        public String format = "unknown";

        // GGUF
        public Integer ggufVersion;
        public Long tensorCount;
        public Long metadataKvCount;
        public final List<Kv> metadata = new ArrayList<>();
        public String metadataNote;

        // LiteRT / TFLite
        public String identifier;
        public Long rootOffset;
        public Integer modelVersion;
        public Integer subgraphCount;
        public Integer operatorCodeCount;
        public Integer bufferCount;
        public String description;

        // unknown
        public String headHex;

        public String parseError;

        /** First metadata value whose key starts with {@code prefix}, or null. */
        public String kvValue(String exactKey) {
            for (Kv kv : metadata) {
                if (exactKey.equals(kv.key)) {
                    return kv.value;
                }
            }
            return null;
        }

        public String architecture() {
            return kvValue("general.architecture");
        }

        public String modelName() {
            return kvValue("general.name");
        }

        public String quantization() {
            String q = kvValue("general.quantization");
            if (q == null) {
                q = kvValue("general.file_type");
            }
            return q;
        }

        /** Single-line human summary used in the TASK_PACKET inventory table. */
        public String summary() {
            StringBuilder sb = new StringBuilder();
            if ("gguf".equals(format)) {
                sb.append("v").append(ggufVersion)
                        .append(" · ").append(tensorCount).append(" tensors")
                        .append(" · ").append(metadataKvCount).append(" KV");
                if (architecture() != null) {
                    sb.append(" · arch=").append(architecture());
                }
                if (modelName() != null) {
                    sb.append(" · name=").append(modelName());
                }
                if (quantization() != null) {
                    sb.append(" · quant=").append(quantization());
                }
            } else if ("litert".equals(format)) {
                sb.append(identifier)
                        .append(" · model v").append(modelVersion)
                        .append(" · ").append(subgraphCount).append(" subgraphs")
                        .append(" · ").append(operatorCodeCount).append(" op codes")
                        .append(" · ").append(bufferCount).append(" buffers");
            } else if ("zip".equals(format)) {
                sb.append("zip archive (entries inspected individually)");
            } else {
                sb.append("unrecognised container");
                if (headHex != null) {
                    sb.append(" · head=").append(headHex);
                }
            }
            if (parseError != null) {
                sb.append(" · parse_error=").append(parseError);
            }
            return sb.toString();
        }
    }

    private static final class ParseError extends Exception {
        ParseError(String message) {
            super(message);
        }
    }

    /** Little-endian cursor over the preview buffer. */
    private static final class Cursor {
        private final byte[] b;
        private int p;

        Cursor(byte[] b, int start) {
            this.b = b;
            this.p = start;
        }

        boolean has(int n) {
            return p + n <= b.length;
        }

        long u32() throws ParseError {
            if (!has(4)) {
                throw new ParseError("buffer ended");
            }
            long v = (b[p] & 0xFFL)
                    | ((b[p + 1] & 0xFFL) << 8)
                    | ((b[p + 2] & 0xFFL) << 16)
                    | ((b[p + 3] & 0xFFL) << 24);
            p += 4;
            return v;
        }

        long u64() throws ParseError {
            if (!has(8)) {
                throw new ParseError("buffer ended");
            }
            long v = 0;
            for (int i = 7; i >= 0; i--) {
                v = (v << 8) | (b[p + i] & 0xFFL);
            }
            p += 8;
            return v;
        }

        byte[] bytes(int n) throws ParseError {
            if (n < 0 || !has(n)) {
                throw new ParseError("buffer ended");
            }
            byte[] out = new byte[n];
            System.arraycopy(b, p, out, 0, n);
            p += n;
            return out;
        }

        String utf8(long n) throws ParseError {
            if (n < 0 || n > MAX_STRING_BYTES) {
                throw new ParseError("implausible string length " + n);
            }
            return new String(bytes((int) n), StandardCharsets.UTF_8);
        }
    }

    /** Detect the container format from magic bytes only (never from the extension). */
    public static String detect(byte[] head, long sizeBytes) {
        if (sizeBytes < 4 || head.length < 4) {
            return "unknown";
        }
        if (head[0] == 'G' && head[1] == 'G' && head[2] == 'U' && head[3] == 'F') {
            return "gguf";
        }
        if (head[0] == 'P' && head[1] == 'K') {
            return "zip";
        }
        long root = u32At(head, 0);
        if (root >= 8 && root < sizeBytes) {
            return "litert";
        }
        return "unknown";
    }

    /** Parse the preview buffer of a file or archive entry. */
    public static Header parse(byte[] preview, long sizeBytes) {
        Header h = new Header();
        h.format = detect(preview, sizeBytes);
        switch (h.format) {
            case "gguf":
                parseGguf(preview, h);
                break;
            case "litert":
                parseLitert(preview, sizeBytes, h);
                break;
            case "zip":
                // Entries are walked by ZipStreamInspector; no header of its own.
                break;
            default:
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < Math.min(16, preview.length); i++) {
                    sb.append(String.format(Locale.US, "%02x", preview[i]));
                }
                h.headHex = sb.toString();
                break;
        }
        return h;
    }

    // ------------------------------------------------------------------ GGUF

    private static void parseGguf(byte[] b, Header h) {
        Cursor c = new Cursor(b, 4);
        try {
            h.ggufVersion = (int) c.u32();
            h.tensorCount = c.u64();
            h.metadataKvCount = c.u64();
            long wanted = Math.min(h.metadataKvCount, MAX_KVS);
            for (long i = 0; i < wanted; i++) {
                long klen = c.u64();
                if (klen < 0 || klen > MAX_STRING_BYTES) {
                    throw new ParseError("implausible key length " + klen);
                }
                String key = new String(c.bytes((int) klen), StandardCharsets.UTF_8);
                int vtype = (int) c.u32();
                String value = readValue(c, vtype, 0);
                h.metadata.add(new Kv(key, typeName(vtype), value));
            }
            if (h.metadataKvCount > wanted) {
                h.metadataNote = wanted + " of " + h.metadataKvCount + " KV pairs sampled";
            }
        } catch (ParseError e) {
            h.parseError = "GGUF header not fully contained in preview (" + e.getMessage() + ")";
            if (!h.metadata.isEmpty()) {
                h.metadataNote = h.metadata.size() + " KV pair(s) read before the preview cap";
            }
        }
    }

    private static String typeName(int vtype) {
        if (vtype >= 0 && vtype < GGUF_TYPES.length) {
            return GGUF_TYPES[vtype];
        }
        return "type" + vtype;
    }

    private static String readValue(Cursor c, int vtype, int depth) throws ParseError {
        if (vtype < 0 || vtype >= GGUF_TYPES.length) {
            throw new ParseError("unknown GGUF value type " + vtype);
        }
        String tn = GGUF_TYPES[vtype];
        switch (tn) {
            case "string":
                return c.utf8(c.u64());
            case "bool":
                return String.valueOf((c.bytes(1)[0] & 0xFF) != 0);
            case "array": {
                if (depth > 0) {
                    throw new ParseError("nested GGUF arrays unsupported");
                }
                int elemType = (int) c.u32();
                long count = c.u64();
                if (count < 0 || count > MAX_ARRAY_ELEMENTS) {
                    throw new ParseError("implausible array length " + count);
                }
                StringBuilder shown = new StringBuilder("[");
                for (long i = 0; i < count; i++) {
                    String value = readValue(c, elemType, depth + 1);
                    if (i < 8) {
                        if (i > 0) {
                            shown.append(", ");
                        }
                        shown.append(value);
                    }
                }
                if (count > 8) {
                    shown.append(", ... (").append(count).append(" total)");
                }
                return shown.append(']').toString();
            }
            case "uint8":
                return String.valueOf(c.bytes(1)[0] & 0xFF);
            case "int8":
                return String.valueOf(c.bytes(1)[0]);
            case "uint16": {
                byte[] x = c.bytes(2);
                return String.valueOf((x[0] & 0xFF) | ((x[1] & 0xFF) << 8));
            }
            case "int16": {
                byte[] x = c.bytes(2);
                return String.valueOf((short) ((x[0] & 0xFF) | ((x[1] & 0xFF) << 8)));
            }
            case "uint32":
                return String.valueOf(c.u32());
            case "int32":
                return String.valueOf((int) c.u32());
            case "float32":
                return String.valueOf(Float.intBitsToFloat((int) c.u32()));
            case "uint64": {
                long v = c.u64();
                return v >= 0 ? String.valueOf(v) : Long.toUnsignedString(v);
            }
            case "int64":
                return String.valueOf(c.u64());
            case "float64":
                return String.valueOf(Double.longBitsToDouble(c.u64()));
            default:
                throw new ParseError("unsupported GGUF type " + tn);
        }
    }

    // --------------------------------------------------------- LiteRT / TFLite

    private static void parseLitert(byte[] buf, long sizeBytes, Header h) {
        h.identifier = new String(buf, 4, 4, StandardCharsets.ISO_8859_1);
        long root = u32At(buf, 0);
        h.rootOffset = root;
        if (root >= sizeBytes) {
            h.parseError = "root offset " + root + " beyond file size " + sizeBytes;
            return;
        }
        if (root >= buf.length) {
            h.parseError = "root table beyond the " + (PREVIEW_BYTES / (1024 * 1024)) + " MiB preview";
            return;
        }
        int table = (int) root;
        // Model table: 0=version 1=operator_codes 2=subgraphs 3=description 4=buffers
        h.modelVersion = fbI32(buf, fbField(buf, table, 0));
        h.operatorCodeCount = fbVectorLen(buf, fbField(buf, table, 1));
        h.subgraphCount = fbVectorLen(buf, fbField(buf, table, 2));
        h.description = fbString(buf, fbField(buf, table, 3));
        h.bufferCount = fbVectorLen(buf, fbField(buf, table, 4));
        if (!"TFL3".equals(h.identifier) && h.modelVersion == null) {
            h.parseError = "no LiteRT Model fields (identifier=" + h.identifier + ")";
        }
    }

    /** Absolute position of field {@code idx} in a FlatBuffer table, or -1. */
    private static int fbField(byte[] buf, int table, int idx) {
        if (table < 0 || table + 4 > buf.length) {
            return -1;
        }
        int soff = (int) u32At(buf, table);
        int vt = table - soff;
        if (vt < 0 || vt + 4 > buf.length) {
            return -1;
        }
        int vtSize = (buf[vt] & 0xFF) | ((buf[vt + 1] & 0xFF) << 8);
        int entry = 4 + 2 * idx;
        if (entry + 2 > vtSize) {
            return -1;
        }
        int off = (buf[vt + entry] & 0xFF) | ((buf[vt + entry + 1] & 0xFF) << 8);
        if (off == 0) {
            return -1;
        }
        return table + off;
    }

    private static Integer fbI32(byte[] buf, int pos) {
        if (pos < 0 || pos + 4 > buf.length) {
            return null;
        }
        return (int) u32At(buf, pos);
    }

    private static String fbString(byte[] buf, int pos) {
        if (pos < 0 || pos + 4 > buf.length) {
            return null;
        }
        long at = pos + u32At(buf, pos);
        if (at + 4 > buf.length) {
            return null;
        }
        long len = u32At(buf, (int) at);
        if (len > 16L * 1024 * 1024 || at + 4 + len > buf.length) {
            return null;
        }
        return new String(buf, (int) (at + 4), (int) len, StandardCharsets.UTF_8);
    }

    private static Integer fbVectorLen(byte[] buf, int pos) {
        if (pos < 0 || pos + 4 > buf.length) {
            return null;
        }
        long at = pos + u32At(buf, pos);
        if (at + 4 > buf.length) {
            return null;
        }
        return (int) u32At(buf, (int) at);
    }

    private static long u32At(byte[] b, int off) {
        return (b[off] & 0xFFL)
                | ((b[off + 1] & 0xFFL) << 8)
                | ((b[off + 2] & 0xFFL) << 16)
                | ((b[off + 3] & 0xFFL) << 24);
    }
}
