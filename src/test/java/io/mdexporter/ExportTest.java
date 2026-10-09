package io.mdexporter;

import io.mdexporter.core.ExportFormat;
import io.mdexporter.core.ExportOptions;
import io.mdexporter.core.ExportService;
import io.mdexporter.core.MarkdownDocument;
import io.mdexporter.core.MarkdownParser;
import io.mdexporter.core.PageSize;
import io.mdexporter.diagram.DiagramService;
import io.mdexporter.diagram.PlantUmlRenderer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end export tests. Mermaid needs a JavaFX WebView (and thus a display), so these tests only use the
 * PlantUML renderer and run headless.
 */
class ExportTest {

    private static final String MARKDOWN = """
            ---
            title: Test Report
            author: Jane Doe
            ---

            # Introduction

            Some **bold** and *italic* text with `code`, a [link](https://example.org) and a footnote.[^n]

            [^n]: The footnote text.

            ## Data

            | Name  | Value |
            |:------|------:|
            | Alpha |     1 |
            | Beta  |     2 |

            1. first
            2. second
               - nested

            - [x] done
            - [ ] open

            ![Pixel](img/pixel.png)

            ```plantuml
            Alice -> Bob : hello
            ```

            ```java
            System.out.println("hi");
            ```

            > [!NOTE]
            > An alert.
            """;

    @TempDir
    static Path dir;
    private static MarkdownDocument document;
    private static ExportService service;

    @BeforeAll
    static void setUp() throws Exception {
        Files.createDirectories(dir.resolve("img"));
        BufferedImage image = new BufferedImage(120, 60, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.ORANGE);
        g.fillRect(0, 0, 120, 60);
        g.dispose();
        ImageIO.write(image, "png", dir.resolve("img/pixel.png").toFile());
        Path md = dir.resolve("report.md");
        Files.writeString(md, MARKDOWN, StandardCharsets.UTF_8);
        document = MarkdownParser.parse(md);
        service = new ExportService(new DiagramService(List.of(new PlantUmlRenderer())));
    }

    @Test
    void titleComesFromFrontMatter() {
        assertEquals("Test Report", document.title());
        assertEquals("Jane Doe", document.frontMatterValue("author"));
        assertEquals("report", document.name());
    }

    @Test
    void exportsHtml() throws Exception {
        Path out = service.export(document, ExportFormat.HTML, dir.resolve("out/report.html"),
                ExportOptions.defaults(), null);
        String html = Files.readString(out);
        assertTrue(html.contains("<table"), "table");
        assertTrue(html.contains("data:image/png;base64,"), "embedded image");
        assertTrue(html.contains("<svg"), "inline PlantUML svg");
        assertTrue(html.contains("markdown-alert-note"), "alert");
        assertTrue(html.contains("footnotes"), "footnotes");
        assertTrue(html.contains("<title>Test Report</title>"), "title");
        assertFalse(html.contains("@@MDX_DIAGRAM_"), "diagram placeholders replaced");
    }

    @Test
    void exportsHtmlWithCopiedImages() throws Exception {
        ExportOptions options = ExportOptions.defaults().setEmbedImages(false);
        Path out = service.export(document, ExportFormat.HTML, dir.resolve("copied/report.html"), options, null);
        String html = Files.readString(out);
        assertTrue(html.contains("src=\"report_files/pixel.png\""), html);
        assertTrue(Files.isRegularFile(dir.resolve("copied/report_files/pixel.png")));
    }

    @Test
    void exportsDocx() throws Exception {
        Path out = service.export(document, ExportFormat.DOCX, dir.resolve("out/report.docx"),
                ExportOptions.defaults(), null);
        try (InputStream in = Files.newInputStream(out); XWPFDocument doc = new XWPFDocument(in)) {
            assertEquals(1, doc.getTables().size());
            assertEquals("Alpha", doc.getTables().get(0).getRow(1).getCell(0).getText());
            assertEquals(2, doc.getAllPictures().size(), "image + PlantUML diagram");
            assertEquals(1, doc.getFootnotes().size());
            assertTrue(doc.getParagraphs().stream().map(XWPFParagraph::getStyle)
                    .anyMatch("Heading1"::equals));
            assertTrue(doc.getParagraphs().stream().anyMatch(p -> p.getNumID() != null), "numbered lists");
            assertTrue(doc.getParagraphs().stream().anyMatch(p -> "SourceCode".equals(p.getStyle())));
            assertEquals("Test Report", doc.getProperties().getCoreProperties().getTitle());
        }
    }

    @Test
    void exportsPdf() throws Exception {
        Path out = service.export(document, ExportFormat.PDF, dir.resolve("out/report.pdf"),
                ExportOptions.defaults().setPageSize(PageSize.LETTER), null);
        try (PDDocument pdf = Loader.loadPDF(out.toFile())) {
            assertTrue(pdf.getNumberOfPages() >= 1);
            String text = new PDFTextStripper().getText(pdf);
            assertTrue(text.contains("Introduction"), text);
            assertTrue(text.contains("Alpha"), text);
            assertTrue(text.contains("The footnote text."), text);
            assertEquals(612, Math.round(pdf.getPage(0).getMediaBox().getWidth()), "Letter width");
        }
    }

    @Test
    void tableOfContentsInAllFormats() throws Exception {
        ExportOptions options = ExportOptions.defaults().setTableOfContents(true);
        List<Path> files = service.export(document, EnumSet.allOf(ExportFormat.class), dir.resolve("toc"), "toc",
                options, null);
        assertEquals(3, files.size());
        assertTrue(Files.readString(dir.resolve("toc/toc.html")).contains("class=\"toc\""));
        try (PDDocument pdf = Loader.loadPDF(dir.resolve("toc/toc.pdf").toFile())) {
            assertTrue(new PDFTextStripper().getText(pdf).contains("Contents"));
        }
        try (InputStream in = Files.newInputStream(dir.resolve("toc/toc.docx")); XWPFDocument doc = new XWPFDocument(in)) {
            assertTrue(doc.getDocument().xmlText().contains("TOC \\o"));
        }
    }

    @Test
    void diagramErrorsDoNotAbortExport() throws Exception {
        MarkdownDocument broken = MarkdownParser.parse("# Broken\n\n```dot\ndigraph { a -> }\n```\n", dir, "broken");
        Path out = service.export(broken, ExportFormat.PDF, dir.resolve("out/broken.pdf"), ExportOptions.defaults(), null);
        assertTrue(Files.size(out) > 0);
    }
}
