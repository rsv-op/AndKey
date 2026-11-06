package com.andkey1;

/**
 * One readable text/source file (picked directly or extracted from a {@code .zip}),
 * carrying only a capped UTF-8 preview plus the real size and hash.
 */
public final class InspectedText {

    public final String path;
    public final long sizeBytes;
    public final String sha256;
    public final String preview;
    public final long shownBytes;
    public final boolean truncated;

    public InspectedText(String path, long sizeBytes, String sha256,
                         String preview, long shownBytes, boolean truncated) {
        this.path = path;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256 == null ? "" : sha256;
        this.preview = preview == null ? "" : preview;
        this.shownBytes = shownBytes;
        this.truncated = truncated;
    }
}
