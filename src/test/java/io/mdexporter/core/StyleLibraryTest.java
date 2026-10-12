package io.mdexporter.core;

import io.mdexporter.diagram.DiagramService;
import io.mdexporter.diagram.PlantUmlRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StyleLibraryTest {

    @TempDir
    Path dir;

    @Test
    void savesFindsAndDeletesCustomStyles() throws Exception {
        StyleLibrary library = new StyleLibrary(dir.resolve("styles"));
        assertEquals(ExportTheme.values().length, library.all().size());

        StyleDefinition copy = ExportTheme.CLASSIC.definition().asCustom("Corporate Blue");
        StyleDefinition saved = library.save(copy);
        assertEquals("corporate-blue", saved.id());
        assertFalse(saved.builtIn());
        assertEquals("custom:corporate-blue", saved.key());
        assertTrue(Files.isRegularFile(dir.resolve("styles/corporate-blue.json")));

        StyleDefinition second = library.save(copy);
        assertEquals("corporate-blue-2", second.id(), "ids are unique");

        StyleDefinition found = library.find("custom:corporate-blue").orElseThrow();
        assertTrue(found.sameContent(saved));
        assertEquals("Corporate Blue", library.find("corporate blue").orElseThrow().name(), "found by name");
        assertEquals(ExportTheme.MODERN.definition(), library.find("modern").orElseThrow());

        StyleDefinition renamed = library.save(saved.withName("Corporate"));
        assertEquals("corporate-blue", renamed.id(), "saving keeps the file of an existing style");
        library.delete(second);
        assertEquals(List.of("Corporate"), library.custom().stream().map(StyleDefinition::name).toList());
    }

    @Test
    void jsonRoundTripsEveryField() {
        StyleDefinition s = new StyleDefinition("x", "Sepia", "Book look",
                new ExportTheme.Palette("3B2F2F", "7A6A5A", "D8C8B0", "F3EBDD", "FBF6EC", "8B1E3F", "5A2A1A",
                        "EFE4D0", "EADCC4"),
                ThemeFont.PALATINO, ThemeFont.GEORGIA, ThemeFont.COURIER_NEW, false, true, false,
                "h1 { text-transform: uppercase; }", false);
        StyleDefinition back = StyleLibrary.fromJson(StyleLibrary.toJson(s), ExportTheme.GITHUB.definition());
        assertTrue(back.sameContent(s));
    }

    @Test
    void readsLenientAiAnswers() {
        String answer = """
                Here is your style:
                ```json
                { "name": "Forest", "colors": { "accent": "#2d6a4f", "text": "not a colour" },
                  "fonts": { "text": "Comic Sans", "headings": "times new roman", "code": "Georgia" },
                  "coloredHeadings": true }
                ```""";
        StyleDefinition base = ExportTheme.GITHUB.definition();
        StyleDefinition s = StyleLibrary.fromJson(answer, base);
        assertEquals("Forest", s.name());
        assertEquals("2D6A4F", s.palette().accent());
        assertEquals(base.palette().text(), s.palette().text(), "invalid colour keeps the base colour");
        assertEquals(base.bodyFont(), s.bodyFont(), "unknown font keeps the base font");
        assertEquals(ThemeFont.TIMES, s.headingFont());
        assertEquals(base.codeFont(), s.codeFont(), "a text font is not accepted as code font");
        assertTrue(s.accentHeadings());
        assertThrows(IllegalArgumentException.class, () -> StyleLibrary.fromJson("no json here", base));
    }

    @Test
    void customStyleCssReachesTheExport() throws Exception {
        StyleDefinition style = ExportTheme.MINIMAL.definition().asCustom("Spaced");
        style = new StyleDefinition(null, style.name(), "", style.palette(), style.bodyFont(), style.headingFont(),
                style.codeFont(), true, false, false, "h1 { letter-spacing: 4px; }", false);
        ExportOptions options = ExportOptions.defaults().setStyleDefinition(style);
        MarkdownDocument doc = MarkdownParser.parse("# Title\n\nText", dir, "doc");
        ExportService service = new ExportService(new DiagramService(List.of(new PlantUmlRenderer())));
        Path html = service.export(doc, ExportFormat.HTML, dir.resolve("doc.html"), options, null);
        assertTrue(Files.readString(html).contains("letter-spacing: 4px"));
        Path pdf = service.export(doc, ExportFormat.PDF, dir.resolve("doc.pdf"), options, null);
        assertTrue(Files.size(pdf) > 1000);
    }

    @Test
    void slugsNames() {
        assertEquals("cafe-creme", StyleLibrary.slug("Café Crème!"));
        assertEquals("style", StyleLibrary.slug("???"));
    }
}
