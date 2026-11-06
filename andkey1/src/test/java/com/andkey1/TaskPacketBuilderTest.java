package com.andkey1;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Pure-JVM tests for the deterministic TASK_PACKET.md renderer. */
public class TaskPacketBuilderTest {

    private static InspectedText text(String path, String preview, long size, boolean truncated) {
        return new InspectedText(path, size, StreamInspector.sha256Hex(preview.getBytes()),
                preview, preview.getBytes().length, truncated);
    }

    private static InspectedFile model(String path, String arch, long size) {
        HeaderParser.Header header = HeaderParser.parse(HeaderParserTest.ggufStub(arch, "m"), size);
        return new InspectedFile(path, header, size,
                StreamInspector.sha256Hex(HeaderParserTest.ggufStub(arch, "m") + path), false, "staged");
    }

    private static TaskPacketBuilder.Input baseInput() {
        TaskPacketBuilder.Input in = new TaskPacketBuilder.Input();
        in.userTask = "Build a Windows 11 x64 tray app that summarises the staged notes.";
        in.appVersion = "1.0.0";
        in.deviceInfo = "Test Device · Android 14 (API 34)";
        in.target = "Windows 11 x64 builder";
        in.generatedAtEpochMs = 1_700_000_000_000L;   // 2023-11-14T22:13:20Z
        return in;
    }

    @Test
    public void sectionsAppearInTheDocumentedOrder() {
        TaskPacketBuilder.Input in = baseInput();
        in.files.add(model("models/stub.gguf", "llama", 149));
        in.texts.add(text("notes/todo.md", "# todo\n", 7, false));

        String md = TaskPacketBuilder.build(in);

        assertTrue(md.startsWith("# TASK_PACKET.md"));
        int s1 = md.indexOf("## 1. User Task / Theory");
        int s2 = md.indexOf("## 2. Staged Model Inventory");
        int s3 = md.indexOf("## 3. Extracted Source & Data Files");
        int s4 = md.indexOf("## 4. LLM Feasibility Prompt");
        assertTrue(s1 > 0);
        assertTrue(s1 < s2 && s2 < s3 && s3 < s4);
        assertTrue(md.contains("packet_version: 1"));
        assertTrue(md.contains("generated_at: 2023-11-14T22:13:20Z"));
        assertTrue(md.contains("arch=llama"));
        assertTrue(md.contains("DECISION: BUILDABLE | UNBUILDABLE"));
    }

    @Test
    public void outputIsDeterministicAndSortedByPath() {
        TaskPacketBuilder.Input a = baseInput();
        a.texts.add(text("zeta.md", "z\n", 2, false));
        a.texts.add(text("alpha.md", "a\n", 2, false));
        TaskPacketBuilder.Input b = baseInput();
        b.texts.add(text("alpha.md", "a\n", 2, false));
        b.texts.add(text("zeta.md", "z\n", 2, false));

        String first = TaskPacketBuilder.build(a);
        assertEquals(first, TaskPacketBuilder.build(b));
        assertEquals(first, TaskPacketBuilder.build(a));

        assertTrue(first.indexOf("alpha.md") < first.indexOf("zeta.md"));
    }

    @Test
    public void embedsAtMostTheFileBudgetAndListsTheRemainder() {
        TaskPacketBuilder.Input in = baseInput();
        for (int i = 0; i < TaskPacketBuilder.MAX_EMBED_FILES + 5; i++) {
            in.texts.add(text(String.format("src/f%03d.txt", i), "x\n", 2, false));
        }

        String md = TaskPacketBuilder.build(in);

        assertTrue(md.contains("### 3.3 Not embedded (packet budget)"));
        int embeddedHeadings = md.split("#### `src/f", -1).length - 1;
        assertEquals(TaskPacketBuilder.MAX_EMBED_FILES, embeddedHeadings);
        // the five overflow files are still listed by path in §3.3
        assertTrue(md.contains("- `src/f200.txt`"));
        assertTrue(md.contains("- `src/f204.txt`"));
        assertTrue(md.contains("embedded_text_files: " + TaskPacketBuilder.MAX_EMBED_FILES));
    }

    @Test
    public void honoursTheCharacterBudget() {
        TaskPacketBuilder.Input in = baseInput();
        StringBuilder chunk = new StringBuilder();
        while (chunk.length() < 600_000) {
            chunk.append("0123456789");
        }
        String big = chunk.toString();
        for (int i = 0; i < 5; i++) {
            in.texts.add(text("big/f" + i + ".txt", big, big.length(), false));
        }

        String md = TaskPacketBuilder.build(in);
        assertTrue("at least one file must be omitted by the char budget",
                md.contains("### 3.3 Not embedded (packet budget)"));
        assertTrue(md.length() < TaskPacketBuilder.MAX_TOTAL_TEXT_CHARS + 200_000);
    }

    @Test
    public void marksTruncatedPreviews() {
        TaskPacketBuilder.Input in = baseInput();
        in.texts.add(text("logs/app.log", "partial\n", 131_072, true));

        String md = TaskPacketBuilder.build(in);
        assertTrue(md.contains("<!-- truncated: 8 of 131072 bytes shown -->"));
        assertTrue(md.contains("truncated_text_files: 1"));
    }

    @Test
    public void fenceWidensWhenContentContainsBackticks() {
        TaskPacketBuilder.Input in = baseInput();
        in.texts.add(text("snippet.md", "before\n```java\nint x;\n```\nafter\n", 40, false));

        String md = TaskPacketBuilder.build(in);
        assertTrue("content with ``` must be wrapped in a 4-backtick fence",
                md.contains("````markdown"));
        assertFalse(md.contains("\n```markdown\nbefore"));
    }

    @Test
    public void recordsSkippedInputsAndMissingTask() {
        TaskPacketBuilder.Input in = baseInput();
        in.userTask = "   ";
        in.skipped.add("broken.zip — ZipException: bad entry");
        List<String> expected = new ArrayList<>(in.skipped);

        String md = TaskPacketBuilder.build(in);
        assertTrue(md.contains("ask the operator for the task"));
        assertEquals(1, expected.size());
        assertTrue(md.contains("## Appendix A. Skipped / not inspected"));
        assertTrue(md.contains("bad entry"));
    }
}
