package io.mdexporter;

import io.mdexporter.core.ExportFormat;
import io.mdexporter.core.ExportOptions;
import io.mdexporter.core.ExportTheme;
import io.mdexporter.core.ExportService;
import io.mdexporter.core.MarkdownDocument;
import io.mdexporter.core.MarkdownParser;
import io.mdexporter.core.PageSize;
import io.mdexporter.core.StyleDefinition;
import io.mdexporter.core.StyleLibrary;
import io.mdexporter.core.ThemeFont;
import io.mdexporter.settings.SettingsLocation;
import io.mdexporter.diagram.DiagramService;
import io.mdexporter.diagram.FxToolkit;
import javafx.application.Platform;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Command line interface: {@code md-exporter [options] <file.md>...}. */
public final class Cli {

    private static final String USAGE = """
            Usage: md-exporter --export [options] <file.md>...

            Without options the graphical application is started (optionally opening the given file).

            Options:
              -x, --export            export the given files (implied by any other option)
              -f, --format <list>     comma separated formats: docx,html,pdf (default: all)
              -o, --output <dir>      output directory (default: next to each input file)
              -p, --page-size <size>  A4, A5, Letter or Legal (default: A4)
                  --landscape         landscape orientation
                  --margin <mm>       page margin in millimetres (default: 20)
                  --toc               insert a table of contents
                  --no-embed          HTML: copy images to <name>_files instead of embedding them
                  --theme <name>      Mermaid theme: default, neutral, forest, dark, base
                  --scale <n>         diagram raster scale for Word/PDF (default: 2)

            Appearance:
                  --style <name>      github (default), classic, modern, academic, minimal, dark,
                                      the name of a custom style, or a style .json file
                  --accent <color>    accent colour (links, coloured headings), e.g. #b5179e
                  --font <name>       text font: Segoe UI, Arial, Calibri, Verdana, Trebuchet MS, Georgia,
                                      Times New Roman, Palatino Linotype
                  --heading-font <name>  heading font (same choices)
                  --code-font <name>  code font: Consolas, Courier New
                  --text-size <pct>   text size in percent of the style's size (default: 100)
                  --justify           justified paragraphs
                  --css <file>        HTML/PDF: CSS file applied after the style
              -v, --version           print the version and commit
              -h, --help              show this help
            """;

    private Cli() {
    }

    public static int run(String[] args) {
        PrintStream out = System.out;
        PrintStream err = System.err;
        Set<ExportFormat> formats = EnumSet.noneOf(ExportFormat.class);
        ExportOptions options = ExportOptions.defaults();
        Path outputDir = null;
        List<Path> inputs = new ArrayList<>();
        try {
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                switch (a) {
                    case "-h", "--help" -> {
                        out.print(USAGE);
                        return 0;
                    }
                    case "-v", "--version" -> {
                        out.println("Markdown Exporter " + BuildInfo.summary());
                        return 0;
                    }
                    case "-x", "--export" -> {
                        // explicit command line mode
                    }
                    case "-f", "--format" -> {
                        for (String f : value(args, ++i, a).split(",")) {
                            if (!f.isBlank()) {
                                formats.add(ExportFormat.parse(f));
                            }
                        }
                    }
                    case "-o", "--output" -> outputDir = Path.of(value(args, ++i, a));
                    case "-p", "--page-size" -> options.setPageSize(PageSize.parse(value(args, ++i, a)));
                    case "--landscape" -> options.setOrientation(ExportOptions.Orientation.LANDSCAPE);
                    case "--margin" -> options.setMarginMm(Double.parseDouble(value(args, ++i, a)));
                    case "--toc" -> options.setTableOfContents(true);
                    case "--no-embed" -> options.setEmbedImages(false);
                    case "--theme" -> options.setMermaidTheme(value(args, ++i, a));
                    case "--scale" -> options.setDiagramScale(Double.parseDouble(value(args, ++i, a)));
                    case "--style" -> options.setStyleDefinition(style(value(args, ++i, a)));
                    case "--accent" -> {
                        String color = value(args, ++i, a);
                        if (options.setAccentColor(color).getAccentColor() == null) {
                            throw new IllegalArgumentException("Invalid colour " + color + " (use #RRGGBB)");
                        }
                    }
                    case "--font" -> options.setBodyFont(textFont(value(args, ++i, a)));
                    case "--heading-font" -> options.setHeadingFont(textFont(value(args, ++i, a)));
                    case "--code-font" -> {
                        ThemeFont font = ThemeFont.parse(value(args, ++i, a));
                        if (!font.isMonospaced()) {
                            throw new IllegalArgumentException(font + " is not a code font");
                        }
                        options.setCodeFont(font);
                    }
                    case "--text-size" -> options.setTextScale(
                            Double.parseDouble(value(args, ++i, a).replace("%", "")) / 100);
                    case "--justify" -> options.setJustify(true);
                    case "--css" -> {
                        Path css = Path.of(value(args, ++i, a));
                        if (!Files.isRegularFile(css)) {
                            throw new IllegalArgumentException("CSS file not found: " + css);
                        }
                        options.setCustomCss(css.toAbsolutePath().toString());
                    }
                    default -> {
                        if (a.startsWith("-")) {
                            throw new IllegalArgumentException("Unknown option " + a);
                        }
                        inputs.add(Path.of(a));
                    }
                }
            }
        } catch (IllegalArgumentException e) {
            err.println("Error: " + e.getMessage());
            err.print(USAGE);
            return 2;
        }
        if (inputs.isEmpty()) {
            err.println("Error: no input file");
            err.print(USAGE);
            return 2;
        }
        if (formats.isEmpty()) {
            formats = EnumSet.allOf(ExportFormat.class);
        }

        ExportService service = new ExportService(DiagramService.createDefault());
        int failures = 0;
        try {
            for (Path input : inputs) {
                if (!Files.isRegularFile(input)) {
                    err.println("Not found: " + input);
                    failures++;
                    continue;
                }
                try {
                    MarkdownDocument doc = MarkdownParser.parse(input);
                    Path dir = outputDir != null ? outputDir : input.toAbsolutePath().getParent();
                    Files.createDirectories(dir);
                    for (Path written : service.export(doc, formats, dir, doc.name(), options, null)) {
                        out.println("Written " + written.toAbsolutePath());
                    }
                } catch (Exception e) {
                    failures++;
                    err.println("Failed to export " + input + ": " + e);
                    if (Boolean.getBoolean("mdx.debug")) {
                        e.printStackTrace(err);
                    }
                }
            }
        } finally {
            if (FxToolkit.startedByUs()) {
                Platform.exit();
            }
        }
        return failures == 0 ? 0 : 1;
    }

    /** A built-in style, a custom style of the styles folder, or a style JSON file. */
    private static StyleDefinition style(String value) {
        Path file = Path.of(value);
        if (value.toLowerCase(java.util.Locale.ROOT).endsWith(".json") && Files.isRegularFile(file)) {
            try {
                StyleDefinition style = StyleLibrary.fromJson(Files.readString(file),
                        ExportTheme.GITHUB.definition());
                return style.asCustom(style.name());
            } catch (java.io.IOException | IllegalArgumentException e) {
                throw new IllegalArgumentException("Cannot read style " + file + ": " + e.getMessage());
            }
        }
        StyleLibrary library = new StyleLibrary(SettingsLocation.resolve().location().stylesDirectory());
        return library.find(value).orElseThrow(() -> new IllegalArgumentException("Unknown style " + value
                + "; use one of " + String.join(", ", library.all().stream().map(s -> s.builtIn()
                ? s.id().toLowerCase(java.util.Locale.ROOT) : "\"" + s.name() + "\"").toList())));
    }

    private static ThemeFont textFont(String name) {
        ThemeFont font = ThemeFont.parse(name);
        if (font.isMonospaced()) {
            throw new IllegalArgumentException(font + " is a code font");
        }
        return font;
    }

    private static String value(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException("Missing value for " + option);
        }
        return args[index];
    }
}
