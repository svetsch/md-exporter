package io.mdexporter.core;

import java.util.Arrays;

/**
 * Visual style of exported documents (and of the preview): colours, fonts and a few layout choices. The user can
 * override the accent colour, the fonts and the text size, see {@link ExportOptions}.
 */
public enum ExportTheme {

    GITHUB("GitHub", "Clean sans-serif look of GitHub's Markdown rendering",
            new Palette("1F2328", "59636E", "D1D9E0", "F6F8FA", "FFFFFF", "0969DA", "1F2328", "EFF1F3", "F3F5F7"),
            ThemeFont.SEGOE_UI, ThemeFont.SEGOE_UI, ThemeFont.CONSOLAS, true, false, false),
    CLASSIC("Classic", "Serif text and headings, navy links, for reports and letters",
            new Palette("222222", "666666", "CCCCC4", "F7F7F4", "FFFFFF", "1F4E8C", "1A1A1A", "F0F0EB", "EFEFE9"),
            ThemeFont.GEORGIA, ThemeFont.GEORGIA, ThemeFont.CONSOLAS, true, false, false),
    MODERN("Modern", "Sans-serif text with coloured headings and no rules",
            new Palette("24292F", "5B6470", "E2E8F0", "F8FAFC", "FFFFFF", "4F46E5", "4F46E5", "EEF2FF", "EEF2FF"),
            ThemeFont.SEGOE_UI, ThemeFont.TREBUCHET, ThemeFont.CONSOLAS, false, true, false),
    ACADEMIC("Academic", "Times New Roman, black headings, sober tables, for papers",
            new Palette("000000", "444444", "999999", "F5F5F5", "FFFFFF", "003366", "000000", "F2F2F2", "EBEBEB"),
            ThemeFont.TIMES, ThemeFont.TIMES, ThemeFont.COURIER_NEW, false, false, false),
    MINIMAL("Minimal", "Arial, greys and lots of white space",
            new Palette("333333", "777777", "E5E5E5", "FAFAFA", "FFFFFF", "0066CC", "111111", "F4F4F4", "F7F7F7"),
            ThemeFont.ARIAL, ThemeFont.ARIAL, ThemeFont.CONSOLAS, false, false, false),
    DARK("Dark", "Light text on a dark background (HTML and PDF; Word uses the GitHub colours)",
            new Palette("E6EDF3", "9198A1", "3D444D", "151B23", "0D1117", "4493F8", "F0F6FC", "262C36", "1C2129"),
            ThemeFont.SEGOE_UI, ThemeFont.SEGOE_UI, ThemeFont.CONSOLAS, true, false, true);

    /**
     * Theme colours as {@code RRGGBB}.
     *
     * @param text        body text
     * @param muted       secondary text (quotes, captions, footnotes)
     * @param border      rules, table borders
     * @param subtle      code block and table stripe background
     * @param background  page background
     * @param accent      links (and headings when the theme colours them)
     * @param heading     heading text
     * @param codeBg      inline code background
     * @param tableHeader table header background
     */
    public record Palette(String text, String muted, String border, String subtle, String background, String accent,
                          String heading, String codeBg, String tableHeader) {
    }

    private final String displayName;
    private final String description;
    private final Palette palette;
    private final ThemeFont bodyFont;
    private final ThemeFont headingFont;
    private final ThemeFont codeFont;
    private final boolean headingRules;
    private final boolean accentHeadings;
    private final boolean dark;

    ExportTheme(String displayName, String description, Palette palette, ThemeFont bodyFont, ThemeFont headingFont,
                ThemeFont codeFont, boolean headingRules, boolean accentHeadings, boolean dark) {
        this.displayName = displayName;
        this.description = description;
        this.palette = palette;
        this.bodyFont = bodyFont;
        this.headingFont = headingFont;
        this.codeFont = codeFont;
        this.headingRules = headingRules;
        this.accentHeadings = accentHeadings;
        this.dark = dark;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    public Palette palette() {
        return palette;
    }

    public ThemeFont bodyFont() {
        return bodyFont;
    }

    public ThemeFont headingFont() {
        return headingFont;
    }

    public ThemeFont codeFont() {
        return codeFont;
    }

    /** Lines under level 1 and 2 headings. */
    public boolean headingRules() {
        return headingRules;
    }

    /** Headings take the accent colour. */
    public boolean accentHeadings() {
        return accentHeadings;
    }

    public boolean isDark() {
        return dark;
    }

    /** This built-in style as a {@link StyleDefinition}. */
    public StyleDefinition definition() {
        return new StyleDefinition(name(), displayName, description, palette, bodyFont, headingFont, codeFont,
                headingRules, accentHeadings, dark, "", true);
    }

    public static ExportTheme parse(String value) {
        String v = value.trim();
        for (ExportTheme t : values()) {
            if (t.name().equalsIgnoreCase(v) || t.displayName.equalsIgnoreCase(v)) {
                return t;
            }
        }
        throw new IllegalArgumentException("Unknown style " + value + "; use one of "
                + String.join(", ", Arrays.stream(values()).map(t -> t.name().toLowerCase()).toList()));
    }

    @Override
    public String toString() {
        return displayName;
    }
}
