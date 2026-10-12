package io.mdexporter.editor;

/**
 * A single replacement in a text plus the selection to apply afterwards.
 *
 * @param start       first replaced character
 * @param end         end (exclusive) of the replaced range in the original text
 * @param replacement new text for {@code [start, end)}
 * @param anchor      selection anchor in the resulting text
 * @param caret       caret position in the resulting text
 */
public record TextEdit(int start, int end, String replacement, int anchor, int caret) {

    /** Applies the edit to {@code text} (used by tests and for whole-document operations). */
    public String apply(String text) {
        return text.substring(0, start) + replacement + text.substring(end);
    }

    /** The smallest edit turning {@code before} into {@code after}, with the caret at the end of the change. */
    public static TextEdit between(String before, String after) {
        int prefix = 0;
        int max = Math.min(before.length(), after.length());
        while (prefix < max && before.charAt(prefix) == after.charAt(prefix)) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < max - prefix
                && before.charAt(before.length() - 1 - suffix) == after.charAt(after.length() - 1 - suffix)) {
            suffix++;
        }
        // do not split surrogate pairs
        if (prefix > 0 && Character.isHighSurrogate(before.charAt(prefix - 1))) {
            prefix--;
        }
        if (suffix > 0 && Character.isLowSurrogate(before.charAt(before.length() - suffix))) {
            suffix--;
        }
        String replacement = after.substring(prefix, after.length() - suffix);
        int caret = prefix + replacement.length();
        return new TextEdit(prefix, before.length() - suffix, replacement, caret, caret);
    }

    public boolean isEmpty() {
        return start == end && replacement.isEmpty();
    }
}
