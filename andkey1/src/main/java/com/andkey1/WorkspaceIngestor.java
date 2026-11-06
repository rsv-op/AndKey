package com.andkey1;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a SAF multi-selection into an inventory for {@link TaskPacketBuilder}.
 *
 * <p>Every input is read exactly once: size, SHA-256 and header come from the
 * same pass, so a 4 GB model costs 4 MiB of preview plus a running digest — never
 * a heap-resident copy. Files are copied into the workspace only when they are
 * small (≤ {@link #COPY_LIMIT_BYTES}) or the operator ticks "keep large copies";
 * the packet always records whether a copy exists.</p>
 *
 * <p>{@code .zip} archives are walked entry by entry through
 * {@link ZipStreamInspector} — nothing is extracted to disk.</p>
 */
public final class WorkspaceIngestor {

    /** Files above this size are inspected in place instead of duplicated. */
    public static final long COPY_LIMIT_BYTES = 64L * 1024 * 1024;

    public interface Progress {
        void message(String text);
    }

    public static final class Result {
        public final List<InspectedFile> files = new ArrayList<>();
        public final List<InspectedText> texts = new ArrayList<>();
        public final List<String> skipped = new ArrayList<>();
        public final List<String> copied = new ArrayList<>();
        public int archives;
    }

    private WorkspaceIngestor() {
    }

    public static Result ingest(Context ctx, List<Uri> uris, File workspace, boolean keepLargeCopies,
                                Progress progress, StreamInspector.Cancel cancel) throws IOException {
        Result result = new Result();
        if (!workspace.isDirectory() && !workspace.mkdirs()) {
            throw new IOException("cannot create workspace " + workspace);
        }

        for (Uri uri : uris) {
            if (cancel != null && cancel.isCancelled()) {
                throw new StreamInspector.CancelledException();
            }
            String display = displayName(ctx, uri);
            String name = uniqueName(workspace, display);
            long declared = declaredSize(ctx, uri);
            boolean copy = declared > 0 && (declared <= COPY_LIMIT_BYTES || keepLargeCopies);
            if (progress != null) {
                progress.message("Reading " + name
                        + (declared > 0 ? " · " + ModelToolkit.fmtSize(declared) : ""));
            }

            try (InputStream raw = ctx.getContentResolver().openInputStream(uri)) {
                if (raw == null) {
                    throw new IOException("cannot open " + display);
                }
                BufferedInputStream in = new BufferedInputStream(raw, 128 * 1024);
                in.mark(8);
                byte[] head = new byte[4];
                int got = readFully(in, head);
                in.reset();
                boolean isZip = got >= 2 && head[0] == 'P' && head[1] == 'K';

                if (isZip) {
                    result.archives++;
                    ZipStreamInspector.Report report = ZipStreamInspector.inspect(
                            in, name, new ZipStreamInspector.Limits(),
                            (path, index) -> {
                                if (progress != null) {
                                    progress.message("archive entry " + index + ": " + path);
                                }
                            },
                            cancel);
                    result.files.addAll(report.files);
                    result.texts.addAll(report.texts);
                    result.skipped.addAll(report.skipped);

                    HeaderParser.Header zipHeader = HeaderParser.parse(
                            new byte[]{'P', 'K', 0, 0}, Math.max(declared, 4L));
                    if (copy) {
                        File dest = new File(workspace, name);
                        copyUri(ctx, uri, dest);
                        result.copied.add(dest.getAbsolutePath());
                        result.files.add(new InspectedFile(name, zipHeader, dest.length(),
                                ModelToolkit.sha256(dest), true,
                                "archive copied; entries inspected individually"));
                    } else {
                        result.files.add(new InspectedFile(name, zipHeader, Math.max(declared, 0L), "",
                                false, "archive not copied ("
                                + (declared > 0 ? ModelToolkit.fmtSize(declared) : "size unknown")
                                + "); entries inspected individually"));
                    }
                } else {
                    boolean text = StreamInspector.isTextName(name);
                    File dest = copy ? new File(workspace, name) : null;
                    StreamInspector.Result inspected = null;

                    if (dest != null) {
                        boolean ok = false;
                        try (FileOutputStream out = new FileOutputStream(dest)) {
                            inspected = StreamInspector.inspect(new TeeInputStream(in, out), name,
                                    StreamInspector.MAX_FILE_BYTES, text, cancel);
                            out.flush();
                            out.getFD().sync();
                            ok = true;
                        } finally {
                            if (!ok) {
                                //noinspection ResultOfMethodCallIgnored
                                dest.delete();
                            }
                        }
                        result.copied.add(dest.getAbsolutePath());
                    } else {
                        inspected = StreamInspector.inspect(in, name,
                                StreamInspector.MAX_FILE_BYTES, text, cancel);
                    }

                    String note = dest != null
                            ? "copied to workspace/" + dest.getName()
                            : "not copied (" + (declared > 0
                                    ? ModelToolkit.fmtSize(declared) + " exceeds the "
                                      + ModelToolkit.fmtSize(COPY_LIMIT_BYTES) + " copy policy"
                                    : "size unknown")
                              + "; hashed and header-parsed in place)";

                    if (text) {
                        result.texts.add(new InspectedText(name, inspected.sizeBytes,
                                inspected.sha256, inspected.textPreview,
                                inspected.textShownBytes, inspected.textTruncated));
                    } else {
                        result.files.add(new InspectedFile(name, inspected.header,
                                inspected.sizeBytes, inspected.sha256, dest != null, note));
                    }
                }
            } catch (StreamInspector.CancelledException ce) {
                throw ce;
            } catch (Exception e) {
                result.skipped.add(display + " — " + e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : ": " + e.getMessage()));
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ helpers

    private static String displayName(Context ctx, Uri uri) {
        try (Cursor c = ctx.getContentResolver().query(uri,
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

    private static long declaredSize(Context ctx, Uri uri) {
        try (Cursor c = ctx.getContentResolver().query(uri,
                new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) {
                return c.getLong(0);
            }
        } catch (Exception ignored) {
            // unknown size
        }
        return -1L;
    }

    /** "model.gguf" → "model (2).gguf" when the name is already taken. */
    private static String uniqueName(File dir, String name) {
        String safe = sanitize(name);
        if (!new File(dir, safe).exists()) {
            return safe;
        }
        String base = safe;
        String ext = "";
        int dot = safe.lastIndexOf('.');
        if (dot > 0) {
            base = safe.substring(0, dot);
            ext = safe.substring(dot);
        }
        for (int i = 2; i < 10_000; i++) {
            String candidate = base + " (" + i + ")" + ext;
            if (!new File(dir, candidate).exists()) {
                return candidate;
            }
        }
        return base + "-" + System.currentTimeMillis() + ext;
    }

    private static String sanitize(String name) {
        String n = name.replace('\\', '_').replace('/', '_').trim();
        if (n.isEmpty() || ".".equals(n) || "..".equals(n)) {
            return "picked-file.bin";
        }
        return n;
    }

    private static void copyUri(Context ctx, Uri uri, File dest) throws IOException {
        try (InputStream in = ctx.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(dest)) {
            if (in == null) {
                throw new IOException("cannot open " + uri);
            }
            byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
        }
    }

    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int r = in.read(buf, total, buf.length - total);
            if (r <= 0) {
                break;
            }
            total += r;
        }
        return total;
    }

    /** Copies every byte it reads into the workspace file, in the same pass. */
    private static final class TeeInputStream extends FilterInputStream {
        private final OutputStream out;

        TeeInputStream(InputStream in, OutputStream out) {
            super(in);
            this.out = out;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                out.write(b);
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                out.write(b, off, n);
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            // Skipping would bypass the tee; read (and copy) instead.
            long remaining = n;
            byte[] buf = new byte[8192];
            while (remaining > 0) {
                int want = (int) Math.min(buf.length, remaining);
                int r = read(buf, 0, want);
                if (r <= 0) {
                    break;
                }
                remaining -= r;
            }
            return n - remaining;
        }
    }
}
