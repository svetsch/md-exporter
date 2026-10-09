package io.mdexporter.core;

/** User adjustable export settings. */
public final class ExportOptions {

    public enum Orientation { PORTRAIT, LANDSCAPE }

    private PageSize pageSize = PageSize.A4;
    private Orientation orientation = Orientation.PORTRAIT;
    /** Page margin in millimetres (all sides). */
    private double marginMm = 20;
    private boolean tableOfContents = false;
    /** HTML: embed images as data URIs (single self-contained file) instead of copying them next to the page. */
    private boolean embedImages = true;
    /** Raster resolution multiplier for diagrams in Word/PDF output. */
    private double diagramScale = 2.0;
    /** Mermaid theme: default, neutral, forest, dark, base. */
    private String mermaidTheme = "default";
    private boolean openAfterExport = false;

    public static ExportOptions defaults() {
        return new ExportOptions();
    }

    public ExportOptions copy() {
        ExportOptions o = new ExportOptions();
        o.pageSize = pageSize;
        o.orientation = orientation;
        o.marginMm = marginMm;
        o.tableOfContents = tableOfContents;
        o.embedImages = embedImages;
        o.diagramScale = diagramScale;
        o.mermaidTheme = mermaidTheme;
        o.openAfterExport = openAfterExport;
        return o;
    }

    /** Page width in millimetres taking the orientation into account. */
    public double pageWidthMm() {
        return orientation == Orientation.PORTRAIT ? pageSize.widthMm() : pageSize.heightMm();
    }

    public double pageHeightMm() {
        return orientation == Orientation.PORTRAIT ? pageSize.heightMm() : pageSize.widthMm();
    }

    public PageSize getPageSize() {
        return pageSize;
    }

    public ExportOptions setPageSize(PageSize pageSize) {
        this.pageSize = pageSize;
        return this;
    }

    public Orientation getOrientation() {
        return orientation;
    }

    public ExportOptions setOrientation(Orientation orientation) {
        this.orientation = orientation;
        return this;
    }

    public double getMarginMm() {
        return marginMm;
    }

    public ExportOptions setMarginMm(double marginMm) {
        this.marginMm = Math.max(0, Math.min(60, marginMm));
        return this;
    }

    public boolean isTableOfContents() {
        return tableOfContents;
    }

    public ExportOptions setTableOfContents(boolean tableOfContents) {
        this.tableOfContents = tableOfContents;
        return this;
    }

    public boolean isEmbedImages() {
        return embedImages;
    }

    public ExportOptions setEmbedImages(boolean embedImages) {
        this.embedImages = embedImages;
        return this;
    }

    public double getDiagramScale() {
        return diagramScale;
    }

    public ExportOptions setDiagramScale(double diagramScale) {
        this.diagramScale = Math.max(1, Math.min(4, diagramScale));
        return this;
    }

    public String getMermaidTheme() {
        return mermaidTheme;
    }

    public ExportOptions setMermaidTheme(String mermaidTheme) {
        this.mermaidTheme = mermaidTheme == null || mermaidTheme.isBlank() ? "default" : mermaidTheme.trim();
        return this;
    }

    public boolean isOpenAfterExport() {
        return openAfterExport;
    }

    public ExportOptions setOpenAfterExport(boolean openAfterExport) {
        this.openAfterExport = openAfterExport;
        return this;
    }
}
