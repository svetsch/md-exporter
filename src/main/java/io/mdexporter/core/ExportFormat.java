package io.mdexporter.core;

/** Supported output formats. */
public enum ExportFormat {
    DOCX("Word document", "docx"),
    HTML("HTML page", "html"),
    PDF("PDF document", "pdf");

    private final String displayName;
    private final String extension;

    ExportFormat(String displayName, String extension) {
        this.displayName = displayName;
        this.extension = extension;
    }

    public String displayName() {
        return displayName;
    }

    public String extension() {
        return extension;
    }

    /** True when the format needs raster versions of diagrams. */
    public boolean needsRasterDiagrams() {
        return this != HTML;
    }

    public static ExportFormat parse(String value) {
        String v = value.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (v) {
            case "docx", "word", "doc" -> DOCX;
            case "html", "htm" -> HTML;
            case "pdf" -> PDF;
            default -> throw new IllegalArgumentException("Unknown format: " + value + " (expected docx, html or pdf)");
        };
    }

    @Override
    public String toString() {
        return displayName + " (*." + extension + ")";
    }
}
