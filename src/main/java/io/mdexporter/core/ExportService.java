package io.mdexporter.core;

import io.mdexporter.diagram.DiagramService;
import io.mdexporter.export.DocxExporter;
import io.mdexporter.export.ExportContext;
import io.mdexporter.export.Exporter;
import io.mdexporter.export.HtmlExporter;
import io.mdexporter.export.PdfExporter;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.FencedCodeBlock;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Entry point for exporting documents, used by both the UI and the command line. */
public final class ExportService {

    private final DiagramService diagrams;

    public ExportService(DiagramService diagrams) {
        this.diagrams = diagrams;
    }

    public DiagramService diagrams() {
        return diagrams;
    }

    /** Exports one document to one file. */
    public Path export(MarkdownDocument document, ExportFormat format, Path target, ExportOptions options,
                       ProgressListener progress) throws Exception {
        return export(document, format, target, options, progress, new ResourceLoader(document.baseDir()));
    }

    private Path export(MarkdownDocument document, ExportFormat format, Path target, ExportOptions options,
                        ProgressListener progress, ResourceLoader resources) throws Exception {
        ProgressListener p = progress == null ? ProgressListener.NONE : progress;
        ExportContext context = new ExportContext(diagrams, resources, options, p);
        prerenderDiagrams(document, context, format.needsRasterDiagrams());
        p.update(-1, "Writing " + format.displayName() + "...");
        exporter(format).export(document, target, context);
        p.update(1, "Exported " + target.getFileName());
        return target;
    }

    /** Exports one document to several formats into {@code directory/baseName.<ext>}. */
    public List<Path> export(MarkdownDocument document, Collection<ExportFormat> formats, Path directory,
                             String baseName, ExportOptions options, ProgressListener progress) throws Exception {
        List<Path> written = new ArrayList<>();
        ResourceLoader resources = new ResourceLoader(document.baseDir()); // images are loaded once for all formats
        int i = 0;
        for (ExportFormat format : formats) {
            int index = i++;
            Path target = directory.resolve(baseName + "." + format.extension());
            ProgressListener scoped = (value, message) -> {
                if (progress != null) {
                    double overall = value < 0 ? -1 : (index + value) / formats.size();
                    progress.update(overall, message);
                }
            };
            written.add(export(document, format, target, options, scoped, resources));
        }
        return written;
    }

    private void prerenderDiagrams(MarkdownDocument document, ExportContext context, boolean raster) {
        List<FencedCodeBlock> blocks = new ArrayList<>();
        document.root().accept(new AbstractVisitor() {
            @Override
            public void visit(FencedCodeBlock block) {
                if (context.isDiagram(block)) {
                    blocks.add(block);
                }
            }
        });
        for (int i = 0; i < blocks.size(); i++) {
            context.progress().update(i / (double) Math.max(1, blocks.size()) * 0.6,
                    "Rendering diagram " + (i + 1) + " of " + blocks.size() + "...");
            context.diagram(blocks.get(i), raster);
        }
    }

    public static Exporter exporter(ExportFormat format) {
        return switch (format) {
            case DOCX -> new DocxExporter();
            case HTML -> new HtmlExporter();
            case PDF -> new PdfExporter();
        };
    }
}
