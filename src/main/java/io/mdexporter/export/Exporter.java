package io.mdexporter.export;

import io.mdexporter.core.MarkdownDocument;

import java.nio.file.Path;

/** Writes a Markdown document to a file in a specific format. */
public interface Exporter {

    void export(MarkdownDocument document, Path target, ExportContext context) throws Exception;
}
