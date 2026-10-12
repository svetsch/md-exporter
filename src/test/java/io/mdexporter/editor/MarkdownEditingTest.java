package io.mdexporter.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownEditingTest {

    /** Applies the edit and renders the selection as [ and ] (or | for a caret). */
    private static String run(String text, TextEdit edit) {
        String result = edit.apply(text);
        int a = Math.min(edit.anchor(), edit.caret());
        int c = Math.max(edit.anchor(), edit.caret());
        if (a == c) {
            return result.substring(0, a) + "|" + result.substring(a);
        }
        return result.substring(0, a) + "[" + result.substring(a, c) + "]" + result.substring(c);
    }

    /** Text with [selection] or | caret markers -> edit result with markers. */
    private interface Op {
        TextEdit apply(String text, int start, int end);
    }

    private static String edit(String marked, Op op) {
        int caret = marked.indexOf('|');
        String text;
        int s;
        int e;
        if (caret >= 0) {
            text = marked.substring(0, caret) + marked.substring(caret + 1);
            s = e = caret;
        } else {
            s = marked.indexOf('[');
            e = marked.indexOf(']') - 1;
            text = marked.replaceFirst("\\[", "").replaceFirst("]", "");
        }
        return run(text, op.apply(text, s, e));
    }

    @Test
    void togglesBoldAroundSelectionAndWord() {
        assertEquals("a **[bold]** b", edit("a [bold] b", (t, s, e) -> MarkdownEditing.toggleInline(t, s, e, "**", "**")));
        assertEquals("a [bold] b", edit("a **[bold]** b", (t, s, e) -> MarkdownEditing.toggleInline(t, s, e, "**", "**")));
        assertEquals("a [bold] b", edit("a [**bold**] b", (t, s, e) -> MarkdownEditing.toggleInline(t, s, e, "**", "**")));
        assertEquals("a **wo|rd** b", edit("a wo|rd b", (t, s, e) -> MarkdownEditing.toggleInline(t, s, e, "**", "**")));
        assertEquals("a wo|rd b", edit("a **wo|rd** b", (t, s, e) -> MarkdownEditing.toggleInline(t, s, e, "**", "**")));
        assertEquals("a **|** b", edit("a | b", (t, s, e) -> MarkdownEditing.toggleInline(t, s, e, "**", "**")));
        // whitespace stays outside of the markers
        assertEquals("a **[bold]**  b", edit("a [bold ] b", (t, s, e) -> MarkdownEditing.toggleInline(t, s, e, "**", "**")));
    }

    @Test
    void italicDoesNotConfuseBold() {
        Op italic = (t, s, e) -> MarkdownEditing.toggleInline(t, s, e, "*", "*");
        assertEquals("***[x]***", edit("**[x]**", italic));
        assertEquals("**[x]**", edit("***[x]***", italic));
        assertEquals("[x]", edit("*[x]*", italic));
    }

    @Test
    void togglesMarkersOnEachLine() {
        Op bold = (t, s, e) -> MarkdownEditing.toggleInline(t, s, e, "**", "**");
        assertEquals("[- **one**\n- **two**]", edit("[- one\n- two]", bold));
        assertEquals("[- one\n- two]", edit("[- **one**\n- **two**]", bold));
    }

    @Test
    void setsAndTogglesHeadings() {
        assertEquals("## Ti|tle", edit("Ti|tle", (t, s, e) -> MarkdownEditing.setHeading(t, s, e, 2)));
        assertEquals("### Ti|tle", edit("# Ti|tle", (t, s, e) -> MarkdownEditing.setHeading(t, s, e, 3)));
        assertEquals("Ti|tle", edit("## Ti|tle", (t, s, e) -> MarkdownEditing.setHeading(t, s, e, 2)));
        assertEquals("Ti|tle", edit("## Ti|tle", (t, s, e) -> MarkdownEditing.setHeading(t, s, e, 0)));
    }

    @Test
    void togglesAndConvertsLists() {
        Op numbered = (t, s, e) -> MarkdownEditing.toggleList(t, s, e, MarkdownEditing.ListKind.NUMBERED);
        Op bullet = (t, s, e) -> MarkdownEditing.toggleList(t, s, e, MarkdownEditing.ListKind.BULLET);
        Op task = (t, s, e) -> MarkdownEditing.toggleList(t, s, e, MarkdownEditing.ListKind.TASK);
        assertEquals("[1. a\n2. b\n   1. c\n3. d]", edit("[a\nb\n   c\nd]", numbered));
        assertEquals("[- a\n- b]", edit("[1. a\n2. b]", bullet));
        assertEquals("[a\nb]", edit("[- a\n- b]", bullet));
        assertEquals("- [ ] it|em", edit("- it|em", task));
        assertEquals("- it|em", edit("it|em", bullet));
    }

    @Test
    void togglesQuotes() {
        Op quote = (t, s, e) -> MarkdownEditing.toggleList(t, s, e, MarkdownEditing.ListKind.QUOTE);
        assertEquals("[> a\n>\n> b]", edit("[a\n\nb]", quote));
        assertEquals("[a\n\nb]", edit("[> a\n>\n> b]", quote));
    }

    @Test
    void continuesListsOnEnter() {
        Op enter = MarkdownEditing::continueList;
        assertEquals("- one\n- |", edit("- one|", enter));
        assertEquals("1. one\n2. |", edit("1. one|", enter));
        assertEquals("  - [x] done\n  - [ ] |", edit("  - [x] done|", enter));
        assertEquals("> quote\n> |", edit("> quote|", enter));
        assertEquals("- one\n|", edit("- one\n- |", enter)); // empty item ends the list
        assertNull(MarkdownEditing.continueList("plain", 5, 5));
        assertNull(MarkdownEditing.continueList("```\n- x\n```", 7, 7));
    }

    @Test
    void indentsListItemsUnderTheirParent() {
        assertEquals("1. one\n   2. |two", edit("1. one\n2. |two", MarkdownEditing::indent));
        assertEquals("- one\n  - |two", edit("- one\n- |two", MarkdownEditing::indent));
        assertEquals("- one\n- |two", edit("- one\n  - |two", MarkdownEditing::outdent));
    }

    @Test
    void insertsBlocksOnTheirOwnLines() {
        assertEquals("text\n\n```java\n|\n```\n", edit("text|", (t, s, e) -> MarkdownEditing.codeBlock(t, s, e, "java")));
        assertEquals("a\n\n```\nx\n```|\n\nb", edit("a\n\n[x]\n\nb", (t, s, e) -> MarkdownEditing.codeBlock(t, s, e, "")));
    }

    @Test
    void addsNumberedFootnotes() {
        assertEquals("Text[^2] more\n\n[^1]: one\n[^2]: |", edit("Text| more\n\n[^1]: one\n",
                (t, s, e) -> MarkdownEditing.footnote(t, s, e)));
        assertEquals("Text[^1]\n\n[^1]: |", edit("Text|", (t, s, e) -> MarkdownEditing.footnote(t, s, e)));
    }

    @Test
    void clearsFormatting() {
        String text = "**bold** *italic* [link](http://x) `code` ![img](a.png)";
        assertEquals("bold italic link code ![img](a.png)",
                MarkdownEditing.clearFormatting(text, 0, text.length()).apply(text));
        assertEquals("a wo|rd b", edit("a **wo|rd** b", MarkdownEditing::clearFormatting).replace("[word]", "wo|rd"));
    }

    @Test
    void detectsFencedCode() {
        String text = "a\n```\ncode\n```\nb";
        assertFalse(MarkdownEditing.isInFence(text, 0));
        assertTrue(MarkdownEditing.isInFence(text, 7));
        assertFalse(MarkdownEditing.isInFence(text, text.length()));
    }

    @Test
    void computesMinimalEdits() {
        TextEdit edit = TextEdit.between("hello world", "hello brave world");
        assertEquals(6, edit.start());
        assertEquals(6, edit.end());
        assertEquals("brave ", edit.replacement());
        assertEquals("hello brave world", edit.apply("hello world"));
    }

    @Test
    void createsLinksAndImages() {
        assertEquals("[x](https://a.b \"T\")", MarkdownEditing.link("x", "https://a.b", "T"));
        assertEquals("![alt](<my image.png>){width=200}", MarkdownEditing.image("alt", "my image.png", "", "200"));
    }
}
