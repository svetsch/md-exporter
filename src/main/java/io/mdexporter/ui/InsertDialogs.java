package io.mdexporter.ui;

import io.mdexporter.editor.MarkdownEditing;
import io.mdexporter.editor.MarkdownTable;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Path;
import java.util.Optional;

/** Small dialogs used by the Insert menu. */
final class InsertDialogs {

    private InsertDialogs() {
    }

    private static GridPane grid() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(10));
        ColumnConstraints labels = new ColumnConstraints();
        labels.setMinWidth(90);
        ColumnConstraints fields = new ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        fields.setMinWidth(320);
        grid.getColumnConstraints().addAll(labels, fields);
        return grid;
    }

    private static <T> Dialog<T> dialog(Window owner, String title, String header) {
        Dialog<T> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(title);
        dialog.setHeaderText(header);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        return dialog;
    }

    /** Asks for the size of a new table; returns its Markdown. */
    static Optional<String> table(Window owner) {
        Dialog<String> dialog = dialog(owner, "Insert Table", "Insert a table");
        Spinner<Integer> columns = new Spinner<>(1, 20, 3);
        Spinner<Integer> rows = new Spinner<>(0, 200, 2);
        columns.setEditable(true);
        rows.setEditable(true);
        ComboBox<MarkdownTable.Align> align = new ComboBox<>();
        align.getItems().setAll(MarkdownTable.Align.values());
        align.setValue(MarkdownTable.Align.NONE);
        GridPane grid = grid();
        grid.addRow(0, new Label("Columns:"), columns);
        grid.addRow(1, new Label("Body rows:"), rows);
        grid.addRow(2, new Label("Alignment:"), align);
        dialog.getDialogPane().setContent(grid);
        dialog.setResultConverter(b -> {
            if (b != ButtonType.OK) {
                return null;
            }
            commit(columns);
            commit(rows);
            return MarkdownTable.create(columns.getValue(), rows.getValue(), null, align.getValue()).format().text();
        });
        Platform.runLater(columns::requestFocus);
        return dialog.showAndWait();
    }

    /** Asks for a link; the selected text becomes the label (or the URL if it looks like one). */
    static Optional<String> link(Window owner, String selected) {
        Dialog<String> dialog = dialog(owner, "Insert Link", "Insert a link");
        boolean isUrl = selected.matches("(?i)(https?|mailto|ftp):\\S+|www\\.\\S+|#\\S+");
        TextField text = new TextField(isUrl ? "" : selected);
        TextField url = new TextField(isUrl ? selected : "https://");
        TextField title = new TextField();
        url.setPromptText("https://example.org, #heading-anchor, file.md");
        title.setPromptText("optional tooltip");
        GridPane grid = grid();
        grid.addRow(0, new Label("Text:"), text);
        grid.addRow(1, new Label("URL:"), url);
        grid.addRow(2, new Label("Title:"), title);
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().lookupButton(ButtonType.OK).disableProperty()
                .bind(url.textProperty().isEmpty());
        dialog.setResultConverter(b -> {
            if (b != ButtonType.OK) {
                return null;
            }
            String label = text.getText().isBlank() ? url.getText().trim() : text.getText();
            return MarkdownEditing.link(label, url.getText(), title.getText());
        });
        Platform.runLater(() -> {
            TextField focus = text.getText().isEmpty() && !isUrl ? text : url;
            focus.requestFocus();
            if (focus == url && !isUrl) {
                url.positionCaret(url.getLength());
            }
        });
        return dialog.showAndWait();
    }

    /** Asks for an image (file or URL); local files are referenced relative to the document folder. */
    static Optional<String> image(Window owner, Path baseDir, String selected) {
        Dialog<String> dialog = dialog(owner, "Insert Image", "Insert an image");
        TextField url = new TextField();
        TextField alt = new TextField(selected);
        TextField title = new TextField();
        TextField width = new TextField();
        url.setPromptText("images/picture.png or https://...");
        alt.setPromptText("description for screen readers and exports");
        width.setPromptText("e.g. 300 or 50%");
        CheckBox relative = new CheckBox("Relative to the document folder");
        relative.setSelected(true);
        Button browse = new Button("Browse...");
        browse.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Choose an image");
            chooser.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.svg", "*.bmp",
                            "*.webp"),
                    new FileChooser.ExtensionFilter("All files", "*.*"));
            if (baseDir != null && baseDir.toFile().isDirectory()) {
                chooser.setInitialDirectory(baseDir.toFile());
            }
            File file = chooser.showOpenDialog(dialog.getDialogPane().getScene().getWindow());
            if (file != null) {
                url.setText(imagePath(file.toPath(), baseDir, relative.isSelected()));
                if (alt.getText().isBlank()) {
                    String name = file.getName();
                    int dot = name.lastIndexOf('.');
                    alt.setText((dot > 0 ? name.substring(0, dot) : name).replace('-', ' ').replace('_', ' '));
                }
            }
        });
        HBox urlRow = new HBox(6, url, browse);
        HBox.setHgrow(url, Priority.ALWAYS);
        GridPane grid = grid();
        grid.addRow(0, new Label("Image:"), urlRow);
        grid.add(relative, 1, 1);
        grid.addRow(2, new Label("Alt text:"), alt);
        grid.addRow(3, new Label("Title:"), title);
        grid.addRow(4, new Label("Width:"), width);
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().lookupButton(ButtonType.OK).disableProperty()
                .bind(url.textProperty().isEmpty());
        dialog.setResultConverter(b -> b == ButtonType.OK
                ? MarkdownEditing.image(alt.getText(), url.getText(), title.getText(), width.getText()) : null);
        Platform.runLater(url::requestFocus);
        return dialog.showAndWait();
    }

    /** Takes over a value typed into an editable spinner. */
    static void commit(Spinner<Integer> spinner) {
        try {
            Integer value = spinner.getValueFactory().getConverter().fromString(spinner.getEditor().getText().trim());
            if (value != null) {
                spinner.getValueFactory().setValue(value);
            }
        } catch (RuntimeException ignored) {
            spinner.getEditor().setText(String.valueOf(spinner.getValue()));
        }
    }

    static String imagePath(Path file, Path baseDir, boolean relative) {
        Path abs = file.toAbsolutePath().normalize();
        if (relative && baseDir != null) {
            try {
                Path rel = baseDir.toAbsolutePath().normalize().relativize(abs);
                return rel.toString().replace('\\', '/');
            } catch (IllegalArgumentException ignored) {
                // other drive
            }
        }
        return abs.toUri().toString();
    }

    /** Asks for a line number; returns it 1 based. */
    static Optional<Integer> goToLine(Window owner, int current, int max) {
        Dialog<Integer> dialog = dialog(owner, "Go to Line", "Go to line (1 - " + max + ")");
        Spinner<Integer> line = new Spinner<>(1, Math.max(1, max), Math.max(1, Math.min(current, max)));
        line.setEditable(true);
        GridPane grid = grid();
        grid.addRow(0, new Label("Line:"), line);
        dialog.getDialogPane().setContent(grid);
        dialog.setResultConverter(b -> {
            if (b != ButtonType.OK) {
                return null;
            }
            commit(line);
            return line.getValue();
        });
        Platform.runLater(() -> {
            line.getEditor().requestFocus();
            line.getEditor().selectAll();
        });
        return dialog.showAndWait();
    }
}
