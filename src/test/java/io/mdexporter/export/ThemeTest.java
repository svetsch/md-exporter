package io.mdexporter.export;

import io.mdexporter.core.ExportFormat;
import io.mdexporter.core.ExportOptions;
import io.mdexporter.core.ExportService;
import io.mdexporter.core.ExportTheme;
import io.mdexporter.core.MarkdownDocument;
import io.mdexporter.core.MarkdownParser;
import io.mdexporter.core.ThemeFont;
import io.mdexporter.diagram.DiagramService;
import io.mdexporter.diagram.PlantUmlRenderer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThemeTest {

    private static final String MARKDOWN = """
            # Report

            Some *text* with a [link](https://example.org) and `code`.

            | A | B |
            |---|---|
            | 1 | 2 |

            ```java
            int x = 1;
            ```
            """;

    @TempDir
    Path dir;

    private final ExportService service = new ExportService(new DiagramService(List.of(new PlantUmlRenderer())));

    private MarkdownDocument document() {
        return MarkdownParser.parse(MARKDOWN, dir, "report");
    }

    @ParameterizedTest
    @EnumSource(ExportTheme.class)
    void everyThemeFillsEveryPlaceholder(ExportTheme theme) {
        var style = ExportOptions.defaults().setTheme(theme).setTextScale(1.2).setJustify(true).style();
        for (String template : List.of("css/document.css", "css/pdf.css", "docx/styles.xml")) {
            String out = StyleTemplate.render(HtmlBuilder.resource(template), style);
            assertFalse(out.contains("{{"), template + " has unresolved placeholders for " + theme);
        }
    }

    @Test
    void scalesSizesAndAppliesOverrides() {
        var style = ExportOptions.defaults().setTheme(ExportTheme.MODERN).setAccentColor("#b5179e")
                .setBodyFont(ThemeFont.GEORGIA).setTextScale(1.5).style();
        assertEquals("B5179E", style.palette().accent());
        assertEquals("B5179E", style.palette().heading(), "Modern colours headings with the accent");
        String css = StyleTemplate.render("a{c:#{{accent}};f:{{bodyCss}};s:{{pt:10}};w:{{hp:22}}}", style);
        assertEquals("a{c:#B5179E;f:" + ThemeFont.GEORGIA.cssStack() + ";s:15pt;w:33}", css);
        assertNull(ExportOptions.defaults().setAccentColor("not a colour").getAccentColor());
        assertEquals("AABBCC", ExportOptions.defaults().setAccentColor("#abc").getAccentColor());
    }

    @Test
    void darkThemeKeepsWordLight() {
        var dark = ExportOptions.defaults().setTheme(ExportTheme.DARK).style();
        assertTrue(dark.isDark());
        assertFalse(dark.light().isDark());
        assertEquals(ExportTheme.GITHUB.palette().background(), dark.light().palette().background());
    }

    @Test
    void htmlUsesTheThemeAndTheCustomCss() throws Exception {
        Path css = dir.resolve("extra.css");
        Files.writeString(css, "h1 { letter-spacing: 3px; }");
        ExportOptions options = ExportOptions.defaults().setTheme(ExportTheme.DARK).setJustify(true)
                .setCustomCss(css.toString());
        Path out = service.export(document(), ExportFormat.HTML, dir.resolve("dark.html"), options, null);
        String html = Files.readString(out);
        assertTrue(html.contains("--bg: #0D1117"), "dark background");
        assertTrue(html.contains("text-align: justify"), "justified");
        assertTrue(html.contains("letter-spacing: 3px"), "custom CSS");
    }

    @Test
    void wordUsesTheThemeFontsAndColours() throws Exception {
        ExportOptions options = ExportOptions.defaults().setTheme(ExportTheme.CLASSIC).setAccentColor("#123456")
                .setCodeFont(ThemeFont.COURIER_NEW).setJustify(true);
        Path out = service.export(document(), ExportFormat.DOCX, dir.resolve("classic.docx"), options, null);
        try (InputStream in = Files.newInputStream(out); XWPFDocument doc = new XWPFDocument(in)) {
            String styles = doc.getStyles().getStyle("Normal").getCTStyle().xmlText()
                    + doc.getStyles().getStyle("Heading1").getCTStyle().xmlText()
                    + doc.getStyles().getStyle("Hyperlink").getCTStyle().xmlText()
                    + doc.getStyles().getStyle("SourceCode").getCTStyle().xmlText();
            assertTrue(styles.contains("Georgia"), "heading font");
            assertTrue(styles.contains("123456"), "accent colour");
            assertTrue(styles.contains("Courier New"), "code font");
            assertTrue(styles.contains("\"both\""), "justified");
        }
    }

    @Test
    void pdfEmbedsTheThemeFonts() throws Exception {
        Assumptions.assumeTrue(PdfFonts.isInstalled(ThemeFont.GEORGIA), "Georgia is not installed");
        ExportOptions options = ExportOptions.defaults().setTheme(ExportTheme.CLASSIC);
        Path out = service.export(document(), ExportFormat.PDF, dir.resolve("classic.pdf"), options, null);
        Set<String> fonts = new HashSet<>();
        try (PDDocument pdf = Loader.loadPDF(out.toFile())) {
            for (PDPage page : pdf.getPages()) {
                for (var name : page.getResources().getFontNames()) {
                    PDFont font = page.getResources().getFont(name);
                    fonts.add(font.getName());
                }
            }
        }
        assertTrue(fonts.stream().anyMatch(f -> f.contains("Georgia")), "fonts: " + fonts);
    }
}
