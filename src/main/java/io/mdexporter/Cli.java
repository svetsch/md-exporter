package io.mdexporter;

import io.mdexporter.core.ExportFormat;
import io.mdexporter.core.ExportOptions;
import io.mdexporter.core.ExportService;
import io.mdexporter.core.MarkdownDocument;
import io.mdexporter.core.MarkdownParser;
import io.mdexporter.core.PageSize;
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

    private static String value(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException("Missing value for " + option);
        }
        return args[index];
    }
}
