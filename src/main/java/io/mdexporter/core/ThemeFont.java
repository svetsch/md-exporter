package io.mdexporter.core;

import java.util.Arrays;
import java.util.List;

/**
 * Fonts offered for exports. Each one knows its CSS font stack (HTML), its Word font name and the TrueType files to
 * embed in PDFs (Windows and macOS names; other systems fall back to a font of the same {@link Kind}).
 */
public enum ThemeFont {

    SEGOE_UI("Segoe UI", Kind.SANS,
            "\"Segoe UI\", -apple-system, BlinkMacSystemFont, \"Noto Sans\", Helvetica, Arial, sans-serif",
            files("segoeui.ttf", "segoeuib.ttf", "segoeuii.ttf", "segoeuiz.ttf")),
    ARIAL("Arial", Kind.SANS, "Arial, Helvetica, \"Liberation Sans\", sans-serif",
            files("arial.ttf", "arialbd.ttf", "ariali.ttf", "arialbi.ttf"),
            files("Arial.ttf", "Arial Bold.ttf", "Arial Italic.ttf", "Arial Bold Italic.ttf")),
    CALIBRI("Calibri", Kind.SANS, "Calibri, Carlito, \"Segoe UI\", sans-serif",
            files("calibri.ttf", "calibrib.ttf", "calibrii.ttf", "calibriz.ttf")),
    VERDANA("Verdana", Kind.SANS, "Verdana, \"DejaVu Sans\", sans-serif",
            files("verdana.ttf", "verdanab.ttf", "verdanai.ttf", "verdanaz.ttf"),
            files("Verdana.ttf", "Verdana Bold.ttf", "Verdana Italic.ttf", "Verdana Bold Italic.ttf")),
    TREBUCHET("Trebuchet MS", Kind.SANS, "\"Trebuchet MS\", \"Segoe UI\", sans-serif",
            files("trebuc.ttf", "trebucbd.ttf", "trebucit.ttf", "trebucbi.ttf"),
            files("Trebuchet MS.ttf", "Trebuchet MS Bold.ttf", "Trebuchet MS Italic.ttf", "Trebuchet MS Bold Italic.ttf")),
    GEORGIA("Georgia", Kind.SERIF, "Georgia, \"DejaVu Serif\", serif",
            files("georgia.ttf", "georgiab.ttf", "georgiai.ttf", "georgiaz.ttf"),
            files("Georgia.ttf", "Georgia Bold.ttf", "Georgia Italic.ttf", "Georgia Bold Italic.ttf")),
    TIMES("Times New Roman", Kind.SERIF, "\"Times New Roman\", Times, \"Liberation Serif\", serif",
            files("times.ttf", "timesbd.ttf", "timesi.ttf", "timesbi.ttf"),
            files("Times New Roman.ttf", "Times New Roman Bold.ttf", "Times New Roman Italic.ttf",
                    "Times New Roman Bold Italic.ttf")),
    PALATINO("Palatino Linotype", Kind.SERIF, "\"Palatino Linotype\", Palatino, \"Book Antiqua\", serif",
            files("pala.ttf", "palab.ttf", "palai.ttf", "palabi.ttf")),
    CONSOLAS("Consolas", Kind.MONO,
            "Consolas, ui-monospace, SFMono-Regular, \"Cascadia Mono\", \"Liberation Mono\", Menlo, monospace",
            files("consola.ttf", "consolab.ttf", "consolai.ttf", "consolaz.ttf")),
    COURIER_NEW("Courier New", Kind.MONO, "\"Courier New\", Courier, \"Liberation Mono\", monospace",
            files("cour.ttf", "courbd.ttf", "couri.ttf", "courbi.ttf"),
            files("Courier New.ttf", "Courier New Bold.ttf", "Courier New Italic.ttf", "Courier New Bold Italic.ttf"));

    public enum Kind { SANS, SERIF, MONO }

    /** Regular, bold, italic and bold italic file names of one font. */
    public record Files(String regular, String bold, String italic, String boldItalic) {
    }

    private final String displayName;
    private final Kind kind;
    private final String cssStack;
    private final List<Files> files;

    ThemeFont(String displayName, Kind kind, String cssStack, Files... files) {
        this.displayName = displayName;
        this.kind = kind;
        this.cssStack = cssStack;
        this.files = List.of(files);
    }

    private static Files files(String regular, String bold, String italic, String boldItalic) {
        return new Files(regular, bold, italic, boldItalic);
    }

    /** Font name as known by Word and the operating system. */
    public String displayName() {
        return displayName;
    }

    public Kind kind() {
        return kind;
    }

    public String cssStack() {
        return cssStack;
    }

    /** Candidate files of this font (other systems' fallbacks are added by the PDF exporter). */
    public List<Files> files() {
        return files;
    }

    public boolean isMonospaced() {
        return kind == Kind.MONO;
    }

    public static List<ThemeFont> textFonts() {
        return Arrays.stream(values()).filter(f -> !f.isMonospaced()).toList();
    }

    public static List<ThemeFont> codeFonts() {
        return Arrays.stream(values()).filter(ThemeFont::isMonospaced).toList();
    }

    /** Lenient lookup by enum name or display name ("times new roman", "TIMES", "Times New Roman"). */
    public static ThemeFont parse(String value) {
        String v = value.trim();
        for (ThemeFont f : values()) {
            if (f.name().equalsIgnoreCase(v.replace(' ', '_')) || f.displayName.equalsIgnoreCase(v)) {
                return f;
            }
        }
        throw new IllegalArgumentException("Unknown font " + value + "; use one of "
                + String.join(", ", Arrays.stream(values()).map(ThemeFont::displayName).toList()));
    }

    @Override
    public String toString() {
        return displayName;
    }
}
