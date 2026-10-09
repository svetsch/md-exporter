package io.mdexporter.diagram;

import net.sourceforge.plantuml.FileFormat;
import net.sourceforge.plantuml.FileFormatOption;
import net.sourceforge.plantuml.SourceStringReader;
import net.sourceforge.plantuml.core.DiagramDescription;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * Renders PlantUML diagrams (and Graphviz DOT through PlantUML) with the pure Java PlantUML library.
 * <p>
 * When no Graphviz installation is found, the built-in Smetana layout engine is used so that class, component,
 * use-case, state ... diagrams still work without native dependencies.
 */
public final class PlantUmlRenderer implements DiagramRenderer {

    private static final Set<String> PLANTUML = Set.of("plantuml", "puml", "uml");
    private static final Set<String> DOT = Set.of("dot", "graphviz");

    private final boolean graphvizAvailable = detectGraphviz();

    @Override
    public Set<String> languages() {
        return Set.of("plantuml", "puml", "uml", "dot", "graphviz");
    }

    @Override
    public synchronized RenderedDiagram render(String language, String source, Request request) throws Exception {
        String text = prepare(language, source);
        if (DOT.contains(language) && !graphvizAvailable) {
            return RenderedDiagram.failure(language,
                    "Graphviz 'dot' was not found on the PATH (set GRAPHVIZ_DOT to its location).");
        }
        SourceStringReader reader = new SourceStringReader(text, StandardCharsets.UTF_8);
        if (reader.getBlocks().isEmpty()) {
            return RenderedDiagram.failure(language, "No PlantUML diagram found (missing @startuml?)");
        }

        ByteArrayOutputStream svgOut = new ByteArrayOutputStream();
        DiagramDescription description = reader.outputImage(svgOut, 0, new FileFormatOption(FileFormat.SVG));
        String svg = SvgUtil.stripProlog(svgOut.toString(StandardCharsets.UTF_8));
        double[] size = SvgUtil.size(svg);
        double width = size != null ? size[0] : 400;
        double height = size != null ? size[1] : 300;
        svg = SvgUtil.normalizeRoot(svg, width, height);

        byte[] png = null;
        double scale = 1;
        if (request.png()) {
            // FileFormatOption.withScale is not honoured for PNG: use the "scale" directive instead
            scale = request.scale();
            String scaled = withScaleDirective(text, scale);
            ByteArrayOutputStream pngOut = new ByteArrayOutputStream();
            new SourceStringReader(scaled, StandardCharsets.UTF_8)
                    .outputImage(pngOut, 0, new FileFormatOption(FileFormat.PNG));
            png = pngOut.toByteArray();
        }
        if (description == null || svg.isBlank()) {
            return RenderedDiagram.failure(language, "PlantUML produced no image");
        }
        return new RenderedDiagram(language, svg, png, scale, width, height, null);
    }

    private static String withScaleDirective(String text, double scale) {
        if (Math.abs(scale - 1) < 0.01 || !text.startsWith("@startuml")) {
            return text;
        }
        int eol = text.indexOf('\n');
        return eol < 0 ? text : text.substring(0, eol + 1) + "scale " + scale + "\n" + text.substring(eol + 1);
    }

    private String prepare(String language, String source) {
        String src = source.strip();
        if (DOT.contains(language)) {
            return src.startsWith("@startdot") ? src : "@startdot\n" + src + "\n@enddot\n";
        }
        if (!src.startsWith("@start")) {
            src = "@startuml\n" + src + "\n@enduml";
        }
        if (!graphvizAvailable && src.startsWith("@startuml") && !src.contains("!pragma layout")) {
            int eol = src.indexOf('\n');
            src = eol < 0 ? src : src.substring(0, eol + 1) + "!pragma layout smetana\n" + src.substring(eol + 1);
        }
        return src + "\n";
    }

    private static boolean detectGraphviz() {
        String env = System.getenv("GRAPHVIZ_DOT");
        if (env != null && new File(env).canExecute()) {
            return true;
        }
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        for (String dir : path.split(File.pathSeparator)) {
            File dot = new File(dir, windows ? "dot.exe" : "dot");
            if (dot.canExecute()) {
                return true;
            }
        }
        return false;
    }
}
