package io.mdexporter.editor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown editing operations on plain text. Every operation takes the text and the selection
 * ({@code start <= end}) and returns the {@link TextEdit} to apply, so that it can be tested without a UI.
 */
public final class MarkdownEditing {

    /** In templates: replaced by the selected text. */
    public static final String SELECTION = "{{selection}}";
    /** In templates: selection anchor (or caret when {@link #MARK_END} is absent). */
    public static final char MARK_START = '\u0001';
    /** In templates: end of the selection after insertion. */
    public static final char MARK_END = '\u0002';

    public enum ListKind { BULLET, NUMBERED, TASK, QUOTE }

    private static final Pattern HEADING = Pattern.compile("^(\\s{0,3})(#{1,6})(?:[ \\t]+|$)");
    private static final Pattern LIST_ITEM = Pattern.compile("^(\\s*)([-*+] \\[[ xX]\\] |[-*+] |\\d{1,9}[.)] )");
    private static final Pattern QUOTE = Pattern.compile("^(\\s*)> ?");
    private static final Pattern CONTINUATION = Pattern.compile(
            "^(\\s*)(?:([-*+]) (\\[[ xX]\\] )?|(\\d{1,9})([.)]) |(>) ?)");
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})");
    private static final Pattern FOOTNOTE_REF = Pattern.compile("\\[\\^(\\d+)]");
    private static final Pattern FOOTNOTE_DEF = Pattern.compile("^\\[\\^[^\\]]+]:");
    private static final int INDENT = 4;

    private MarkdownEditing() {
    }

    // ------------------------------------------------------------------ line helpers

    public static int lineStart(String text, int pos) {
        return text.lastIndexOf('\n', pos - 1) + 1;
    }

    public static int lineEnd(String text, int pos) {
        int i = text.indexOf('\n', pos);
        return i < 0 ? text.length() : i;
    }

    /** Zero based line number of {@code pos}. */
    public static int lineIndex(String text, int pos) {
        int line = 0;
        int limit = Math.min(pos, text.length());
        for (int i = 0; i < limit; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /** Offset of the start of line {@code line} (clamped to the text). */
    public static int lineOffset(String text, int line) {
        int pos = 0;
        for (int i = 0; i < line; i++) {
            int next = text.indexOf('\n', pos);
            if (next < 0) {
                return text.length();
            }
            pos = next + 1;
        }
        return pos;
    }

    /** Full lines covered by the selection; a selection ending at the start of a line excludes that line. */
    static int[] lineBlock(String text, int start, int end) {
        int blockStart = lineStart(text, start);
        int last = end > start && text.charAt(end - 1) == '\n' ? end - 1 : end;
        return new int[]{blockStart, lineEnd(text, Math.max(last, blockStart))};
    }

    /** Whether {@code pos} lies inside a fenced code block (the fence lines included). */
    public static boolean isInFence(String text, int pos) {
        String fence = null;
        int target = lineStart(text, pos);
        int ls = 0;
        while (true) {
            int le = lineEnd(text, ls);
            String line = text.substring(ls, le);
            Matcher m = FENCE.matcher(line);
            boolean closes = false;
            if (m.find()) {
                String marker = m.group(1);
                if (fence == null) {
                    fence = marker;
                } else {
                    closes = marker.charAt(0) == fence.charAt(0) && marker.length() >= fence.length()
                            && line.trim().length() == marker.length();
                }
            }
            if (ls == target) {
                return fence != null;
            }
            if (closes) {
                fence = null;
            }
            if (le >= text.length()) {
                return false;
            }
            ls = le + 1;
        }
    }

    // ------------------------------------------------------------------ inline formatting

    /** Toggles inline markers such as {@code **} around the selection, or around the word at the caret. */
    public static TextEdit toggleInline(String text, int start, int end, String open, String close) {
        int caret = start;
        boolean collapsed = start == end;
        if (collapsed) {
            int[] word = wordAt(text, start);
            if (word == null) {
                int before = start - open.length();
                if (before >= 0 && text.startsWith(open, before) && text.startsWith(close, start)) {
                    return new TextEdit(before, start + close.length(), "", before, before);
                }
                int pos = start + open.length();
                return new TextEdit(start, end, open + close, pos, pos);
            }
            start = word[0];
            end = word[1];
        }
        String selected = text.substring(start, end);
        if (selected.indexOf('\n') >= 0) {
            return toggleInlineLines(text, start, end, open, close);
        }
        int ts = start;
        int te = end;
        while (ts < te && Character.isWhitespace(text.charAt(ts))) {
            ts++;
        }
        while (te > ts && Character.isWhitespace(text.charAt(te - 1))) {
            te--;
        }
        if (ts == te) {
            int pos = start + open.length();
            return new TextEdit(start, start, open + close, pos, pos);
        }
        String inner = text.substring(ts, te);
        TextEdit edit;
        if (wrappedInside(inner, open, close)) {
            String unwrapped = inner.substring(open.length(), inner.length() - close.length());
            edit = new TextEdit(ts, te, unwrapped, ts, ts + unwrapped.length());
            if (collapsed) {
                int c = Math.max(ts, Math.min(caret - open.length(), ts + unwrapped.length()));
                return new TextEdit(ts, te, unwrapped, c, c);
            }
        } else if (wrappedOutside(text, ts, te, open, close)) {
            int from = ts - open.length();
            edit = new TextEdit(from, te + close.length(), inner, from, from + inner.length());
            if (collapsed) {
                int c = caret - open.length();
                return new TextEdit(from, te + close.length(), inner, c, c);
            }
        } else {
            String wrapped = open + inner + close;
            edit = new TextEdit(ts, te, wrapped, ts + open.length(), ts + open.length() + inner.length());
            if (collapsed) {
                int c = caret + open.length();
                return new TextEdit(ts, te, wrapped, c, c);
            }
        }
        return edit;
    }

    /** Multi-line selections: markers are toggled on each non-blank line (emphasis cannot span paragraphs). */
    private static TextEdit toggleInlineLines(String text, int start, int end, String open, String close) {
        String[] lines = text.substring(start, end).split("\n", -1);
        boolean allWrapped = true;
        boolean any = false;
        for (String line : lines) {
            String content = lineContent(line);
            if (!content.isBlank()) {
                any = true;
                allWrapped &= wrappedInside(content.strip(), open, close);
            }
        }
        if (!any) {
            return new TextEdit(start, end, text.substring(start, end), start, end);
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            String line = lines[i];
            String content = lineContent(line);
            if (content.isBlank()) {
                out.append(line);
                continue;
            }
            String prefix = line.substring(0, line.length() - content.length());
            String lead = content.substring(0, content.length() - content.stripLeading().length());
            String trail = content.substring(content.stripTrailing().length());
            String core = content.strip();
            if (allWrapped) {
                core = core.substring(open.length(), core.length() - close.length());
            } else if (!wrappedInside(core, open, close)) {
                core = open + core + close;
            }
            out.append(prefix).append(lead).append(core).append(trail);
        }
        String replacement = out.toString();
        return new TextEdit(start, end, replacement, start, start + replacement.length());
    }

    /** The line without its block prefix (indentation, list marker, quote marker, heading marker). */
    private static String lineContent(String line) {
        int pos = 0;
        boolean changed = true;
        while (changed) {
            changed = false;
            String rest = line.substring(pos);
            for (Pattern p : new Pattern[]{QUOTE, LIST_ITEM, HEADING}) {
                Matcher m = p.matcher(rest);
                if (m.find() && m.end() > 0) {
                    pos += m.end();
                    changed = true;
                    break;
                }
            }
        }
        return line.substring(pos);
    }

    private static boolean wrappedInside(String s, String open, String close) {
        if (s.length() < open.length() + close.length() || !s.startsWith(open) || !s.endsWith(close)) {
            return false;
        }
        if (!isRun(open)) {
            return true;
        }
        char c = open.charAt(0);
        int before = run(s, 0, c, 1);
        int after = run(s, s.length() - 1, c, -1);
        if (before == s.length()) {
            return false; // only markers
        }
        return runMatches(c, open.length(), before, after);
    }

    private static boolean wrappedOutside(String text, int ts, int te, String open, String close) {
        int from = ts - open.length();
        if (from < 0 || te + close.length() > text.length()
                || !text.startsWith(open, from) || !text.startsWith(close, te)) {
            return false;
        }
        if (!isRun(open)) {
            return true;
        }
        char c = open.charAt(0);
        return runMatches(c, open.length(), run(text, ts - 1, c, -1), run(text, te, c, 1));
    }

    /** {@code *} runs: 1 = italic, 2 = bold, 3 = both. Other markers just need to be long enough. */
    private static boolean runMatches(char c, int n, int before, int after) {
        if ((c == '*' || c == '_') && n == 1) {
            return before % 2 == 1 && after % 2 == 1;
        }
        return before >= n && after >= n;
    }

    private static boolean isRun(String marker) {
        return marker.chars().allMatch(ch -> ch == marker.charAt(0));
    }

    private static int run(String s, int from, char c, int step) {
        int n = 0;
        for (int i = from; i >= 0 && i < s.length() && s.charAt(i) == c; i += step) {
            n++;
        }
        return n;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '\'';
    }

    /** Bounds of the word touching {@code pos}, or {@code null}. */
    static int[] wordAt(String text, int pos) {
        int s = pos;
        int e = pos;
        while (s > 0 && isWordChar(text.charAt(s - 1))) {
            s--;
        }
        while (e < text.length() && isWordChar(text.charAt(e))) {
            e++;
        }
        return s == e ? null : new int[]{s, e};
    }

    private static final Pattern FORMATTING = Pattern.compile(
            "\\*\\*|__|~~|\\+\\+|`|</?(?:sub|sup|kbd|u|mark|ins|del|b|i|em|strong|s)>");
    private static final Pattern EMPHASIS = Pattern.compile("(?<![\\w*])[*_](?=\\S)(.+?)(?<=\\S)[*_](?![\\w*])");
    private static final Pattern LINK = Pattern.compile("(?<!!)\\[([^\\]]*)]\\([^)]*\\)");

    /** Removes inline formatting (emphasis, code, links, simple HTML tags) from the selection. */
    public static TextEdit clearFormatting(String text, int start, int end) {
        if (start == end) {
            int[] word = wordAt(text, start);
            if (word == null) {
                return new TextEdit(start, end, "", start, end);
            }
            // extend over the surrounding markers
            int s = word[0];
            int e = word[1];
            while (s > 0 && "*_~`+".indexOf(text.charAt(s - 1)) >= 0) {
                s--;
            }
            while (e < text.length() && "*_~`+".indexOf(text.charAt(e)) >= 0) {
                e++;
            }
            start = s;
            end = e;
        }
        String s = text.substring(start, end);
        s = LINK.matcher(s).replaceAll("$1");
        s = FORMATTING.matcher(s).replaceAll("");
        s = EMPHASIS.matcher(s).replaceAll("$1");
        return new TextEdit(start, end, s, start, start + s.length());
    }

    // ------------------------------------------------------------------ block formatting

    /** Sets the heading level (0 = normal paragraph) of the selected lines; the same level again removes it. */
    public static TextEdit setHeading(String text, int start, int end, int level) {
        int[] block = lineBlock(text, start, end);
        String[] lines = text.substring(block[0], block[1]).split("\n", -1);
        boolean allSame = level > 0;
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            Matcher m = HEADING.matcher(line);
            allSame &= m.find() && m.group(2).length() == level;
        }
        int target = allSame ? 0 : level;
        List<String> result = new ArrayList<>();
        for (String line : lines) {
            if (line.isBlank() && lines.length > 1) {
                result.add(line);
                continue;
            }
            Matcher m = HEADING.matcher(line);
            String content = m.find() ? line.substring(m.end()) : line.stripLeading();
            result.add(target == 0 ? content : "#".repeat(target) + " " + content);
        }
        return lineEdit(text, start, end, block, lines, result);
    }

    /** Toggles list (or quote) markers on the selected lines; other list markers are converted. */
    public static TextEdit toggleList(String text, int start, int end, ListKind kind) {
        int[] block = lineBlock(text, start, end);
        String[] lines = text.substring(block[0], block[1]).split("\n", -1);
        if (kind == ListKind.QUOTE) {
            return toggleQuote(text, start, end, block, lines);
        }
        boolean allHave = true;
        boolean any = false;
        for (String line : lines) {
            if (!line.isBlank()) {
                any = true;
                allHave &= kindOf(line) == kind;
            }
        }
        allHave &= any;
        List<String> result = new ArrayList<>();
        Map<Integer, Integer> counters = new HashMap<>();
        for (String line : lines) {
            if (line.isBlank()) {
                result.add(line);
                continue;
            }
            Matcher m = LIST_ITEM.matcher(line);
            String indent;
            String content;
            if (m.find()) {
                indent = m.group(1);
                content = line.substring(m.end());
            } else {
                indent = line.substring(0, line.length() - line.stripLeading().length());
                content = line.stripLeading();
            }
            if (allHave) {
                result.add(indent + content);
                continue;
            }
            int depth = indent.length();
            counters.keySet().removeIf(d -> d > depth);
            String marker = switch (kind) {
                case BULLET -> "- ";
                case TASK -> "- [ ] ";
                default -> {
                    int n = counters.merge(depth, 1, Integer::sum);
                    yield n + ". ";
                }
            };
            result.add(indent + marker + content);
        }
        return lineEdit(text, start, end, block, lines, result);
    }

    private static final Pattern TASK = Pattern.compile("^(\\s*[-*+] \\[)([ xX])(] )");

    /** Ticks / unticks the task items of the selected lines; other lines become tasks. */
    public static TextEdit toggleTaskDone(String text, int start, int end) {
        int[] block = lineBlock(text, start, end);
        String[] lines = text.substring(block[0], block[1]).split("\n", -1);
        boolean anyTask = false;
        boolean allDone = true;
        for (String line : lines) {
            Matcher m = TASK.matcher(line);
            if (m.find()) {
                anyTask = true;
                allDone &= !m.group(2).equals(" ");
            }
        }
        if (!anyTask) {
            return toggleList(text, start, end, ListKind.TASK);
        }
        List<String> result = new ArrayList<>();
        for (String line : lines) {
            Matcher m = TASK.matcher(line);
            result.add(m.find() ? m.group(1) + (allDone ? " " : "x") + m.group(3) + line.substring(m.end()) : line);
        }
        TextEdit edit = lineEdit(text, start, end, block, lines, result);
        // the line length does not change: keep the selection as it was
        return new TextEdit(edit.start(), edit.end(), edit.replacement(), start, end);
    }

    private static ListKind kindOf(String line) {
        Matcher m = LIST_ITEM.matcher(line);
        if (!m.find()) {
            return null;
        }
        String marker = m.group(2);
        if (marker.contains("[")) {
            return ListKind.TASK;
        }
        return Character.isDigit(marker.charAt(0)) ? ListKind.NUMBERED : ListKind.BULLET;
    }

    private static TextEdit toggleQuote(String text, int start, int end, int[] block, String[] lines) {
        boolean allQuoted = true;
        boolean any = false;
        for (String line : lines) {
            if (!line.isBlank()) {
                any = true;
                allQuoted &= QUOTE.matcher(line).find();
            }
        }
        allQuoted &= any;
        List<String> result = new ArrayList<>();
        for (String line : lines) {
            if (allQuoted) {
                Matcher m = QUOTE.matcher(line);
                result.add(m.find() ? m.group(1) + line.substring(m.end()) : line);
            } else {
                result.add(line.isBlank() ? ">" : "> " + line);
            }
        }
        return lineEdit(text, start, end, block, lines, result);
    }

    /** Indents the selected lines; list items are nested under the previous item. */
    public static TextEdit indent(String text, int start, int end) {
        int[] block = lineBlock(text, start, end);
        String[] lines = text.substring(block[0], block[1]).split("\n", -1);
        String first = lines[0];
        int current = leadingSpaces(first);
        int delta = INDENT;
        Matcher m = LIST_ITEM.matcher(first);
        if (m.find()) {
            int parentContent = previousListContentColumn(text, block[0], current, true);
            delta = parentContent > current ? parentContent - current : 2;
        }
        String pad = " ".repeat(delta);
        List<String> result = new ArrayList<>();
        for (String line : lines) {
            result.add(line.isEmpty() ? line : pad + line);
        }
        return lineEdit(text, start, end, block, lines, result);
    }

    /** Removes one indentation level from the selected lines. */
    public static TextEdit outdent(String text, int start, int end) {
        int[] block = lineBlock(text, start, end);
        String[] lines = text.substring(block[0], block[1]).split("\n", -1);
        int current = leadingSpaces(lines[0]);
        int delta = Math.min(current, INDENT);
        if (LIST_ITEM.matcher(lines[0]).find() && current > 0) {
            int parentIndent = previousListIndent(text, block[0], current);
            delta = parentIndent >= 0 ? current - parentIndent : current;
        }
        if (delta <= 0 && !lines[0].startsWith("\t")) {
            delta = INDENT;
        }
        List<String> result = new ArrayList<>();
        for (String line : lines) {
            if (line.startsWith("\t")) {
                result.add(line.substring(1));
            } else {
                result.add(line.substring(Math.min(delta, leadingSpaces(line))));
            }
        }
        return lineEdit(text, start, end, block, lines, result);
    }

    private static int leadingSpaces(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ') {
            n++;
        }
        return n;
    }

    /** Content column of the closest list item above with an indentation {@code <= indent}, or -1. */
    private static int previousListContentColumn(String text, int lineStart, int indent, boolean sameLevel) {
        int pos = lineStart;
        while (pos > 0) {
            int prevStart = lineStart(text, pos - 1);
            String line = text.substring(prevStart, pos - 1);
            pos = prevStart;
            if (line.isBlank()) {
                continue;
            }
            Matcher m = LIST_ITEM.matcher(line);
            if (!m.find()) {
                if (leadingSpaces(line) <= indent) {
                    return -1;
                }
                continue;
            }
            int lineIndent = m.group(1).length();
            if (lineIndent == indent && sameLevel || lineIndent < indent) {
                return m.end();
            }
        }
        return -1;
    }

    /** Indentation of the closest list item above with an indentation {@code < indent}, or -1. */
    private static int previousListIndent(String text, int lineStart, int indent) {
        int pos = lineStart;
        while (pos > 0) {
            int prevStart = lineStart(text, pos - 1);
            String line = text.substring(prevStart, pos - 1);
            pos = prevStart;
            Matcher m = LIST_ITEM.matcher(line);
            if (m.find() && m.group(1).length() < indent) {
                return m.group(1).length();
            }
        }
        return -1;
    }

    /** Builds the edit for a line based operation, keeping the caret on the same content where possible. */
    private static TextEdit lineEdit(String text, int start, int end, int[] block, String[] before,
                                     List<String> after) {
        String replacement = String.join("\n", after);
        if (start == end && before.length == 1) {
            String oldLine = before[0];
            String newLine = after.get(0);
            int oldPrefix = oldLine.length() - lineContent(oldLine).length();
            int newPrefix = newLine.length() - lineContent(newLine).length();
            int offset = Math.max(0, start - block[0] - oldPrefix);
            int caret = block[0] + Math.min(newLine.length(), newPrefix + offset);
            return new TextEdit(block[0], block[1], replacement, caret, caret);
        }
        return new TextEdit(block[0], block[1], replacement, block[0], block[0] + replacement.length());
    }

    // ------------------------------------------------------------------ Enter in lists

    /**
     * Enter at the end (or in the middle) of a list item / quote line: continues the list with a new marker. On an
     * empty item the marker is removed instead. Returns {@code null} when Enter needs no special handling.
     */
    public static TextEdit continueList(String text, int start, int end) {
        if (start != end) {
            return null;
        }
        int ls = lineStart(text, start);
        int le = lineEnd(text, start);
        String line = text.substring(ls, le);
        Matcher m = CONTINUATION.matcher(line);
        if (!m.find() || start - ls < m.end() || isInFence(text, start)) {
            return null;
        }
        String indent = m.group(1);
        String rest = line.substring(m.end());
        if (rest.isBlank() && start == le) {
            // empty item: end the list
            return new TextEdit(ls, le, "", ls, ls);
        }
        String marker;
        if (m.group(2) != null) {
            marker = m.group(2) + " " + (m.group(3) != null ? "[ ] " : "");
        } else if (m.group(4) != null) {
            marker = (Long.parseLong(m.group(4)) + 1) + m.group(5) + " ";
        } else {
            marker = "> ";
        }
        String insert = "\n" + indent + marker;
        int caret = start + insert.length();
        return new TextEdit(start, end, insert, caret, caret);
    }

    // ------------------------------------------------------------------ insertion

    /**
     * Replaces the selection with {@code template}: {@link #SELECTION} is replaced by the selected text,
     * {@link #MARK_START}/{@link #MARK_END} define the selection afterwards. Block templates are separated from the
     * surrounding text by blank lines.
     */
    public static TextEdit insert(String text, int start, int end, String template, boolean block) {
        String selected = text.substring(start, end);
        String t = template.replace(SELECTION, selected);
        String prefix = "";
        String suffix = "";
        if (block) {
            String before = text.substring(0, start);
            String after = text.substring(end);
            if (!before.isEmpty()) {
                if (!before.endsWith("\n")) {
                    prefix = "\n\n";
                } else if (!before.endsWith("\n\n") && before.length() > 1) {
                    prefix = "\n";
                }
            }
            if (!after.isEmpty()) {
                if (!after.startsWith("\n")) {
                    suffix = "\n\n";
                } else if (!after.startsWith("\n\n")) {
                    suffix = "\n";
                }
            } else {
                suffix = "\n";
            }
        }
        int a = t.indexOf(MARK_START);
        if (a >= 0) {
            t = t.substring(0, a) + t.substring(a + 1);
        }
        int b = t.indexOf(MARK_END);
        if (b >= 0) {
            t = t.substring(0, b) + t.substring(b + 1);
        }
        if (a < 0) {
            a = t.length();
        }
        if (b < 0) {
            b = a;
        }
        String replacement = prefix + t + suffix;
        int base = start + prefix.length();
        return new TextEdit(start, end, replacement, base + a, base + b);
    }

    /** Wraps the selection in a fenced code block (or inserts an empty one). */
    public static TextEdit codeBlock(String text, int start, int end, String language) {
        String lang = language == null ? "" : language;
        if (start == end) {
            return insert(text, start, end, "```" + lang + "\n" + MARK_START + "\n```", true);
        }
        int[] block = lineBlock(text, start, end);
        String code = text.substring(block[0], block[1]);
        String fence = code.contains("```") ? "~~~" : "```";
        return insert(text, block[0], block[1], fence + lang + "\n" + code + "\n" + fence + MARK_START, true);
    }

    /** Inserts a footnote reference after the selection and its definition at the end of the document. */
    public static TextEdit footnote(String text, int start, int end) {
        int max = 0;
        Matcher m = FOOTNOTE_REF.matcher(text);
        while (m.find()) {
            max = Math.max(max, Integer.parseInt(m.group(1)));
        }
        String ref = "[^" + (max + 1) + "]";
        String tail = text.substring(end);
        String body = text.substring(0, end) + ref + tail;
        String trimmed = body.stripTrailing();
        String lastLine = trimmed.substring(lineStart(trimmed, trimmed.length()));
        String separator = trimmed.isEmpty() ? "" : FOOTNOTE_DEF.matcher(lastLine).find() ? "\n" : "\n\n";
        String definition = ref + ": ";
        String replacement = ref + tail.substring(0, tail.length() - (body.length() - trimmed.length()))
                + separator + definition;
        int caret = end + replacement.length();
        return new TextEdit(end, text.length(), replacement, caret, caret);
    }

    /** Inserts YAML front matter at the top of the document, unless there is one already. */
    public static TextEdit frontMatter(String text, String date) {
        if (text.startsWith("---\n") || text.startsWith("---\r\n")) {
            return new TextEdit(0, 0, "", 4, 4);
        }
        String template = "---\ntitle: " + MARK_START + "Title" + MARK_END + "\nauthor: \ndate: " + date
                + "\n---\n\n";
        return insert(text, 0, 0, template, false);
    }

    /** Markdown for a link; URLs with spaces are wrapped in angle brackets. */
    public static String link(String label, String url, String title) {
        return "[" + label + "](" + destination(url) + titlePart(title) + ")";
    }

    /** Markdown for an image, with an optional {@code {width=...}} attribute. */
    public static String image(String alt, String url, String title, String width) {
        String attrs = width == null || width.isBlank() ? "" : "{width=" + width.trim() + "}";
        return "![" + alt + "](" + destination(url) + titlePart(title) + ")" + attrs;
    }

    private static String destination(String url) {
        String u = url == null ? "" : url.trim();
        return u.contains(" ") || u.contains("(") || u.contains(")") ? "<" + u + ">" : u;
    }

    private static String titlePart(String title) {
        return title == null || title.isBlank() ? "" : " \"" + title.replace("\"", "\\\"") + "\"";
    }
}
