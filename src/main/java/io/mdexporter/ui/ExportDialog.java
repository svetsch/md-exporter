package io.mdexporter.ui;

import io.mdexporter.core.ExportFormat;
import io.mdexporter.core.ExportOptions;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Dialog to export the current document to several formats at once. */
final class ExportDialog extends Dialog<ExportDialog.Request> {

    record Request(Set<ExportFormat> formats, Path directory, String baseName, ExportOptions options) {
    }

    ExportDialog(Window owner, Set<ExportFormat> formats, File directory, String baseName, ExportOptions options) {
        initOwner(owner);
        setTitle("Export");
        setHeaderText("Export the document to Word, HTML and/or PDF");
        setResizable(true);

        Map<ExportFormat, CheckBox> boxes = new EnumMap<>(ExportFormat.class);
        HBox formatRow = new HBox(16);
        for (ExportFormat f : ExportFormat.values()) {
            CheckBox box = new CheckBox(f.displayName() + " (." + f.extension() + ")");
            box.setSelected(formats.contains(f));
            boxes.put(f, box);
            formatRow.getChildren().add(box);
        }

        TextField dirField = new TextField(directory != null ? directory.getAbsolutePath() : "");
        HBox.setHgrow(dirField, Priority.ALWAYS);
        Button browse = new Button("Browse...");
        browse.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Output folder");
            File current = new File(dirField.getText());
            if (current.isDirectory()) {
                chooser.setInitialDirectory(current);
            }
            File chosen = chooser.showDialog(getDialogPane().getScene().getWindow());
            if (chosen != null) {
                dirField.setText(chosen.getAbsolutePath());
            }
        });
        TextField nameField = new TextField(baseName);

        OptionsPane optionsPane = new OptionsPane(options);

        VBox content = new VBox(8,
                bold("Formats"), formatRow,
                new Separator(),
                bold("Destination"),
                new HBox(6, new Label("Folder:"), dirField, browse),
                new HBox(6, new Label("File name:"), nameField, new Label("(extension added automatically)")),
                new Separator(),
                bold("Options"), optionsPane);
        content.setPadding(new Insets(10));
        content.setPrefWidth(620);
        getDialogPane().setContent(content);

        ButtonType export = new ButtonType("Export", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(export, ButtonType.CANCEL);
        Button exportButton = (Button) getDialogPane().lookupButton(export);
        Runnable validate = () -> exportButton.setDisable(
                boxes.values().stream().noneMatch(CheckBox::isSelected)
                        || dirField.getText().isBlank() || nameField.getText().isBlank());
        boxes.values().forEach(b -> b.selectedProperty().addListener((o, a, v) -> validate.run()));
        dirField.textProperty().addListener((o, a, v) -> validate.run());
        nameField.textProperty().addListener((o, a, v) -> validate.run());
        validate.run();

        setResultConverter(button -> {
            if (button != export) {
                return null;
            }
            Set<ExportFormat> selected = EnumSet.noneOf(ExportFormat.class);
            boxes.forEach((f, b) -> {
                if (b.isSelected()) {
                    selected.add(f);
                }
            });
            String name = nameField.getText().trim().replaceAll("[\\\\/:*?\"<>|]", "_");
            return new Request(selected, Path.of(dirField.getText().trim()), name, optionsPane.toOptions());
        });
    }

    private static Label bold(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-weight: bold;");
        return label;
    }
}
