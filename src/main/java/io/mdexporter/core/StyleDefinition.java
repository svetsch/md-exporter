package io.mdexporter.core;

/**
 * A document style (export template): colours, fonts, a few switches and optional extra CSS. The built-in styles
 * come from {@link ExportTheme}; custom ones are stored as JSON files by {@link StyleLibrary}.
 *
 * @param id             enum name of a built-in style, file name (without extension) of a custom one
 * @param name           display name
 * @param description    one line description
 * @param palette        colours ({@code RRGGBB})
 * @param bodyFont       text font
 * @param headingFont    heading font
 * @param codeFont       code font
 * @param headingRules   lines under level 1 and 2 headings
 * @param accentHeadings headings take the accent colour
 * @param dark           dark page (HTML and PDF; Word stays light)
 * @param css            extra CSS for HTML, PDF and the preview, applied after the style (may be empty)
 * @param builtIn        one of the styles shipped with the application (read-only)
 */
public record StyleDefinition(String id, String name, String description, ExportTheme.Palette palette,
                              ThemeFont bodyFont, ThemeFont headingFont, ThemeFont codeFont, boolean headingRules,
                              boolean accentHeadings, boolean dark, String css, boolean builtIn) {

    private static final String CUSTOM = "custom:";

    public StyleDefinition {
        name = name == null || name.isBlank() ? "Untitled style" : name.strip();
        description = description == null ? "" : description.strip();
        css = css == null ? "" : css;
    }

    /** Reference stored in the settings: the enum name of a built-in style, {@code custom:<id>} otherwise. */
    public String key() {
        return builtIn ? id : CUSTOM + id;
    }

    static boolean isCustomKey(String key) {
        return key != null && key.startsWith(CUSTOM);
    }

    static String customId(String key) {
        return key.substring(CUSTOM.length());
    }

    public StyleDefinition withName(String newName) {
        return new StyleDefinition(id, newName, description, palette, bodyFont, headingFont, codeFont, headingRules,
                accentHeadings, dark, css, builtIn);
    }

    /** An editable copy (not built-in, no id yet: the library assigns one when saving). */
    public StyleDefinition asCustom(String newName) {
        return new StyleDefinition(null, newName, description, palette, bodyFont, headingFont, codeFont, headingRules,
                accentHeadings, dark, css, false);
    }

    /** Same content (ignores id and the built-in flag). */
    public boolean sameContent(StyleDefinition o) {
        return o != null && name.equals(o.name) && description.equals(o.description) && palette.equals(o.palette)
                && bodyFont == o.bodyFont && headingFont == o.headingFont && codeFont == o.codeFont
                && headingRules == o.headingRules && accentHeadings == o.accentHeadings && dark == o.dark
                && css.equals(o.css);
    }

    @Override
    public String toString() {
        return name;
    }
}
