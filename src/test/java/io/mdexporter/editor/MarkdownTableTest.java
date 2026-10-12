package io.mdexporter.editor;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownTableTest {

    @Test
    void formatsAndAlignsColumns() {
        String text = """
                Name|Value|Note
                :--|--:|:-:
                Alpha|1|x
                Beta|22
                """.strip();
        String expected = """
                | Name  | Value | Note  |
                | :---- | ----: | :---: |
                | Alpha |     1 |   x   |
                | Beta  |    22 |       |""";
        assertEquals(expected, MarkdownTable.formatAll(text));
        assertEquals(expected, MarkdownTable.formatAll(expected), "formatting is idempotent");
    }

    @Test
    void keepsEscapedPipesAndWideCharacters() {
        String text = "| a | b |\n|---|---|\n| x \\| y | 日本 |";
        String expected = """
                | a      | b    |
                | ------ | ---- |
                | x \\| y | 日本 |""";
        assertEquals(expected, MarkdownTable.formatAll(text));
        assertEquals(4, MarkdownTable.displayWidth("日本"));
        assertEquals(1, MarkdownTable.displayWidth("é"));
    }

    @Test
    void ignoresTablesInCodeBlocksAndNonTables() {
        String text = "```\n|a|b|\n|-|-|\n```\n\na | b\nnot a table\n";
        assertEquals(text, MarkdownTable.formatAll(text));
        assertTrue(MarkdownTable.findAll(text).isEmpty());
    }

    @Test
    void formatsEveryTableAndKeepsSurroundingText() {
        String text = "# T\n\n|a|b|\n|-|-|\n|1|2|\n\ntext | with pipe\n\n|c|\n|-|\n|3|\n";
        String result = MarkdownTable.formatAll(text);
        assertEquals("# T\n\n| a   | b   |\n| --- | --- |\n| 1   | 2   |\n\ntext | with pipe\n\n| c   |\n| --- |\n| 3   |\n",
                result);
    }

    @Test
    void locatesCaretCell() {
        String text = "intro\n\n| a | bb |\n|---|---|\n| 1 | 22 |\n";
        int pos = text.indexOf("22") + 1;
        MarkdownTable.Located t = MarkdownTable.find(text, pos);
        assertNotNull(t);
        assertEquals(1, t.caretRow());
        assertEquals(1, t.caretCol());
        assertEquals(1, t.caretSkip());
        assertEquals(text.indexOf("| a"), t.start());
        assertEquals(text.length() - 1, t.end());
        assertNull(MarkdownTable.find(text, 2));
    }

    @Test
    void editsRowsAndColumns() {
        MarkdownTable t = MarkdownTable.parse(List.of("|a|b|", "|-|-|", "|1|2|"));
        t.insertColumn(1);
        t.insertRow(1);
        t.align(0, MarkdownTable.Align.RIGHT);
        assertEquals("""
                |   a |     | b   |
                | --: | --- | --- |
                |     |     |     |
                |   1 |     | 2   |""", t.format().text());
        t.deleteColumn(1);
        t.deleteRow(1);
        t.moveColumn(0, 1);
        assertEquals("""
                | b   |   a |
                | --- | --: |
                | 2   |   1 |""", t.format().text());
    }

    @Test
    void recognisesDelimiterRows() {
        assertTrue(MarkdownTable.isDelimiterRow("|---|:--:|"));
        assertTrue(MarkdownTable.isDelimiterRow("--- | ---"));
        assertFalse(MarkdownTable.isDelimiterRow("---"));
        assertFalse(MarkdownTable.isDelimiterRow("| a | b |"));
    }

    @Test
    void createsNewTables() {
        String text = MarkdownTable.create(2, 1, null, MarkdownTable.Align.NONE).format().text();
        assertEquals("""
                | Column 1 | Column 2 |
                | -------- | -------- |
                |          |          |""", text);
    }

    @Test
    void diffsWords() {
        List<TextDiff.Op> ops = TextDiff.diff("The quick fox\nsecond line\n", "The slow fox\nsecond line\n");
        assertEquals(List.of(
                new TextDiff.Op(TextDiff.Type.EQUAL, "The "),
                new TextDiff.Op(TextDiff.Type.DELETE, "quick"),
                new TextDiff.Op(TextDiff.Type.INSERT, "slow"),
                new TextDiff.Op(TextDiff.Type.EQUAL, " fox\nsecond line\n")), ops);
    }
}
