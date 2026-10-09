package io.mdexporter.export;

import io.mdexporter.core.ExportOptions;
import io.mdexporter.core.ProgressListener;
import io.mdexporter.core.ResourceLoader;
import io.mdexporter.diagram.DiagramRenderer;
import io.mdexporter.diagram.DiagramService;
import io.mdexporter.diagram.RenderedDiagram;
import org.commonmark.node.FencedCodeBlock;

/** Everything an exporter needs besides the document itself. */
public record ExportContext(DiagramService diagrams, ResourceLoader resources, ExportOptions options,
                            ProgressListener progress) {

    public boolean isDiagram(FencedCodeBlock block) {
        return diagrams != null && diagrams.isDiagram(block.getInfo());
    }

    public RenderedDiagram diagram(FencedCodeBlock block, boolean png) {
        return diagrams.render(block.getInfo(), block.getLiteral(),
                new DiagramRenderer.Request(png, options.getDiagramScale(), options.getMermaidTheme()));
    }
}
