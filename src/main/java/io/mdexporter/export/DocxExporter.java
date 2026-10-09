package io.mdexporter.export;

import io.mdexporter.core.MarkdownDocument;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Exports to Microsoft Word (.docx) using Apache POI. */
public final class DocxExporter implements Exporter {

    @Override
    public void export(MarkdownDocument document, Path target, ExportContext context) throws Exception {
        Path parent = target.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path tmp = Files.createTempFile(parent, ".mdx-", ".docx.tmp");
        try {
            try (XWPFDocument doc = new XWPFDocument()) {
                new DocxWriter(doc, document, context).write();
                try (OutputStream out = Files.newOutputStream(tmp)) {
                    doc.write(out);
                }
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
