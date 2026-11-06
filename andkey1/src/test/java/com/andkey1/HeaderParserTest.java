package com.andkey1;

import org.junit.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Pure-JVM tests for the zero-execution header parser. */
public class HeaderParserTest {

    /** Byte-for-byte the layout ModelToolkit writes for a stub GGUF (v3, 0 tensors, 3 KVs). */
    static byte[] ggufStub(String arch, String name) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write('G');
        out.write('G');
        out.write('U');
        out.write('F');
        writeU32(out, 3);
        writeU64(out, 0);
        writeU64(out, 3);
        writeKv(out, "general.architecture", arch);
        writeKv(out, "general.name", name);
        writeKv(out, "general.quantization", "Q4_0");
        return out.toByteArray();
    }

    /** Minimal FlatBuffer with a TFL3 identifier and a Model table carrying only `version`. */
    static byte[] litertMinimal(int version) {
        byte[] b = new byte[40];
        putU32(b, 0, 24);          // root offset
        b[4] = 'T';
        b[5] = 'F';
        b[6] = 'L';
        b[7] = '3';
        putU16(b, 12, 8);          // vtable size
        putU16(b, 14, 8);          // table size
        putU16(b, 16, 4);          // field 0 (version) at table+4
        putU16(b, 18, 0);          // field 1 absent
        putU32(b, 24, 12);         // soff → vtable at 12
        putU32(b, 28, version);    // Model.version
        return b;
    }

    static void writeU32(ByteArrayOutputStream out, long v) {
        for (int i = 0; i < 4; i++) {
            out.write((int) ((v >> (8 * i)) & 0xFF));
        }
    }

    static void writeU64(ByteArrayOutputStream out, long v) {
        for (int i = 0; i < 8; i++) {
            out.write((int) ((v >> (8 * i)) & 0xFF));
        }
    }

    static void writeKv(ByteArrayOutputStream out, String key, String value) {
        byte[] k = key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] v = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writeU64(out, k.length);
        out.write(k, 0, k.length);
        writeU32(out, 8);          // type 8 = string
        writeU64(out, v.length);
        out.write(v, 0, v.length);
    }

    private static void putU32(byte[] b, int off, long v) {
        for (int i = 0; i < 4; i++) {
            b[off + i] = (byte) ((v >> (8 * i)) & 0xFF);
        }
    }

    private static void putU16(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
    }

    @Test
    public void parsesGgufMetadata() {
        byte[] stub = ggufStub("llama", "unit-test");
        HeaderParser.Header h = HeaderParser.parse(stub, stub.length);

        assertEquals("gguf", h.format);
        assertEquals(Integer.valueOf(3), h.ggufVersion);
        assertEquals(Long.valueOf(0), h.tensorCount);
        assertEquals(Long.valueOf(3), h.metadataKvCount);
        assertEquals("llama", h.architecture());
        assertEquals("unit-test", h.modelName());
        assertEquals("Q4_0", h.quantization());
        assertNull(h.parseError);
        assertTrue(h.summary().contains("arch=llama"));
    }

    @Test
    public void detectsFormatFromContentNotExtension() {
        byte[] stub = ggufStub("qwen", "mislabelled");
        assertEquals("gguf", HeaderParser.detect(stub, stub.length));

        byte[] tflite = litertMinimal(3);
        assertEquals("litert", HeaderParser.detect(tflite, tflite.length));

        assertEquals("zip", HeaderParser.detect(new byte[]{'P', 'K', 3, 4}, 100));
        assertEquals("unknown", HeaderParser.detect(new byte[]{1, 2, 3, 4}, 4));
    }

    @Test
    public void parsesLiteRtModelTable() {
        byte[] model = litertMinimal(3);
        HeaderParser.Header h = HeaderParser.parse(model, model.length);

        assertEquals("litert", h.format);
        assertEquals("TFL3", h.identifier);
        assertEquals(Long.valueOf(24), h.rootOffset);
        assertEquals(Integer.valueOf(3), h.modelVersion);
        assertNull(h.parseError);
    }

    @Test
    public void reportsUnknownContainersWithHexHead() {
        byte[] data = new byte[]{0x0A, 0x0B, 0x0C, 0x0D, 0x01, 0x02, 0x03, 0x04};
        HeaderParser.Header h = HeaderParser.parse(data, data.length);

        assertEquals("unknown", h.format);
        assertEquals("0a0b0c0d01020304", h.headHex);
    }

    @Test
    public void truncatedPreviewIsReportedNotThrown() {
        byte[] full = ggufStub("llama", "cut-short");
        byte[] cut = java.util.Arrays.copyOf(full, 40);   // inside the first KV
        HeaderParser.Header h = HeaderParser.parse(cut, full.length);

        assertEquals("gguf", h.format);
        assertEquals(Integer.valueOf(3), h.ggufVersion);
        assertTrue("expected a parse_error mentioning the preview",
                h.parseError != null && h.parseError.contains("preview"));
    }
}
