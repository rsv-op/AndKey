package com.andkey1;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.provider.OpenableColumns;
import android.database.Cursor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AndKey1 — the offline, on-device companion to
 * {@code TERMUX_PYTHON_WINDOWS_GUIDE.md} and {@code tools/model_toolkit.py}.
 *
 * <p>The guide and the Python tool are compiled into the APK as assets, and the
 * four functions they describe are implemented natively so they work on a phone
 * that has neither Termux nor a PC:</p>
 *
 * <ol>
 *   <li>Load — pick any file, get size + sha256 + detected format</li>
 *   <li>Inspect — .gguf / .litert / .ablitert / .tflite headers, by content</li>
 *   <li>Generate — report.json, manifest.json, a valid 149-byte stub.gguf</li>
 *   <li>Replace — atomic swap with .bak-&lt;timestamp&gt; backup, hash verify, restore</li>
 * </ol>
 */
public class MainActivity extends Activity {

    private static final int REQ_LOAD = 101;
    private static final int REQ_INSPECT = 102;
    private static final int REQ_REPORT = 103;
    private static final int REQ_SOURCE = 104;
    private static final int REQ_EXPORT = 105;

    private static final String GUIDE_ASSET = "TERMUX_PYTHON_WINDOWS_GUIDE.md";
    private static final String PYTHON_ASSET = "model_toolkit.py";

    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<String, View> pages = new LinkedHashMap<>();
    private final Map<String, Button> navButtons = new LinkedHashMap<>();

    private FrameLayout container;
    private TextView statusBar;
    private File workspace;
    private File scratch;

    private TextView loadOut;
    private TextView inspectOut;
    private TextView generateOut;
    private TextView replaceOut;
    private TextView workspaceListGenerate;
    private TextView workspaceListReplace;
    private EditText maxKvsField;
    private EditText stubNameField;
    private EditText stubArchField;
    private EditText targetNameField;
    private CheckBox forceBox;

    private volatile String replaceSourceName;
    private volatile File lastGenerated;

    private interface Op {
        ModelToolkit.Result run() throws Exception;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        File base = getExternalFilesDir(null);
        if (base == null) {
            base = getFilesDir();
        }
        workspace = new File(base, "workspace");
        scratch = new File(getCacheDir(), "picked");
        //noinspection ResultOfMethodCallIgnored
        workspace.mkdirs();
        //noinspection ResultOfMethodCallIgnored
        scratch.mkdirs();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        // ---------------------------------------------------------------- header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(14), dp(12), dp(14), dp(8));
        header.setBackgroundColor(0xFF101820);

        TextView title = new TextView(this);
        title.setText("AndKey1");
        title.setTextColor(Color.WHITE);
        title.setTextSize(19f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Guide + model_toolkit, on-device · zero network permission");
        subtitle.setTextColor(0xFF9FB3C8);
        subtitle.setTextSize(12f);
        header.addView(subtitle);
        root.addView(header);

        // ------------------------------------------------------------------- nav
        HorizontalScrollView navScroll = new HorizontalScrollView(this);
        navScroll.setHorizontalScrollBarEnabled(false);
        navScroll.setBackgroundColor(0xFF17242B);
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setPadding(dp(8), dp(6), dp(8), dp(6));
        navScroll.addView(nav);
        root.addView(navScroll);

        addNav(nav, "guide", "Guide");
        addNav(nav, "load", "① Load");
        addNav(nav, "inspect", "② Inspect");
        addNav(nav, "generate", "③ Generate");
        addNav(nav, "replace", "④ Replace");
        addNav(nav, "python", "Python tool");
        addNav(nav, "about", "About");

        // ---------------------------------------------------------------- status
        statusBar = new TextView(this);
        statusBar.setText("Ready · workspace: " + workspace.getAbsolutePath());
        statusBar.setTextSize(11f);
        statusBar.setPadding(dp(14), dp(6), dp(14), dp(6));
        statusBar.setBackgroundColor(0xFFE8F0FE);
        statusBar.setTextColor(0xFF1B3A57);
        root.addView(statusBar);

        // --------------------------------------------------------------- content
        container = new FrameLayout(this);
        root.addView(container, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        pages.put("guide", buildGuidePage());
        pages.put("load", buildLoadPage());
        pages.put("inspect", buildInspectPage());
        pages.put("generate", buildGeneratePage());
        pages.put("replace", buildReplacePage());
        pages.put("python", buildPythonPage());
        pages.put("about", buildAboutPage());

        show("guide");
    }

    // ======================================================================
    // Navigation & shared UI helpers
    // ======================================================================

    private void addNav(LinearLayout nav, final String key, String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(12f);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setPadding(dp(10), dp(4), dp(10), dp(4));
        b.setOnClickListener(v -> show(key));
        nav.addView(b, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        navButtons.put(key, b);
    }

    private void show(String key) {
        View page = pages.get(key);
        if (page == null) {
            return;
        }
        container.removeAllViews();
        container.addView(page);
        for (Map.Entry<String, Button> e : navButtons.entrySet()) {
            boolean active = e.getKey().equals(key);
            e.getValue().setTextColor(active ? 0xFF7FD1FF : Color.WHITE);
            e.getValue().setTypeface(active ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private LinearLayout column() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), dp(12), dp(14), dp(24));
        return col;
    }

    private ScrollView scrollPage(LinearLayout col) {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.addView(col);
        return sv;
    }

    private TextView heading(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(16f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, 0, 0, dp(6));
        return tv;
    }

    private TextView body(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13f);
        tv.setLineSpacing(dp(2), 1f);
        tv.setPadding(0, 0, 0, dp(10));
        return tv;
    }

    private TextView console(String initial) {
        TextView tv = new TextView(this);
        tv.setText(initial);
        tv.setTextSize(11f);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(10), dp(10), dp(10), dp(10));
        tv.setBackgroundColor(0xFF101820);
        tv.setTextColor(0xFFB9F6CA);
        tv.setLineSpacing(dp(2), 1f);
        return tv;
    }

    private Button action(String label, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(13f);
        b.setOnClickListener(click);
        return b;
    }

    private LinearLayout.LayoutParams wide() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void status(String text) {
        statusBar.setText(text);
    }

    private void statusAsync(String text) {
        ui.post(() -> status(text));
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    // ======================================================================
    // Pages
    // ======================================================================

    private View buildGuidePage() {
        LinearLayout col = column();
        col.addView(heading("TERMUX_PYTHON_WINDOWS_GUIDE.md"));
        col.addView(body("Bundled verbatim from the repository — the same file you would open on "
                + "GitHub. Termux, Python and Windows Shell steps for the four core functions "
                + "(Load · Inspect · Generate · Replace)."));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final String md = readAsset(GUIDE_ASSET);
        row.addView(action("Copy Markdown", v -> copyToClipboard("AndKey1 guide", md)),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(action("Save to workspace", v -> saveToWorkspace(GUIDE_ASSET, md)),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(row, wide());
        col.addView(spacer(8));

        TextView viewer = new TextView(this);
        viewer.setText(MarkdownRenderer.render(md));
        viewer.setTextSize(13f);
        viewer.setTextIsSelectable(true);
        viewer.setLineSpacing(dp(3), 1f);
        col.addView(viewer);
        return scrollPage(col);
    }

    private View buildLoadPage() {
        LinearLayout col = column();
        col.addView(heading("① Load — validate a file's identity"));
        col.addView(body("Picks any file, copies it into the app's private scratch area, then "
                + "reports size, SHA-256 and the format detected from magic bytes "
                + "(gguf / litert / zip / unknown). Equivalent to:\n"
                + "python model_toolkit.py load <PATH>"));

        loadOut = console("LOAD — no file picked yet.");
        col.addView(action("Pick a file & run LOAD", v -> pickFile(REQ_LOAD, "*/*")), wide());
        col.addView(spacer(8));
        col.addView(loadOut, wide());
        return scrollPage(col);
    }

    private View buildInspectPage() {
        LinearLayout col = column();
        col.addView(heading("② Inspect — .gguf / .litert / .ablitert"));
        col.addView(body("Parses model headers without executing them. GGUF: magic, version, tensor "
                + "count, metadata KV pairs. LiteRT family: FlatBuffer root offset, identifier "
                + "(TFL3), model version, description, subgraph / op-code / buffer counts. "
                + "Detection is by content, not extension."));

        LinearLayout kvsRow = new LinearLayout(this);
        kvsRow.setOrientation(LinearLayout.HORIZONTAL);
        kvsRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = new TextView(this);
        label.setText("GGUF metadata pairs to sample: ");
        label.setTextSize(13f);
        kvsRow.addView(label);
        maxKvsField = new EditText(this);
        maxKvsField.setText("32");
        maxKvsField.setInputType(InputType.TYPE_CLASS_NUMBER);
        maxKvsField.setTextSize(13f);
        kvsRow.addView(maxKvsField, new LinearLayout.LayoutParams(dp(72),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(kvsRow, wide());
        col.addView(spacer(6));

        inspectOut = console("INSPECT — no file picked yet.");
        col.addView(action("Pick a model file & INSPECT", v -> pickFile(REQ_INSPECT, "*/*")), wide());
        col.addView(spacer(8));
        col.addView(inspectOut, wide());
        return scrollPage(col);
    }

    private View buildGeneratePage() {
        LinearLayout col = column();
        col.addView(heading("③ Generate — reports, manifests, stub models"));
        col.addView(body("All outputs are written into the workspace folder listed in the status "
                + "bar, with fsync + atomic rename, exactly like the Python generator."));

        col.addView(action("Report from a model file…", v -> pickFile(REQ_REPORT, "*/*")), wide());

        workspaceListGenerate = console("(workspace listing)");

        LinearLayout stubRow = new LinearLayout(this);
        stubRow.setOrientation(LinearLayout.HORIZONTAL);
        stubNameField = new EditText(this);
        stubNameField.setText("app-stub");
        stubNameField.setTextSize(13f);
        stubNameField.setHint("name");
        stubArchField = new EditText(this);
        stubArchField.setText("llama");
        stubArchField.setTextSize(13f);
        stubArchField.setHint("arch");
        stubRow.addView(stubNameField, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        stubRow.addView(stubArchField, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(stubRow, wide());
        col.addView(action("Stub GGUF (self-verified) → workspace/stub.gguf",
                v -> generateStub()), wide());
        col.addView(action("Manifest of the workspace folder → manifest.json",
                v -> generateManifest()), wide());
        col.addView(action("Export last generated file to Downloads…",
                v -> exportLastGenerated()), wide());

        generateOut = console("GENERATE — nothing generated yet.");
        col.addView(spacer(8));
        col.addView(generateOut, wide());
        col.addView(spacer(10));
        col.addView(heading("Workspace"));
        col.addView(workspaceListGenerate, wide());
        return scrollPage(col);
    }

    private View buildReplacePage() {
        LinearLayout col = column();
        col.addView(heading("④ Replace — atomic swap with rollback"));
        col.addView(body("Pick the replacement SOURCE file (it is copied into the workspace), type "
                + "the workspace file it should replace, then run REPLACE. The old file is copied "
                + "to <name>.bak-<timestamp> first; both sides are re-hashed and the swap only "
                + "counts as successful when the hashes match. Format mismatches are refused "
                + "unless 'force' is ticked, and RESTORE rolls the newest backup back."));

        targetNameField = new EditText(this);
        targetNameField.setText("model.gguf");
        targetNameField.setTextSize(13f);
        targetNameField.setHint("target file name inside the workspace");
        col.addView(targetNameField, wide());

        forceBox = new CheckBox(this);
        forceBox.setText("force (skip format / parse guards)");
        forceBox.setTextSize(13f);
        col.addView(forceBox);

        col.addView(action("Pick replacement SOURCE file…", v -> pickFile(REQ_SOURCE, "*/*")), wide());
        col.addView(action("Run REPLACE  (target ← source)", v -> runReplace()), wide());
        col.addView(action("Restore newest .bak of target", v -> runRestore()), wide());

        replaceOut = console("REPLACE — nothing done yet.");
        workspaceListReplace = console("(workspace listing)");
        col.addView(spacer(8));
        col.addView(replaceOut, wide());
        col.addView(spacer(10));
        col.addView(heading("Workspace"));
        col.addView(workspaceListReplace, wide());
        refreshLists();
        return scrollPage(col);
    }

    private View buildPythonPage() {
        LinearLayout col = column();
        col.addView(heading("tools/model_toolkit.py"));
        col.addView(body("The Python toolkit, bundled verbatim. Use it in Termux or Windows as the "
                + "guide describes; everything here is the on-device equivalent for when neither "
                + "is available."));

        final String src = readAsset(PYTHON_ASSET);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(action("Copy source", v -> copyToClipboard("model_toolkit.py", src)),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(action("Save to workspace", v -> saveToWorkspace(PYTHON_ASSET, src)),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(row, wide());

        TextView code = new TextView(this);
        code.setText(src);
        code.setTextSize(10f);
        code.setTypeface(Typeface.MONOSPACE);
        code.setTextIsSelectable(true);
        code.setBackgroundColor(0xFFF6F8FA);
        code.setPadding(dp(8), dp(8), dp(8), dp(8));
        col.addView(spacer(8));
        col.addView(code, wide());
        return scrollPage(col);
    }

    private View buildAboutPage() {
        LinearLayout col = column();
        col.addView(heading("About AndKey1"));
        col.addView(body(
                "AndKey1 " + ModelToolkit.TOOL_VERSION + " packages:\n"
                        + "• TERMUX_PYTHON_WINDOWS_GUIDE.md — the operating guide, rendered in-app\n"
                        + "• model_toolkit.py — the Python toolkit, verbatim (copy it out to Termux)\n"
                        + "• A native port of the four functions: Load · Inspect · Generate · Replace\n\n"
                        + "Both files live inside this app's own assets (andkey1/src/main/assets/), so "
                        + "the app can never drift from its documentation — nothing else is in this project.\n\n"
                        + "Zero network: the INTERNET and ACCESS_NETWORK_STATE permissions are "
                        + "removed with tools:node=\"remove\", no third-party libraries are linked, "
                        + "and every operation is local file I/O.\n\n"
                        + "Workspace (visible over adb/Termux as "
                        + "/sdcard/Android/data/com.andkey1/files/workspace):\n"
                        + workspace.getAbsolutePath() + "\n\n"
                        + "Exit-code contract (same as the Python tool): 0 = success, 1 = file or "
                        + "directory not found, 2 = parse or validation failure, 3 = wrong usage."));
        col.addView(action("Open workspace listing", v -> {
            refreshLists();
            toast("Workspace: " + workspace.getAbsolutePath());
        }), wide());
        return scrollPage(col);
    }

    private View spacer(int heightDp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp)));
        return v;
    }

    // ======================================================================
    // File picking (SAF) — works with zero storage permissions
    // ======================================================================

    private void pickFile(int requestCode, String mime) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mime == null ? "*/*" : mime);
        try {
            startActivityForResult(intent, requestCode);
        } catch (Exception e) {
            status("No document picker available: " + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            if (requestCode != REQ_EXPORT) {
                status("Cancelled — nothing picked.");
            }
            return;
        }
        Uri uri = data.getData();
        if (requestCode == REQ_EXPORT) {
            writeUri(uri);
            return;
        }
        final String name = safeName(queryDisplayName(uri));
        final int maxKvs = readMaxKvs();   // read the UI field on the UI thread
        switch (requestCode) {
            case REQ_LOAD:
                runPicked(uri, name, scratch, loadOut, "LOAD", file -> ModelToolkit.load(file));
                break;
            case REQ_INSPECT:
                runPicked(uri, name, scratch, inspectOut, "INSPECT",
                        file -> ModelToolkit.inspect(file, maxKvs));
                break;
            case REQ_REPORT:
                runPicked(uri, name, workspace, generateOut, "GENERATE report", file -> {
                    File out = new File(file.getAbsolutePath() + ".report.json");
                    ModelToolkit.Result r = ModelToolkit.generateReport(file, out);
                    lastGenerated = out;
                    return r;
                });
                break;
            case REQ_SOURCE:
                runPicked(uri, name, workspace, replaceOut, "IMPORT source", file -> {
                    replaceSourceName = file.getName();
                    ModelToolkit.Result base = ModelToolkit.load(file);
                    String human = "SOURCE READY → " + file.getName() + "\n"
                            + "Type the workspace target name above (e.g. model.gguf), then press\n"
                            + "\"Run REPLACE (target ← source)\".\n\n"
                            + base.human;
                    return new ModelToolkit.Result(base.json, human);
                });
                break;
            default:
                break;
        }
    }

    private interface PickedOp {
        ModelToolkit.Result run(File localCopy) throws Exception;
    }

    private void runPicked(Uri uri, String name, File dir, TextView out, String label, final PickedOp op) {
        final File dest = new File(dir, name);
        runTask(label + " · " + name, out, () -> {
            copyUriToFile(uri, dest);
            return op.run(dest);
        });
    }

    private int readMaxKvs() {
        try {
            int v = Integer.parseInt(maxKvsField.getText().toString().trim());
            return v <= 0 ? 32 : Math.min(v, 4096);
        } catch (Exception e) {
            return 32;
        }
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.isEmpty()) {
                    return n;
                }
            }
        } catch (Exception ignored) {
            // fall through to the URI's last segment
        }
        String last = uri.getLastPathSegment();
        return last == null ? "picked-file.bin" : last;
    }

    private String safeName(String name) {
        String n = name.replace('\\', '_').replace('/', '_').trim();
        if (n.isEmpty() || n.equals(".") || n.equals("..")) {
            return "picked-file.bin";
        }
        return n;
    }

    private void copyUriToFile(Uri uri, File dest) throws IOException {
        File parent = dest.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("cannot create " + parent);
        }
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(dest)) {
            if (in == null) {
                throw new IOException("cannot open " + uri);
            }
            byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
            out.getFD().sync();
        }
    }

    // ======================================================================
    // Background task plumbing
    // ======================================================================

    private void runTask(final String label, final TextView out, final Op task) {
        statusAsync("Working · " + label);
        if (out != null) {
            out.setText("Working…\n" + label);
        }
        bg.execute(() -> {
            String text;
            try {
                text = task.run().human;
            } catch (ModelToolkit.ToolkitException e) {
                text = "FAILED (exit code " + e.code + ")\n" + e.getMessage();
            } catch (Throwable t) {
                text = "ERROR\n" + t.getClass().getSimpleName()
                        + (t.getMessage() == null ? "" : ": " + t.getMessage());
            }
            final String finalText = text;
            ui.post(() -> {
                if (out != null) {
                    out.setText(finalText);
                }
                status("Ready · " + label + " finished");
                refreshLists();
            });
        });
    }

    // ======================================================================
    // Generate actions
    // ======================================================================

    private void generateStub() {
        final String name = stubNameField.getText().toString().trim();
        final String arch = stubArchField.getText().toString().trim();
        final File out = new File(workspace, "stub.gguf");
        runTask("GENERATE stub-gguf", generateOut, () -> {
            ModelToolkit.Result r = ModelToolkit.generateStubGguf(out,
                    name.isEmpty() ? "app-stub" : name,
                    arch.isEmpty() ? "llama" : arch);
            lastGenerated = out;
            return r;
        });
    }

    private void generateManifest() {
        final File out = new File(workspace, "manifest.json");
        runTask("GENERATE manifest", generateOut, () -> {
            ModelToolkit.Result r = ModelToolkit.generateManifest(workspace, out);
            lastGenerated = out;
            return r;
        });
    }

    private void exportLastGenerated() {
        if (lastGenerated == null || !lastGenerated.isFile()) {
            toast("Generate something first (report / stub / manifest).");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, lastGenerated.getName());
        try {
            startActivityForResult(intent, REQ_EXPORT);
        } catch (Exception e) {
            toast("No export target available: " + e.getMessage());
        }
    }

    private void writeUri(Uri uri) {
        if (lastGenerated == null || !lastGenerated.isFile()) {
            return;
        }
        final File src = lastGenerated;
        statusAsync("Copying " + src.getName() + " to the chosen location …");
        bg.execute(() -> {
            String message;
            try (InputStream in = new java.io.FileInputStream(src);
                 OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) {
                    throw new IOException("cannot open target");
                }
                byte[] buf = new byte[128 * 1024];
                long total = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    total += n;
                }
                out.flush();
                message = "Exported " + src.getName() + " (" + total + " bytes)";
            } catch (Exception e) {
                message = "Export failed: " + e.getMessage();
            }
            final String m = message;
            ui.post(() -> status(m));
        });
    }

    // ======================================================================
    // Replace actions
    // ======================================================================

    private File targetFile() {
        String name = targetNameField.getText().toString().trim();
        if (name.isEmpty()) {
            return null;
        }
        return new File(workspace, safeName(name));
    }

    private void runReplace() {
        final File target = targetFile();
        if (target == null) {
            toast("Type the workspace file name to replace first.");
            return;
        }
        if (replaceSourceName == null) {
            toast("Pick a replacement SOURCE file first.");
            return;
        }
        final File source = new File(workspace, replaceSourceName);
        final boolean force = forceBox.isChecked();
        runTask("REPLACE " + target.getName(), replaceOut,
                () -> ModelToolkit.replace(target, source, force, false));
    }

    private void runRestore() {
        final File target = targetFile();
        if (target == null) {
            toast("Type the workspace file name to restore first.");
            return;
        }
        runTask("RESTORE " + target.getName(), replaceOut,
                () -> ModelToolkit.restore(target));
    }

    // ======================================================================
    // Workspace helpers
    // ======================================================================

    private void refreshLists() {
        final String listing = workspaceListing();
        ui.post(() -> {
            if (workspaceListGenerate != null) {
                workspaceListGenerate.setText(listing);
            }
            if (workspaceListReplace != null) {
                workspaceListReplace.setText(listing);
            }
        });
    }

    private String workspaceListing() {
        File[] files = workspace.listFiles();
        StringBuilder sb = new StringBuilder();
        sb.append("folder: ").append(workspace.getAbsolutePath()).append("\n\n");
        if (files == null || files.length == 0) {
            sb.append("(empty — generate something, or pick a file on another tab)");
            return sb.toString();
        }
        List<File> sorted = new ArrayList<>(Arrays.asList(files));
        sorted.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        int count = 0;
        long bytes = 0;
        for (File f : sorted) {
            if (!f.isFile()) {
                continue;
            }
            count++;
            bytes += f.length();
            String tag = f.getName().contains(".bak-") ? "  [backup]" : "";
            sb.append(String.format(Locale.US, "%-34s %12s%s%n",
                    f.getName(), ModelToolkit.fmtSize(f.length()), tag));
        }
        sb.append("\n").append(count).append(" file(s), ")
                .append(ModelToolkit.fmtSize(bytes)).append(" total");
        return sb.toString();
    }

    private void saveToWorkspace(String assetName, String content) {
        final File out = new File(workspace, assetName);
        runTask("SAVE " + assetName, null, () -> {
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(content.getBytes("UTF-8"));
                fos.flush();
                fos.getFD().sync();
            }
            org.json.JSONObject json = new org.json.JSONObject();
            json.put("path", out.getAbsolutePath());
            ModelToolkit.Result r = new ModelToolkit.Result(json, "SAVED "
                    + out.getAbsolutePath() + " (" + out.length() + " bytes)");
            lastGenerated = out;
            return r;
        });
    }

    private void copyToClipboard(String label, String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm == null) {
            toast("Clipboard unavailable");
            return;
        }
        cm.setPrimaryClip(ClipData.newPlainText(label, text));
        toast("Copied to clipboard");
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toString("UTF-8");
        } catch (IOException e) {
            return "(cannot read asset " + name + ": " + e.getMessage() + ")";
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        bg.shutdownNow();
    }
}
