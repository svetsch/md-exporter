package io.mdexporter.core;

import org.commonmark.node.Code;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Node;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Small helpers around the CommonMark AST. */
public final class Nodes {

    private Nodes() {
    }

    /** Plain text content of a node (used for headings, alt texts, ...). */
    public static String textContent(Node node) {
        StringBuilder sb = new StringBuilder();
        collectText(node, sb);
        return sb.toString();
    }

    private static void collectText(Node node, StringBuilder sb) {
        if (node instanceof Text text) {
            sb.append(text.getLiteral());
        } else if (node instanceof Code code) {
            sb.append(code.getLiteral());
        } else if (node instanceof SoftLineBreak || node instanceof HardLineBreak) {
            sb.append(' ');
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            collectText(child, sb);
        }
    }

    public static List<Node> children(Node node) {
        List<Node> list = new ArrayList<>();
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            list.add(child);
        }
        return list;
    }

    /** First word of a fenced code block info string, lower-cased ("" when absent). */
    public static String language(String info) {
        if (info == null) {
            return "";
        }
        String trimmed = info.trim();
        int end = 0;
        while (end < trimmed.length() && !Character.isWhitespace(trimmed.charAt(end))
                && trimmed.charAt(end) != '{') {
            end++;
        }
        return trimmed.substring(0, end).toLowerCase(Locale.ROOT);
    }
}
