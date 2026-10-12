package io.mdexporter.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A GitHub flavoured Markdown pipe table: parsing, column alignment ("pretty printing") and row/column operations.
 * Row 0 is the header row; the delimiter row is not part of {@link #rows()} but rendered from {@link #aligns()}.
 */
public final class MarkdownTable {

    public enum Align { NONE, LEFT, CENTER, RIGHT }

    private static final Pattern DELIMITER_CELL = Pattern.compile("\\s*:?-+:?\\s*");
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})");

    private final String indent;
    private final List<List<String>> rows;
    private final List<Align> aligns;

    private MarkdownTable(String indent, List<List<String>> rows, List<Align> aligns) {
        this.indent = indent;
        this.rows = rows;
        this.aligns = aligns;
        normalize();
    }

    /** New table with empty cells. */
    public static MarkdownTable create(int columns, int bodyRows, List<String> header, Align align) {
        List<List<String>> rows = new ArrayList<>();
        List<String> head = new ArrayList<>();
        for (int c = 0; c < columns; c++) {
            head.add(header != null && c < header.size() ? header.get(c) : "Column " + (c + 1));
        }
        rows.add(head);
        for (int r = 0; r < bodyRows; r++) {
            List<String> row = new ArrayList<>();
            for (int c = 0; c < columns; c++) {
                row.add("");
            }
            rows.add(row);
        }
        List<Align> aligns = new ArrayList<>();
        for (int c = 0; c < columns; c++) {
            aligns.add(align == null ? Align.NONE : align);
        }
        return new MarkdownTable("", rows, aligns);
    }

    public List<List<String>> rows() {
        return rows;
    }

    public List<Align> aligns() {
        return aligns;
    }

    public int columnCount() {
        return aligns.size();
    }

    // ------------------------------------------------------------------ parsing

    /** Whether {@code line} is a delimiter row such as {@code |:---|---:|}. */
    public static boolean isDelimiterRow(String line) {
        List<String> cells = splitCells(line);
        if (cells.isEmpty() || cells.size() == 1 && line.indexOf('|') < 0) {
            return false;
        }
        for (String cell : cells) {
            if (!DELIMITER_CELL.matcher(cell).matches()) {
                return false;
            }
        }
        return true;
    }

    /** Parses table lines: header, delimiter row, body rows. */
    public static MarkdownTable parse(List<String> lines) {
        if (lines.size() < 2 || !isDelimiterRow(lines.get(1))) {
            throw new IllegalArgumentException("Not a Markdown table");
        }
        String first = lines.get(0);
        String indent = first.substring(0, first.length() - first.stripLeading().length());
        List<List<String>> rows = new ArrayList<>();
        rows.add(trimmed(splitCells(first)));
        List<Align> aligns = new ArrayList<>();
        for (String cell : splitCells(lines.get(1))) {
            String c = cell.trim();
            boolean left = c.startsWith(":");
            boolean right = c.endsWith(":");
            aligns.add(left && right ? Align.CENTER : left ? Align.LEFT : right ? Align.RIGHT : Align.NONE);
        }
        for (int i = 2; i < lines.size(); i++) {
            rows.add(trimmed(splitCells(lines.get(i))));
        }
        return new MarkdownTable(indent, rows, aligns);
    }

    private static List<String> trimmed(List<String> cells) {
        List<String> out = new ArrayList<>(cells.size());
        for (String c : cells) {
            out.add(c.trim());
        }
        return out;
    }

    /** Splits a row on unescaped pipes; the optional leading and trailing pipes are dropped. */
    static List<String> splitCells(String line) {
        String s = line.strip();
        if (s.startsWith("|")) {
            s = s.substring(1);
        }
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean endsWithPipe = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            endsWithPipe = false;
            if (c == '\\' && i + 1 < s.length()) {
                cell.append(c).append(s.charAt(++i));
            } else if (c == '|') {
                cells.add(cell.toString());
                cell.setLength(0);
                endsWithPipe = true;
            } else {
                cell.append(c);
            }
        }
        if (!endsWithPipe || cells.isEmpty()) {
            cells.add(cell.toString());
        }
        return cells;
    }

    /** Index of the cell containing column {@code offset} of {@code line}. */
    static int cellIndexAt(String line, int offset) {
        String s = line;
        int lead = s.length() - s.stripLeading().length();
        int index = 0;
        boolean leadingPipe = s.startsWith("|", lead);
        for (int i = lead + (leadingPipe ? 1 : 0); i < Math.min(offset, s.length()); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '|') {
                index++;
            }
        }
        return index;
    }

    // ------------------------------------------------------------------ editing

    private void normalize() {
        int columns = aligns.size();
        for (List<String> row : rows) {
            columns = Math.max(columns, row.size());
        }
        while (aligns.size() < columns) {
            aligns.add(Align.NONE);
        }
        for (List<String> row : rows) {
            while (row.size() < columns) {
                row.add("");
            }
        }
    }

    public void insertRow(int index) {
        List<String> row = new ArrayList<>();
        for (int c = 0; c < columnCount(); c++) {
            row.add("");
        }
        rows.add(Math.max(1, Math.min(index, rows.size())), row);
    }

    public void deleteRow(int index) {
        if (index > 0 && index < rows.size()) {
            rows.remove(index);
        }
    }

    public void insertColumn(int index) {
        int at = Math.max(0, Math.min(index, columnCount()));
        aligns.add(at, Align.NONE);
        for (List<String> row : rows) {
            row.add(at, "");
        }
    }

    public void deleteColumn(int index) {
        if (columnCount() > 1 && index >= 0 && index < columnCount()) {
            aligns.remove(index);
            for (List<String> row : rows) {
                row.remove(index);
            }
        }
    }

    public void moveColumn(int index, int delta) {
        int target = index + delta;
        if (index < 0 || index >= columnCount() || target < 0 || target >= columnCount()) {
            return;
        }
        aligns.add(target, aligns.remove(index));
        for (List<String> row : rows) {
            row.add(target, row.remove(index));
        }
    }

    public void moveRow(int index, int delta) {
        int target = index + delta;
        if (index < 1 || index >= rows.size() || target < 1 || target >= rows.size()) {
            return;
        }
        rows.add(target, rows.remove(index));
    }

    public void align(int column, Align align) {
        if (column >= 0 && column < columnCount()) {
            aligns.set(column, align);
        }
    }

    // ------------------------------------------------------------------ formatting

    /**
     * Formatted table text.
     *
     * @param text       the table, lines separated by {@code \n}, without trailing newline
     * @param cellStarts per line (header, delimiter, body rows) and column: offset of the cell content in
     *                   {@code text}
     */
    public record Formatted(String text, int[][] cellStarts) {

        /** Offset of the content of {@code row} (0 = header) / {@code column}. */
        public int cellStart(int row, int column) {
            int line = row == 0 ? 0 : row + 1;
            int[] starts = cellStarts[Math.min(line, cellStarts.length - 1)];
            return starts[Math.max(0, Math.min(column, starts.length - 1))];
        }
    }

    /** Pretty prints the table: every column padded to the same width, the delimiter row matching it. */
    public Formatted format() {
        int columns = columnCount();
        int[] widths = new int[columns];
        for (int c = 0; c < columns; c++) {
            widths[c] = aligns.get(c) == Align.CENTER ? 5 : 3;
            for (List<String> row : rows) {
                widths[c] = Math.max(widths[c], displayWidth(row.get(c)));
            }
        }
        StringBuilder out = new StringBuilder();
        int[][] starts = new int[rows.size() + 1][columns];
        for (int line = 0; line <= rows.size(); line++) {
            if (line > 0) {
                out.append('\n');
            }
            out.append(indent).append('|');
            for (int c = 0; c < columns; c++) {
                out.append(' ');
                if (line == 1) {
                    starts[line][c] = out.length();
                    out.append(delimiter(aligns.get(c), widths[c]));
                } else {
                    String cell = rows.get(line == 0 ? 0 : line - 1).get(c);
                    int pad = widths[c] - displayWidth(cell);
                    int left = switch (aligns.get(c)) {
                        case RIGHT -> pad;
                        case CENTER -> pad / 2;
                        default -> 0;
                    };
                    out.append(" ".repeat(left));
                    starts[line][c] = out.length();
                    out.append(cell).append(" ".repeat(pad - left));
                }
                out.append(" |");
            }
        }
        return new Formatted(out.toString(), starts);
    }

    private static String delimiter(Align align, int width) {
        return switch (align) {
            case LEFT -> ":" + "-".repeat(width - 1);
            case RIGHT -> "-".repeat(width - 1) + ":";
            case CENTER -> ":" + "-".repeat(width - 2) + ":";
            default -> "-".repeat(width);
        };
    }

    /** Width in a monospaced font: East Asian wide characters and emoji count twice, combining marks not at all. */
    public static int displayWidth(String s) {
        int width = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);
            if (type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK || type == Character.FORMAT
                    || cp >= 0xFE00 && cp <= 0xFE0F) {
                continue;
            }
            width += isWide(cp) ? 2 : 1;
        }
        return width;
    }

    private static boolean isWide(int cp) {
        return cp >= 0x1100 && cp <= 0x115F
                || cp >= 0x2E80 && cp <= 0xA4CF && cp != 0x303F
                || cp >= 0xAC00 && cp <= 0xD7A3
                || cp >= 0xF900 && cp <= 0xFAFF
                || cp >= 0xFE30 && cp <= 0xFE4F
                || cp >= 0xFF00 && cp <= 0xFF60
                || cp >= 0xFFE0 && cp <= 0xFFE6
                || cp >= 0x1F300 && cp <= 0x1F64F
                || cp >= 0x1F900 && cp <= 0x1F9FF
                || cp >= 0x20000 && cp <= 0x3FFFD;
    }

    // ------------------------------------------------------------------ location in a document

    /**
     * A table found in a document.
     *
     * @param start     offset of the first character of the header line
     * @param end       offset after the last character of the last row (before its line break)
     * @param table     the parsed table
     * @param caretRow  row containing the caret (0 = header, the delimiter row counts as header), or -1
     * @param caretCol  column containing the caret, or -1
     * @param caretSkip characters between the start of the cell content and the caret
     */
    public record Located(int start, int end, MarkdownTable table, int caretRow, int caretCol, int caretSkip) {
    }

    /** The table containing {@code pos}, or {@code null}. Tables in fenced code blocks are ignored. */
    public static Located find(String text, int pos) {
        if (MarkdownEditing.isInFence(text, pos)) {
            return null;
        }
        return locate(text, pos);
    }

    private static Located locate(String text, int pos) {
        int ls = MarkdownEditing.lineStart(text, pos);
        if (!isTableLine(text, ls)) {
            return null;
        }
        int first = ls;
        while (first > 0) {
            int prev = MarkdownEditing.lineStart(text, first - 1);
            if (!isTableLine(text, prev)) {
                break;
            }
            first = prev;
        }
        List<Integer> starts = new ArrayList<>();
        int line = first;
        while (isTableLine(text, line)) {
            starts.add(line);
            int le = MarkdownEditing.lineEnd(text, line);
            if (le >= text.length()) {
                break;
            }
            line = le + 1;
        }
        // the header is the line before the first delimiter row
        int header = -1;
        for (int i = 1; i < starts.size(); i++) {
            if (isDelimiterRow(lineAt(text, starts.get(i)))) {
                header = i - 1;
                break;
            }
        }
        if (header < 0 || starts.get(header) > ls) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        for (int i = header; i < starts.size(); i++) {
            lines.add(lineAt(text, starts.get(i)));
        }
        int start = starts.get(header);
        int end = MarkdownEditing.lineEnd(text, starts.get(starts.size() - 1));
        MarkdownTable table = parse(lines);
        int lineInTable = starts.indexOf(ls) - header;
        String caretLine = lines.get(lineInTable);
        int column = Math.min(cellIndexAt(caretLine, pos - ls), table.columnCount() - 1);
        int row = lineInTable <= 1 ? 0 : lineInTable - 1;
        int skip = lineInTable == 1 ? 0 : caretSkip(caretLine, pos - ls);
        return new Located(start, end, table, row, column, skip);
    }

    /** Characters between the start of the (trimmed) cell content and the caret. */
    private static int caretSkip(String line, int offset) {
        int cellStart = offset;
        while (cellStart > 0 && !(line.charAt(cellStart - 1) == '|'
                && (cellStart < 2 || line.charAt(cellStart - 2) != '\\'))) {
            cellStart--;
        }
        int content = cellStart;
        while (content < offset && content < line.length() && line.charAt(content) == ' ') {
            content++;
        }
        return Math.max(0, offset - content);
    }

    private static String lineAt(String text, int lineStart) {
        return text.substring(lineStart, MarkdownEditing.lineEnd(text, lineStart));
    }

    private static boolean isTableLine(String text, int lineStart) {
        String line = lineAt(text, lineStart);
        if (line.isBlank()) {
            return false;
        }
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '|') {
                return true;
            }
        }
        return false;
    }

    /** All tables outside fenced code blocks, from the top. */
    public static List<Located> findAll(String text) {
        List<Located> tables = new ArrayList<>();
        String fence = null;
        int pos = 0;
        while (pos < text.length()) {
            int le = MarkdownEditing.lineEnd(text, pos);
            String line = text.substring(pos, le);
            java.util.regex.Matcher m = FENCE.matcher(line);
            if (m.find()) {
                String marker = m.group(1);
                if (fence == null) {
                    fence = marker;
                } else if (marker.charAt(0) == fence.charAt(0) && marker.length() >= fence.length()
                        && line.trim().length() == marker.length()) {
                    fence = null;
                }
            } else if (fence == null) {
                Located t = locate(text, pos);
                if (t != null && t.start() == pos) {
                    tables.add(t);
                    le = t.end();
                }
            }
            if (le >= text.length()) {
                break;
            }
            pos = le + 1;
        }
        return tables;
    }

    /** Formats every table of the document. */
    public static String formatAll(String text) {
        List<Located> tables = findAll(text);
        StringBuilder out = new StringBuilder(text);
        for (int i = tables.size() - 1; i >= 0; i--) {
            Located t = tables.get(i);
            out.replace(t.start(), t.end(), t.table().format().text());
        }
        return out.toString();
    }
}
