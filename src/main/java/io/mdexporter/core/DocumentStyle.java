package io.mdexporter.core;

/**
 * A theme with the user's overrides applied: what the HTML, PDF and Word writers actually use.
 *
 * @param definition  the selected style (export template)
 * @param palette     colours ({@code RRGGBB}), accent override included
 * @param bodyFont    font of the text
 * @param headingFont font of the headings and the title
 * @param codeFont    font of code
 * @param scale       text size factor (1 = the theme's size)
 * @param justify     justified paragraphs
 * @param headingRules lines under level 1 and 2 headings
 * @param customCss   CSS file appended to HTML / PDF / preview, or {@code null}
 */
public record DocumentStyle(StyleDefinition definition, ExportTheme.Palette palette, ThemeFont bodyFont,
                            ThemeFont headingFont, ThemeFont codeFont, double scale, boolean justify,
                            boolean headingRules, String customCss) {

    static DocumentStyle of(ExportOptions o) {
        StyleDefinition t = o.getStyleDefinition();
        return new DocumentStyle(t, palette(t.palette(), t.accentHeadings(), o.getAccentColor()),
                o.getBodyFont() != null ? o.getBodyFont() : t.bodyFont(),
                o.getHeadingFont() != null ? o.getHeadingFont() : t.headingFont(),
                o.getCodeFont() != null ? o.getCodeFont() : t.codeFont(),
                o.getTextScale(), o.isJustify(), t.headingRules(), o.getCustomCss());
    }

    private static ExportTheme.Palette palette(ExportTheme.Palette p, boolean accentHeadings, String accent) {
        if (accent == null) {
            return p;
        }
        return new ExportTheme.Palette(p.text(), p.muted(), p.border(), p.subtle(), p.background(), accent,
                accentHeadings ? accent : p.heading(), p.codeBg(), p.tableHeader());
    }

    public boolean isDark() {
        return definition.dark();
    }

    /** Extra CSS of the style (may be empty). */
    public String styleCss() {
        return definition.css();
    }

    /**
     * The style for printed-paper formats that cannot have a dark page (Word): a dark theme falls back to the
     * GitHub colours, keeping the fonts and the accent override.
     */
    public DocumentStyle light() {
        if (!isDark()) {
            return this;
        }
        ExportTheme.Palette light = ExportTheme.GITHUB.palette();
        String accent = palette.accent().equals(definition.palette().accent()) ? light.accent() : palette.accent();
        return new DocumentStyle(ExportTheme.GITHUB.definition(), palette(light, false, accent), bodyFont,
                headingFont, codeFont, scale, justify, headingRules, customCss);
    }

    /** {@code #RRGGBB} for CSS. */
    public static String css(String rrggbb) {
        return "#" + rrggbb;
    }
}
