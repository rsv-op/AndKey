package com.andkey1;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Pure-JVM tests for archive walking (no Android, no extraction). */
public class ZipStreamInspectorTest {

    private static byte[] zipOf(Object[]... entries) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Object[] e : entries) {
                zip.putNextEntry(new ZipEntry((String) e[0]));
                zip.write((byte[]) e[1]);
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    @Test
    public void sortsTextAndModelEntries() throws Exception {
        byte[] archive = zipOf(
                new Object[]{"docs/notes.md", "# notes\nhello world\n".getBytes(StandardCharsets.UTF_8)},
                new Object[]{"models/stub.gguf", HeaderParserTest.ggufStub("llama", "zip-stub")});

        ZipStreamInspector.Report report = ZipStreamInspector.inspect(
                new ByteArrayInputStream(archive), "pack.zip",
                new ZipStreamInspector.Limits(), null, null);

        assertEquals(1, report.texts.size());
        assertEquals(1, report.files.size());
        assertEquals("pack.zip!/docs/notes.md", report.texts.get(0).path);
        assertTrue(report.texts.get(0).preview.contains("hello world"));
        assertTrue(report.texts.get(0).truncated == false);

        InspectedFile model = report.files.get(0);
        assertEquals("pack.zip!/models/stub.gguf", model.path);
        assertEquals("gguf", model.format());
        assertEquals(Integer.valueOf(3), model.header.ggufVersion);
        assertEquals("llama", model.header.architecture());
        assertEquals(64, model.sha256.length());
        assertTrue(report.skipped.isEmpty());
    }

    @Test
    public void capsTextPreviewButKeepsFullHash() throws Exception {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            big.append("line ").append(i).append('\n');
        }
        byte[] payload = big.toString().getBytes(StandardCharsets.UTF_8);
        byte[] archive = zipOf(new Object[]{"big.txt", payload});

        ZipStreamInspector.Report report = ZipStreamInspector.inspect(
                new ByteArrayInputStream(archive), "big.zip",
                new ZipStreamInspector.Limits(), null, null);

        InspectedText text = report.texts.get(0);
        assertEquals(payload.length, text.sizeBytes);
        assertTrue(text.truncated);
        assertTrue(text.shownBytes <= StreamInspector.TEXT_PREVIEW_BYTES);
        assertEquals(StreamInspector.sha256Hex(payload), text.sha256);
    }

    @Test
    public void rejectsZipSlipPathsAndKeepsGoing() throws Exception {
        byte[] archive = zipOf(
                new Object[]{"../escape.sh", "echo pwned\n".getBytes(StandardCharsets.UTF_8)},
                new Object[]{"safe.md", "ok\n".getBytes(StandardCharsets.UTF_8)});

        ZipStreamInspector.Report report = ZipStreamInspector.inspect(
                new ByteArrayInputStream(archive), "evil.zip",
                new ZipStreamInspector.Limits(), null, null);

        assertEquals(1, report.texts.size());
        assertEquals("evil.zip!/safe.md", report.texts.get(0).path);
        assertEquals(1, report.skipped.size());
        assertTrue(report.skipped.get(0).contains("zip-slip"));
    }

    @Test
    public void stopsAtEntryLimit() throws Exception {
        byte[] archive = zipOf(
                new Object[]{"a.txt", "a".getBytes(StandardCharsets.UTF_8)},
                new Object[]{"b.txt", "b".getBytes(StandardCharsets.UTF_8)},
                new Object[]{"c.txt", "c".getBytes(StandardCharsets.UTF_8)});

        ZipStreamInspector.Limits limits = new ZipStreamInspector.Limits();
        limits.maxEntries = 2;
        ZipStreamInspector.Report report = ZipStreamInspector.inspect(
                new ByteArrayInputStream(archive), "many.zip", limits, null, null);

        assertEquals(2, report.texts.size());
        assertEquals(1, report.skipped.size());
        assertTrue(report.skipped.get(0).contains("entry limit"));
    }
}
