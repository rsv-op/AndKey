package com.andkey1;

/**
 * One inventoried file: a model/data file picked directly, or an entry inside a
 * {@code .zip} archive (path then reads {@code archive.zip!/inside/name.gguf}).
 */
public final class InspectedFile {

    public final String path;
    public final HeaderParser.Header header;
    public final long sizeBytes;
    public final String sha256;
    public final boolean copiedToWorkspace;
    public final String note;

    public InspectedFile(String path, HeaderParser.Header header, long sizeBytes,
                         String sha256, boolean copiedToWorkspace, String note) {
        this.path = path;
        this.header = header == null ? new HeaderParser.Header() : header;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256 == null ? "" : sha256;
        this.copiedToWorkspace = copiedToWorkspace;
        this.note = note == null ? "" : note;
    }

    public String format() {
        return header.format;
    }
}
