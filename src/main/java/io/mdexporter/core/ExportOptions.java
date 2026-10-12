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

    /** Style (export template) of the documents and of the preview: built-in or custom. */
    private StyleDefinition styleDefinition = ExportTheme.GITHUB.definition();
    /** Overrides of the theme; {@code null} keeps the theme's choice. */
    private String accentColor;
    private ThemeFont bodyFont;
    private ThemeFont headingFont;
    private ThemeFont codeFont;
    /** Text size relative to the theme (1 = 100 %). */
    private double textScale = 1.0;
    private boolean justify = false;
    /** HTML / PDF / preview: CSS file appended after the theme; {@code null} for none. */
    private String customCss;

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
        o.styleDefinition = styleDefinition;
        o.accentColor = accentColor;
        o.bodyFont = bodyFont;
        o.headingFont = headingFont;
        o.codeFont = codeFont;
        o.textScale = textScale;
        o.justify = justify;
        o.customCss = customCss;
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

    public StyleDefinition getStyleDefinition() {
        return styleDefinition;
    }

    public ExportOptions setStyleDefinition(StyleDefinition style) {
        this.styleDefinition = style == null ? ExportTheme.GITHUB.definition() : style;
        return this;
    }

    /** Selects a built-in style. */
    public ExportOptions setTheme(ExportTheme theme) {
        return setStyleDefinition(theme == null ? null : theme.definition());
    }

    /** Accent colour override as {@code RRGGBB}, or {@code null}. */
    public String getAccentColor() {
        return accentColor;
    }

    /** Accepts {@code #RRGGBB}, {@code RRGGBB} or {@code #RGB}; anything else (or blank) clears the override. */
    public ExportOptions setAccentColor(String color) {
        this.accentColor = normalizeColor(color);
        return this;
    }

    public ThemeFont getBodyFont() {
        return bodyFont;
    }

    public ExportOptions setBodyFont(ThemeFont font) {
        this.bodyFont = font;
        return this;
    }

    public ThemeFont getHeadingFont() {
        return headingFont;
    }

    public ExportOptions setHeadingFont(ThemeFont font) {
        this.headingFont = font;
        return this;
    }

    public ThemeFont getCodeFont() {
        return codeFont;
    }

    public ExportOptions setCodeFont(ThemeFont font) {
        this.codeFont = font;
        return this;
    }

    public double getTextScale() {
        return textScale;
    }

    public ExportOptions setTextScale(double scale) {
        this.textScale = Double.isNaN(scale) ? 1 : Math.max(0.6, Math.min(2, scale));
        return this;
    }

    public boolean isJustify() {
        return justify;
    }

    public ExportOptions setJustify(boolean justify) {
        this.justify = justify;
        return this;
    }

    public String getCustomCss() {
        return customCss;
    }

    public ExportOptions setCustomCss(String path) {
        this.customCss = path == null || path.isBlank() ? null : path.trim();
        return this;
    }

    /** The theme with every override applied. */
    public DocumentStyle style() {
        return DocumentStyle.of(this);
    }

    static String normalizeColor(String color) {
        if (color == null) {
            return null;
        }
        String c = color.trim();
        if (c.startsWith("#")) {
            c = c.substring(1);
        }
        if (c.matches("[0-9a-fA-F]{3}")) {
            c = "" + c.charAt(0) + c.charAt(0) + c.charAt(1) + c.charAt(1) + c.charAt(2) + c.charAt(2);
        }
        return c.matches("[0-9a-fA-F]{6}") ? c.toUpperCase(java.util.Locale.ROOT) : null;
    }
}
