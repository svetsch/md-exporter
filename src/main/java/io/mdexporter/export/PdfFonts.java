package io.mdexporter.export;

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Registers system TrueType fonts with the PDF renderer under logical family names used by {@code pdf.css}
 * ({@code MdxSans}, {@code MdxMono}, {@code MdxSymbols}). The PDF base-14 fonts only cover Latin-1, so embedding a
 * real font is required for proper Unicode output.
 */
final class PdfFonts {

    /** One font family candidate: regular, bold, italic, bold-italic file names. */
    private record Family(String regular, String bold, String italic, String boldItalic) {
    }

    private static final List<File> DIRS = fontDirs();

    private static final List<Family> SANS = List.of(
            new Family("segoeui.ttf", "segoeuib.ttf", "segoeuii.ttf", "segoeuiz.ttf"),
            new Family("arial.ttf", "arialbd.ttf", "ariali.ttf", "arialbi.ttf"),
            new Family("Arial.ttf", "Arial Bold.ttf", "Arial Italic.ttf", "Arial Bold Italic.ttf"),
            new Family("DejaVuSans.ttf", "DejaVuSans-Bold.ttf", "DejaVuSans-Oblique.ttf", "DejaVuSans-BoldOblique.ttf"),
            new Family("LiberationSans-Regular.ttf", "LiberationSans-Bold.ttf", "LiberationSans-Italic.ttf",
                    "LiberationSans-BoldItalic.ttf"),
            new Family("NotoSans-Regular.ttf", "NotoSans-Bold.ttf", "NotoSans-Italic.ttf", "NotoSans-BoldItalic.ttf"));

    private static final List<Family> MONO = List.of(
            new Family("consola.ttf", "consolab.ttf", "consolai.ttf", "consolaz.ttf"),
            new Family("DejaVuSansMono.ttf", "DejaVuSansMono-Bold.ttf", "DejaVuSansMono-Oblique.ttf",
                    "DejaVuSansMono-BoldOblique.ttf"),
            new Family("LiberationMono-Regular.ttf", "LiberationMono-Bold.ttf", "LiberationMono-Italic.ttf",
                    "LiberationMono-BoldItalic.ttf"),
            new Family("cour.ttf", "courbd.ttf", "couri.ttf", "courbi.ttf"),
            new Family("Courier New.ttf", "Courier New Bold.ttf", "Courier New Italic.ttf",
                    "Courier New Bold Italic.ttf"));

    private static final List<String> SYMBOLS = List.of("seguisym.ttf", "DejaVuSans.ttf", "NotoSansSymbols2-Regular.ttf",
            "Apple Symbols.ttf", "arialuni.ttf");

    private PdfFonts() {
    }

    static void register(PdfRendererBuilder builder) {
        registerFamily(builder, "MdxSans", SANS);
        registerFamily(builder, "MdxMono", MONO);
        for (String name : SYMBOLS) {
            File f = find(name);
            if (f != null) {
                builder.useFont(f, "MdxSymbols", 400, FontStyle.NORMAL, true);
                break;
            }
        }
    }

    private static void registerFamily(PdfRendererBuilder builder, String family, List<Family> candidates) {
        for (Family c : candidates) {
            File regular = find(c.regular());
            if (regular == null) {
                continue;
            }
            builder.useFont(regular, family, 400, FontStyle.NORMAL, true);
            File bold = find(c.bold());
            builder.useFont(bold != null ? bold : regular, family, 700, FontStyle.NORMAL, true);
            File italic = find(c.italic());
            builder.useFont(italic != null ? italic : regular, family, 400, FontStyle.ITALIC, true);
            File boldItalic = find(c.boldItalic());
            builder.useFont(boldItalic != null ? boldItalic : (bold != null ? bold : regular), family, 700,
                    FontStyle.ITALIC, true);
            return;
        }
    }

    private static File find(String name) {
        for (File dir : DIRS) {
            File f = new File(dir, name);
            if (f.isFile()) {
                return f;
            }
        }
        return null;
    }

    private static List<File> fontDirs() {
        List<File> dirs = new ArrayList<>();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home");
        if (os.contains("win")) {
            String windir = System.getenv().getOrDefault("WINDIR", "C:\\Windows");
            dirs.add(new File(windir, "Fonts"));
            String local = System.getenv("LOCALAPPDATA");
            if (local != null) {
                dirs.add(new File(local, "Microsoft\\Windows\\Fonts"));
            }
        } else if (os.contains("mac")) {
            dirs.add(new File("/System/Library/Fonts/Supplemental"));
            dirs.add(new File("/System/Library/Fonts"));
            dirs.add(new File("/Library/Fonts"));
            dirs.add(new File(home, "Library/Fonts"));
        } else {
            for (String d : List.of("/usr/share/fonts/truetype/dejavu", "/usr/share/fonts/dejavu",
                    "/usr/share/fonts/TTF", "/usr/share/fonts/truetype/liberation", "/usr/share/fonts/liberation",
                    "/usr/share/fonts/truetype/noto", "/usr/share/fonts/noto", "/usr/share/fonts/truetype/msttcorefonts")) {
                dirs.add(new File(d));
            }
            dirs.add(new File(home, ".fonts"));
            dirs.add(new File(home, ".local/share/fonts"));
        }
        return dirs;
    }
}
