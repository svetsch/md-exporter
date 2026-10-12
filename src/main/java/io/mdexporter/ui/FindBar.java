package io.mdexporter.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Find / replace bar shown above the editor (Ctrl+F, Ctrl+H). */
final class FindBar extends VBox {

    private final TextArea editor;
    private final TextField findField = new TextField();
    private final TextField replaceField = new TextField();
    private final ToggleButton matchCase = toggle("Aa", "Match case");
    private final ToggleButton wholeWord = toggle("W", "Whole words");
    private final ToggleButton regex = toggle(".*", "Regular expression ($1, $2... in the replacement)");
    private final Label count = new Label();
    private final HBox replaceRow;

    FindBar(TextArea editor) {
        this.editor = editor;
        getStyleClass().add("find-bar");
        setSpacing(4);
        setPadding(new Insets(4, 8, 4, 8));

        findField.setPromptText("Find");
        replaceField.setPromptText("Replace with");
        HBox.setHgrow(findField, Priority.ALWAYS);
        HBox.setHgrow(replaceField, Priority.ALWAYS);
        count.setMinWidth(80);
        count.getStyleClass().add("find-count");

        Button previous = button("↑", "Previous match (Shift+F3, Shift+Enter)", () -> find(false));
        Button next = button("↓", "Next match (F3, Enter)", () -> find(true));
        Button close = button("✕", "Close (Esc)", this::hide);
        Region spacer = new Region();
        spacer.setMinWidth(close.prefWidth(-1));
        HBox findRow = new HBox(4, findField, matchCase, wholeWord, regex, previous, next, count, close);
        findRow.setAlignment(Pos.CENTER_LEFT);
        Button replace = button("Replace", "Replace the current match and find the next one", this::replace);
        Button replaceAll = button("Replace All", "Replace every match (one undo step)", this::replaceAll);
        replaceRow = new HBox(4, replaceField, replace, replaceAll, spacer);
        replaceRow.setAlignment(Pos.CENTER_LEFT);
        getChildren().addAll(findRow, replaceRow);

        findField.textProperty().addListener((o, a, v) -> {
            updateCount();
            if (!v.isEmpty()) {
                findFrom(Math.min(editor.getSelection().getStart(), editor.getLength()), true);
            }
        });
        for (ToggleButton b : List.of(matchCase, wholeWord, regex)) {
            b.selectedProperty().addListener((o, a, v) -> updateCount());
        }
        addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                hide();
                e.consume();
            } else if (e.getCode() == KeyCode.ENTER && e.getTarget() == findField) {
                find(!e.isShiftDown());
                e.consume();
            } else if (e.getCode() == KeyCode.ENTER && e.getTarget() == replaceField) {
                replace();
                e.consume();
            }
        });
        setVisible(false);
        setManaged(false);
    }

    private static ToggleButton toggle(String text, String tooltip) {
        ToggleButton b = new ToggleButton(text);
        b.setTooltip(new Tooltip(tooltip));
        b.setFocusTraversable(false);
        b.getStyleClass().add("find-toggle");
        return b;
    }

    private static Button button(String text, String tooltip, Runnable action) {
        Button b = new Button(text);
        b.setTooltip(new Tooltip(tooltip));
        b.setOnAction(e -> action.run());
        b.setFocusTraversable(false);
        return b;
    }

    /** Shows the bar; the selected text (single line) becomes the search term. */
    void show(boolean withReplace) {
        String selected = editor.getSelectedText();
        if (!selected.isEmpty() && selected.indexOf('\n') < 0) {
            findField.setText(regex.isSelected() ? Pattern.quote(selected) : selected);
        }
        replaceRow.setVisible(withReplace);
        replaceRow.setManaged(withReplace);
        setVisible(true);
        setManaged(true);
        updateCount();
        findField.requestFocus();
        findField.selectAll();
    }

    void hide() {
        setVisible(false);
        setManaged(false);
        editor.requestFocus();
    }

    boolean hasTerm() {
        return !findField.getText().isEmpty();
    }

    private Pattern pattern() {
        String term = findField.getText();
        if (term.isEmpty()) {
            return null;
        }
        String p = regex.isSelected() ? term : Pattern.quote(term);
        if (wholeWord.isSelected()) {
            p = "(?<![\\w])(?:" + p + ")(?![\\w])";
        }
        int flags = Pattern.MULTILINE | Pattern.UNICODE_CHARACTER_CLASS;
        if (!matchCase.isSelected()) {
            flags |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        }
        try {
            findField.getStyleClass().remove("error");
            return Pattern.compile(p, flags);
        } catch (PatternSyntaxException e) {
            if (!findField.getStyleClass().contains("error")) {
                findField.getStyleClass().add("error");
            }
            return null;
        }
    }

    /** Finds the next (or previous) match from the current selection; wraps around. */
    void find(boolean forward) {
        if (!hasTerm()) {
            show(replaceRow.isVisible());
            return;
        }
        int from = forward ? editor.getSelection().getEnd() : editor.getSelection().getStart();
        findFrom(from, forward);
    }

    private void findFrom(int from, boolean forward) {
        Pattern p = pattern();
        if (p == null) {
            return;
        }
        String text = editor.getText();
        List<int[]> matches = matches(p, text);
        if (matches.isEmpty()) {
            count.setText("No results");
            return;
        }
        int index = -1;
        if (forward) {
            for (int i = 0; i < matches.size(); i++) {
                if (matches.get(i)[0] >= from) {
                    index = i;
                    break;
                }
            }
            if (index < 0) {
                index = 0;
            }
        } else {
            for (int i = matches.size() - 1; i >= 0; i--) {
                if (matches.get(i)[0] < from) {
                    index = i;
                    break;
                }
            }
            if (index < 0) {
                index = matches.size() - 1;
            }
        }
        int[] m = matches.get(index);
        editor.selectRange(m[0], m[1]);
        count.setText((index + 1) + " of " + matches.size());
    }

    private static List<int[]> matches(Pattern p, String text) {
        List<int[]> list = new ArrayList<>();
        Matcher m = p.matcher(text);
        while (m.find()) {
            if (m.end() == m.start()) {
                if (m.end() >= text.length()) {
                    break;
                }
                continue; // ignore empty matches
            }
            list.add(new int[]{m.start(), m.end()});
        }
        return list;
    }

    private void updateCount() {
        Pattern p = pattern();
        count.setText(p == null ? "" : matches(p, editor.getText()).size() + " found");
    }

    private void replace() {
        Pattern p = pattern();
        if (p == null) {
            return;
        }
        String selected = editor.getSelectedText();
        Matcher m = p.matcher(selected);
        if (!selected.isEmpty() && m.matches()) {
            String replacement;
            try {
                replacement = regex.isSelected() ? m.replaceFirst(replaceField.getText()) : replaceField.getText();
            } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
                count.setText("Invalid replacement");
                return;
            }
            int start = editor.getSelection().getStart();
            editor.replaceSelection(replacement);
            editor.selectRange(start + replacement.length(), start + replacement.length());
        }
        find(true);
        updateCount();
    }

    private void replaceAll() {
        Pattern p = pattern();
        if (p == null) {
            return;
        }
        String text = editor.getText();
        Matcher m = p.matcher(text);
        int n = 0;
        StringBuilder out = new StringBuilder();
        try {
            while (m.find()) {
                n++;
                m.appendReplacement(out, regex.isSelected() ? replaceField.getText()
                        : Matcher.quoteReplacement(replaceField.getText()));
            }
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            count.setText("Invalid replacement");
            return;
        }
        m.appendTail(out);
        if (n > 0) {
            io.mdexporter.editor.TextEdit edit = io.mdexporter.editor.TextEdit.between(text, out.toString());
            editor.replaceText(edit.start(), edit.end(), edit.replacement());
            editor.selectRange(edit.caret(), edit.caret());
        }
        count.setText(n + " replaced");
    }
}
