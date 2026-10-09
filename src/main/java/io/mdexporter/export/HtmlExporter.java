package io.mdexporter.export;

import io.mdexporter.core.MarkdownDocument;
import io.mdexporter.core.MarkdownParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Exports to a single HTML page. Diagrams are inlined as SVG; images are embedded as data URIs or, when embedding
 * is disabled, copied into a {@code <name>_files} folder next to the page.
 */
public final class HtmlExporter implements Exporter {

    @Override
    public void export(MarkdownDocument document, Path target, ExportContext context) throws Exception {
        Path parent = target.toAbsolutePath().getParent();
        Path assets = parent.resolve(MarkdownParser.baseName(target.getFileName().toString()) + "_files");
        HtmlBuilder builder = new HtmlBuilder(HtmlBuilder.Target.HTML, context, assets);
        String html = builder.build(document).toHtml();
        Files.createDirectories(parent);
        Files.writeString(target, html, StandardCharsets.UTF_8);
    }
}
