package io.mdexporter.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Word level difference between two texts (Myers' algorithm), used to review AI suggestions. Lines are compared
 * first, then the words of the changed lines, which keeps large documents fast.
 */
public final class TextDiff {

    public enum Type { EQUAL, DELETE, INSERT }

    public record Op(Type type, String text) {
    }

    private static final Pattern TOKEN = Pattern.compile("\\w+|\\s+|[^\\w\\s]", Pattern.UNICODE_CHARACTER_CLASS);
    /** Above this edit distance the changed region is reported as one replacement (bounds memory to ~8 MB). */
    private static final int MAX_DISTANCE = 1000;

    private TextDiff() {
    }

    public static List<Op> diff(String before, String after) {
        List<Op> out = new ArrayList<>();
        List<String> a = lines(before);
        List<String> b = lines(after);
        List<Op> lineOps = diff(a, b);
        // refine replaced line blocks word by word
        for (int i = 0; i < lineOps.size(); i++) {
            Op op = lineOps.get(i);
            if (op.type() == Type.DELETE && i + 1 < lineOps.size() && lineOps.get(i + 1).type() == Type.INSERT) {
                for (Op w : diff(tokens(op.text()), tokens(lineOps.get(i + 1).text()))) {
                    add(out, w.type(), w.text());
                }
                i++;
            } else {
                add(out, op.type(), op.text());
            }
        }
        return out;
    }

    /** Number of characters inserted plus deleted. */
    public static int changedCharacters(List<Op> ops) {
        return ops.stream().filter(o -> o.type() != Type.EQUAL).mapToInt(o -> o.text().length()).sum();
    }

    static List<String> lines(String s) {
        List<String> lines = new ArrayList<>();
        int from = 0;
        while (from < s.length()) {
            int nl = s.indexOf('\n', from);
            int end = nl < 0 ? s.length() : nl + 1;
            lines.add(s.substring(from, end));
            from = end;
        }
        return lines;
    }

    static List<String> tokens(String s) {
        List<String> tokens = new ArrayList<>();
        Matcher m = TOKEN.matcher(s);
        while (m.find()) {
            tokens.add(m.group());
        }
        return tokens;
    }

    /** Myers' O(ND) difference of two token lists, merged into runs. */
    static List<Op> diff(List<String> a, List<String> b) {
        int prefix = 0;
        while (prefix < a.size() && prefix < b.size() && a.get(prefix).equals(b.get(prefix))) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < a.size() - prefix && suffix < b.size() - prefix
                && a.get(a.size() - 1 - suffix).equals(b.get(b.size() - 1 - suffix))) {
            suffix++;
        }
        List<Op> out = new ArrayList<>();
        add(out, Type.EQUAL, String.join("", a.subList(0, prefix)));
        List<String> x = a.subList(prefix, a.size() - suffix);
        List<String> y = b.subList(prefix, b.size() - suffix);
        middle(out, x, y);
        add(out, Type.EQUAL, String.join("", a.subList(a.size() - suffix, a.size())));
        return out;
    }

    private static void middle(List<Op> out, List<String> x, List<String> y) {
        int n = x.size();
        int m = y.size();
        if (n == 0 || m == 0) {
            add(out, Type.DELETE, String.join("", x));
            add(out, Type.INSERT, String.join("", y));
            return;
        }
        int max = Math.min(n + m, MAX_DISTANCE);
        int offset = max + 1;
        int[] v = new int[2 * max + 3];
        List<int[]> trace = new ArrayList<>();
        int found = -1;
        for (int d = 0; d <= max && found < 0; d++) {
            trace.add(v.clone());
            for (int k = -d; k <= d; k += 2) {
                int xi = k == -d || k != d && v[offset + k - 1] < v[offset + k + 1]
                        ? v[offset + k + 1] : v[offset + k - 1] + 1;
                int yi = xi - k;
                while (xi < n && yi < m && x.get(xi).equals(y.get(yi))) {
                    xi++;
                    yi++;
                }
                v[offset + k] = xi;
                if (xi >= n && yi >= m) {
                    found = d;
                    break;
                }
            }
        }
        if (found < 0) {
            add(out, Type.DELETE, String.join("", x));
            add(out, Type.INSERT, String.join("", y));
            return;
        }
        // backtrack
        List<Op> reversed = new ArrayList<>();
        int xi = n;
        int yi = m;
        for (int d = found; d > 0; d--) {
            int[] pv = trace.get(d);
            int k = xi - yi;
            int prevK = k == -d || k != d && pv[offset + k - 1] < pv[offset + k + 1] ? k + 1 : k - 1;
            int prevX = pv[offset + prevK];
            int prevY = prevX - prevK;
            while (xi > prevX && yi > prevY) {
                reversed.add(new Op(Type.EQUAL, x.get(--xi)));
                yi--;
            }
            if (xi == prevX) {
                reversed.add(new Op(Type.INSERT, y.get(--yi)));
            } else {
                reversed.add(new Op(Type.DELETE, x.get(--xi)));
            }
        }
        while (xi > 0 && yi > 0) {
            reversed.add(new Op(Type.EQUAL, x.get(--xi)));
            yi--;
        }
        for (int i = reversed.size() - 1; i >= 0; i--) {
            add(out, reversed.get(i).type(), reversed.get(i).text());
        }
    }

    private static void add(List<Op> out, Type type, String text) {
        if (text.isEmpty()) {
            return;
        }
        if (!out.isEmpty() && out.get(out.size() - 1).type() == type) {
            Op last = out.remove(out.size() - 1);
            out.add(new Op(type, last.text() + text));
        } else {
            out.add(new Op(type, text));
        }
    }
}
