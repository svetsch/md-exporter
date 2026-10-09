package io.mdexporter.ui;

import io.mdexporter.core.ExportOptions;
import io.mdexporter.core.PageSize;
import javafx.geometry.Insets;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;

/** Form editing {@link ExportOptions}. */
final class OptionsPane extends GridPane {

    private final ComboBox<PageSize> pageSize = new ComboBox<>();
    private final ComboBox<ExportOptions.Orientation> orientation = new ComboBox<>();
    private final Spinner<Double> margin = new Spinner<>(0.0, 60.0, 20.0, 1.0);
    private final CheckBox toc = new CheckBox("Insert a table of contents");
    private final CheckBox embed = new CheckBox("Embed images in the HTML file (single self-contained file)");
    private final ComboBox<String> theme = new ComboBox<>();
    private final Spinner<Double> scale = new Spinner<>(1.0, 4.0, 2.0, 0.5);
    private final CheckBox openAfter = new CheckBox("Open the exported file when done");

    OptionsPane(ExportOptions options) {
        setHgap(10);
        setVgap(8);
        setPadding(new Insets(4, 0, 4, 0));
        ColumnConstraints labels = new ColumnConstraints();
        labels.setMinWidth(130);
        ColumnConstraints fields = new ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        getColumnConstraints().addAll(labels, fields);

        pageSize.getItems().setAll(PageSize.values());
        orientation.getItems().setAll(ExportOptions.Orientation.values());
        theme.getItems().setAll("default", "neutral", "forest", "dark", "base");
        margin.setEditable(true);
        margin.setPrefWidth(90);
        scale.setEditable(true);
        scale.setPrefWidth(90);
        scale.setTooltip(new Tooltip("Resolution multiplier of diagram images in Word and PDF"));

        int row = 0;
        addRow(row++, new Label("Page size:"), pageSize);
        addRow(row++, new Label("Orientation:"), orientation);
        addRow(row++, new Label("Margins (mm):"), margin);
        addRow(row++, new Label("Mermaid theme:"), theme);
        addRow(row++, new Label("Diagram scale:"), scale);
        add(toc, 1, row++);
        add(embed, 1, row++);
        add(openAfter, 1, row);
        load(options);
    }

    void load(ExportOptions o) {
        pageSize.setValue(o.getPageSize());
        orientation.setValue(o.getOrientation());
        margin.getValueFactory().setValue(o.getMarginMm());
        theme.setValue(o.getMermaidTheme());
        scale.getValueFactory().setValue(o.getDiagramScale());
        toc.setSelected(o.isTableOfContents());
        embed.setSelected(o.isEmbedImages());
        openAfter.setSelected(o.isOpenAfterExport());
    }

    ExportOptions toOptions() {
        commitSpinner(margin);
        commitSpinner(scale);
        return ExportOptions.defaults()
                .setPageSize(pageSize.getValue())
                .setOrientation(orientation.getValue())
                .setMarginMm(margin.getValue())
                .setMermaidTheme(theme.getValue())
                .setDiagramScale(scale.getValue())
                .setTableOfContents(toc.isSelected())
                .setEmbedImages(embed.isSelected())
                .setOpenAfterExport(openAfter.isSelected());
    }

    private static void commitSpinner(Spinner<Double> spinner) {
        try {
            String text = spinner.getEditor().getText().replace(',', '.');
            spinner.getValueFactory().setValue(Double.parseDouble(text));
        } catch (NumberFormatException ignored) {
            spinner.getEditor().setText(String.valueOf(spinner.getValue()));
        }
    }
}
