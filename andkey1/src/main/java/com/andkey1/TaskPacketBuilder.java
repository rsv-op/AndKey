package com.andkey1;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Renders the single {@code TASK_PACKET.md} that an LLM (or the Windows 11 heavy
 * builder) reads to decide feasibility and emit {@code AI_BUILD_SPEC.md}.
 *
 * <p>Deterministic by construction: inputs are sorted by path, newlines are LF,
 * the timestamp comes from the caller, and every budget cut is recorded inline.
 * No Android imports — this class is covered by JVM unit tests in CI.</p>
 *
 * <p>Budgets: at most {@link #MAX_EMBED_FILES} embedded files and
 * {@link #MAX_TOTAL_TEXT_CHARS} characters of source text; anything beyond that
 * is listed (path, size, SHA-256) but not inlined.</p>
 */
public final class TaskPacketBuilder {

    public static final String PACKET_VERSION = "1";
    public static final int MAX_EMBED_FILES = 200;
    public static final int MAX_TOTAL_TEXT_CHARS = 2_000_000;

    /** Everything the packet needs; lists are copied and sorted, never mutated. */
    public static final class Input {
        public String userTask = "";
        public String appVersion = "";
        public String deviceInfo = "";
        public String target = "Windows 11 x64 heavy builder";
        public long generatedAtEpochMs;
        public final List<InspectedFile> files = new ArrayList<>();
        public final List<InspectedText> texts = new ArrayList<>();
        public final List<String> skipped = new ArrayList<>();
    }

    private TaskPacketBuilder() {
    }

    public static String build(Input in) {
        List<InspectedFile> files = new ArrayList<>(in.files);
        files.sort(Comparator.comparing(f -> f.path));
        List<InspectedText> texts = new ArrayList<>(in.texts);
        texts.sort(Comparator.comparing(t -> t.path));
        List<String> skipped = new ArrayList<>(in.skipped);
        skipped.sort(Comparator.naturalOrder());

        List<InspectedText> embedded = new ArrayList<>();
        List<InspectedText> omitted = new ArrayList<>();
        int textBudget = 0;
        for (InspectedText t : texts) {
            int len = t.preview.length();
            if (embedded.size() < MAX_EMBED_FILES && textBudget + len <= MAX_TOTAL_TEXT_CHARS) {
                embedded.add(t);
                textBudget += len;
            } else {
                omitted.add(t);
            }
        }

        StringBuilder md = new StringBuilder(96 * 1024);

        md.append("# TASK_PACKET.md\n\n");
        appendFrontMatter(md, in, files, texts, embedded, skipped);

        // ---------------------------------------------------------------- §1
        md.append("## 1. User Task / Theory\n\n");
        String task = in.userTask == null ? "" : in.userTask.trim();
        if (task.isEmpty()) {
            md.append("_(no task text was entered — ask the operator for the task)_\n\n");
        } else {
            String fence = fenceFor(task);
            md.append(fence).append("text\n").append(normalize(task)).append('\n').append(fence).append("\n\n");
        }

        // ---------------------------------------------------------------- §2
        md.append("## 2. Staged Model Inventory\n\n");
        if (files.isEmpty()) {
            md.append("_No model, archive or binary files were staged._\n\n");
        } else {
            md.append("| # | file | format | size | sha256 | header |\n");
            md.append("|---|------|--------|------|--------|--------|\n");
            int i = 0;
            for (InspectedFile f : files) {
                i++;
                md.append("| ").append(i)
                        .append(" | ").append(cell(f.path))
                        .append(" | ").append(cell(f.format()))
                        .append(" | ").append(f.sizeBytes).append(" B")
                        .append(" | ").append(f.sha256.isEmpty() ? "n/a" : "`" + f.sha256 + "`")
                        .append(" | ").append(cell(f.header.summary()))
                        .append(" |\n");
            }
            md.append('\n');

            md.append("### 2.1 Header details\n\n");
            for (InspectedFile f : files) {
                md.append("#### `").append(f.path).append("`\n\n");
                md.append("- format: `").append(f.format()).append("`\n");
                md.append("- size: ").append(f.sizeBytes).append(" bytes\n");
                md.append("- sha256: `").append(f.sha256.isEmpty() ? "n/a" : f.sha256).append("`\n");
                md.append("- copied into workspace: ").append(f.copiedToWorkspace ? "yes" : "no").append('\n');
                if (!f.note.isEmpty()) {
                    md.append("- note: ").append(f.note).append('\n');
                }
                if ("gguf".equals(f.format())) {
                    md.append("- gguf version: ").append(f.header.ggufVersion).append('\n');
                    md.append("- tensors: ").append(f.header.tensorCount).append('\n');
                    md.append("- metadata KV count: ").append(f.header.metadataKvCount).append('\n');
                    if (!f.header.metadata.isEmpty()) {
                        md.append("- metadata sample:\n");
                        for (HeaderParser.Kv kv : f.header.metadata) {
                            md.append("  - `").append(kv.key).append("` = ")
                                    .append(trim(kv.value, 200)).append('\n');
                        }
                    }
                } else if ("litert".equals(f.format())) {
                    md.append("- identifier: `").append(f.header.identifier).append("`\n");
                    md.append("- root offset: ").append(f.header.rootOffset).append('\n');
                    md.append("- model version: ").append(f.header.modelVersion).append('\n');
                    md.append("- subgraphs: ").append(f.header.subgraphCount).append('\n');
                    md.append("- operator codes: ").append(f.header.operatorCodeCount).append('\n');
                    md.append("- buffers: ").append(f.header.bufferCount).append('\n');
                    if (f.header.description != null) {
                        md.append("- description: ").append(trim(f.header.description, 300)).append('\n');
                    }
                }
                if (f.header.parseError != null) {
                    md.append("- parse error: ").append(f.header.parseError).append('\n');
                }
                if (f.header.metadataNote != null) {
                    md.append("- metadata note: ").append(f.header.metadataNote).append('\n');
                }
                md.append('\n');
            }
        }

        // ---------------------------------------------------------------- §3
        md.append("## 3. Extracted Source & Data Files\n\n");
        if (texts.isEmpty()) {
            md.append("_No readable text or source files were staged._\n\n");
        } else {
            md.append("### 3.1 File tree\n\n```text\n");
            for (InspectedText t : texts) {
                md.append(t.path).append('\n');
            }
            md.append("```\n\n");

            md.append("### 3.2 Contents\n\n");
            for (InspectedText t : embedded) {
                md.append("#### `").append(t.path).append("`\n\n");
                md.append("size ").append(t.sizeBytes).append(" B · sha256 `").append(t.sha256).append("`")
                        .append(" · shown ").append(t.shownBytes).append(" of ").append(t.sizeBytes).append(" B\n\n");
                String content = normalize(t.preview);
                String fence = fenceFor(content);
                md.append(fence).append(StreamInspector.languageFor(t.path)).append('\n');
                md.append(content);
                if (!content.endsWith("\n")) {
                    md.append('\n');
                }
                md.append(fence).append('\n');
                if (t.truncated) {
                    md.append("\n<!-- truncated: ").append(t.shownBytes).append(" of ")
                            .append(t.sizeBytes).append(" bytes shown -->\n");
                }
                md.append('\n');
            }
            if (!omitted.isEmpty()) {
                md.append("### 3.3 Not embedded (packet budget)\n\n");
                md.append("Listed for completeness — paths, sizes and hashes only "
                        + "(per-file preview cap ").append(StreamInspector.TEXT_PREVIEW_BYTES)
                        .append(" B; total budget ").append(MAX_TOTAL_TEXT_CHARS)
                        .append(" chars, ").append(MAX_EMBED_FILES).append(" files):\n\n");
                for (InspectedText t : omitted) {
                    md.append("- `").append(t.path).append("` — ").append(t.sizeBytes)
                            .append(" B · sha256 `").append(t.sha256).append("`\n");
                }
                md.append('\n');
            }
        }

        // ---------------------------------------------------------------- §4
        md.append("## 4. LLM Feasibility Prompt\n\n");
        md.append(feasibilityPrompt(in.target));

        // ------------------------------------------------------------ appendix
        if (!skipped.isEmpty()) {
            md.append("## Appendix A. Skipped / not inspected\n\n");
            for (String s : skipped) {
                md.append("- ").append(s).append('\n');
            }
            md.append('\n');
        }

        return md.toString();
    }

    private static void appendFrontMatter(StringBuilder md, Input in, List<InspectedFile> files,
                                         List<InspectedText> texts, List<InspectedText> embedded,
                                         List<String> skipped) {
        int models = 0;
        for (InspectedFile f : files) {
            if ("gguf".equals(f.format()) || "litert".equals(f.format())) {
                models++;
            }
        }
        int truncated = 0;
        for (InspectedText t : texts) {
            if (t.truncated) {
                truncated++;
            }
        }
        md.append("---\n");
        md.append("packet_version: ").append(PACKET_VERSION).append('\n');
        md.append("generated_at: ").append(iso(in.generatedAtEpochMs)).append('\n');
        md.append("app: ").append(nz(in.appVersion)).append(" (com.andkey1)\n");
        md.append("device: ").append(nz(in.deviceInfo)).append('\n');
        md.append("target: ").append(nz(in.target)).append('\n');
        md.append("counts:\n");
        md.append("  files: ").append(files.size()).append('\n');
        md.append("  model_files: ").append(models).append('\n');
        md.append("  text_files: ").append(texts.size()).append('\n');
        md.append("  embedded_text_files: ").append(embedded.size()).append('\n');
        md.append("  truncated_text_files: ").append(truncated).append('\n');
        md.append("  skipped: ").append(skipped.size()).append('\n');
        md.append("---\n\n");
    }

    private static String feasibilityPrompt(String target) {
        String t = nz(target);
        return "Evaluate whether the **User Task** in §1 can be built using only the resources "
                + "inventoried in §2 and §3.\n"
                + "\n"
                + "- Target: **" + t + "**. The device that produced this packet is an Android phone "
                + "with **no network permission**, so no downloads, hosted APIs, credentials or cloud "
                + "services may be assumed for the build inputs.\n"
                + "- Treat every `sha256` in §2/§3 as the authoritative identity of an input. Do not "
                + "invent files, hashes, model names or capabilities that are not listed.\n"
                + "- Text files in §3 may be truncated; the truncation marker and the recorded byte "
                + "counts are exact — say so if a decision needs the full file.\n"
                + "\n"
                + "Answer in exactly this shape and nothing else:\n"
                + "\n"
                + "```text\n"
                + "DECISION: BUILDABLE | UNBUILDABLE\n"
                + "```\n"
                + "\n"
                + "If **UNBUILDABLE**, follow it with one `MISSING: <resource> — <why it is required>` "
                + "line per gap, then stop.\n"
                + "\n"
                + "If **BUILDABLE**, emit a single fenced ```markdown block containing "
                + "`AI_BUILD_SPEC.md` with exactly these sections: Goal, Inputs (filename + sha256, "
                + "taken from §2/§3), Build steps (" + t + "), RAM/disk budget, Verification (one "
                + "runnable command per step), Risks and rollback.\n";
    }

    // ------------------------------------------------------------- formatting

    private static String iso(long epochMs) {
        if (epochMs <= 0L) {
            return "unknown";
        }
        return Instant.ofEpochMilli(epochMs).truncatedTo(ChronoUnit.SECONDS).toString();
    }

    private static String nz(String value) {
        return value == null || value.trim().isEmpty() ? "unknown" : value.trim();
    }

    private static String cell(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return "";
        }
        String flat = value.replace("\n", " ");
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** Fence long enough to survive backticks inside the content. */
    static String fenceFor(String content) {
        int longest = 0;
        int run = 0;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '`') {
                run++;
                if (run > longest) {
                    longest = run;
                }
            } else {
                run = 0;
            }
        }
        int n = Math.max(3, longest + 1);
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append('`');
        }
        return sb.toString();
    }

    /** Exposed for tests: the byte budget wording used in §3.3. */
    static String budgetDescription() {
        return String.format(Locale.US, "%d files / %d chars", MAX_EMBED_FILES, MAX_TOTAL_TEXT_CHARS);
    }
}
