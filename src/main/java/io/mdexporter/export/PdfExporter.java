package io.mdexporter.export;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.openhtmltopdf.svgsupport.BatikSVGDrawer;
import io.mdexporter.core.MarkdownDocument;
import org.jsoup.helper.W3CDom;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Exports to PDF by rendering print-styled HTML with openhtmltopdf (PDFBox). */
public final class PdfExporter implements Exporter {

    static {
        com.openhtmltopdf.util.XRLog.setLoggingEnabled(false);
    }

    @Override
    public void export(MarkdownDocument document, Path target, ExportContext context) throws Exception {
        HtmlBuilder builder = new HtmlBuilder(HtmlBuilder.Target.PDF, context, null);
        org.jsoup.nodes.Document html = builder.build(document).document();
        org.w3c.dom.Document dom = new W3CDom().fromJsoup(html);

        Path parent = target.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path tmp = Files.createTempFile(parent, ".mdx-", ".pdf.tmp");
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                PdfRendererBuilder pdf = new PdfRendererBuilder();
                pdf.useFastMode();
                pdf.useSVGDrawer(new BatikSVGDrawer());
                PdfFonts.register(pdf);
                pdf.withW3cDocument(dom, document.baseDir().toUri().toString());
                pdf.toStream(out);
                pdf.run();
            }
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
