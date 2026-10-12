package io.mdexporter.ui;

import io.mdexporter.core.ExportOptions;
import io.mdexporter.core.ExportTheme;
import io.mdexporter.core.PageSize;
import io.mdexporter.core.StyleDefinition;
import io.mdexporter.core.StyleLibrary;
import io.mdexporter.core.ThemeFont;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/** Form editing {@link ExportOptions}: page, appearance (style) and diagrams. */
final class OptionsPane extends HBox {

    /**
     * Access to the styles: the library and the style editor (returns the style chosen in the editor, if any).
     */
    record Styles(StyleLibrary library, Function<StyleDefinition, Optional<StyleDefinition>> editor) {
    }

    /** A font choice; {@code null} font = the theme's font. */
    private record FontOption(ThemeFont font) {
    }

    private final ComboBox<PageSize> pageSize = new ComboBox<>();
    private final ComboBox<ExportOptions.Orientation> orientation = new ComboBox<>();
    private final Spinner<Double> margin = new Spinner<>(0.0, 60.0, 20.0, 1.0);
    private final CheckBox toc = new CheckBox("Insert a table of contents");
    private final CheckBox embed = new CheckBox("HTML: embed images (single file)");
    private final ComboBox<String> mermaidTheme = new ComboBox<>();
    private final Spinner<Double> scale = new Spinner<>(1.0, 4.0, 2.0, 0.5);
    private final CheckBox openAfter = new CheckBox("Open the exported file when done");

    private final ComboBox<StyleDefinition> theme = new ComboBox<>();
    private final Styles styles;
    private final Label themeDescription = new Label();
    private final CheckBox customAccent = new CheckBox("Custom");
    private final ColorPicker accent = new ColorPicker();
    private final ComboBox<FontOption> bodyFont = new ComboBox<>();
    private final ComboBox<FontOption> headingFont = new ComboBox<>();
    private final ComboBox<FontOption> codeFont = new ComboBox<>();
    private final Spinner<Integer> textSize = new Spinner<>(60, 200, 100, 5);
    private final CheckBox justify = new CheckBox("Justify paragraphs");
    private final TextField customCss = new TextField();

    OptionsPane(ExportOptions options, Styles styles) {
        this.styles = styles;
        setSpacing(24);
        setPadding(new Insets(4, 0, 4, 0));

        pageSize.getItems().setAll(PageSize.values());
        orientation.getItems().setAll(ExportOptions.Orientation.values());
        mermaidTheme.getItems().setAll("default", "neutral", "forest", "dark", "base");
        margin.setEditable(true);
        margin.setPrefWidth(90);
        scale.setEditable(true);
        scale.setPrefWidth(90);
        scale.setTooltip(new Tooltip("Resolution multiplier of diagram images in Word and PDF"));

        theme.getItems().setAll(styles.library().all());
        theme.setMaxWidth(Double.MAX_VALUE);
        theme.setCellFactory(v -> new StyleCell());
        theme.setButtonCell(new StyleCell());
        Button editStyles = new Button("Edit...");
        editStyles.setTooltip(new Tooltip("Edit the styles, create your own (optionally with the AI)"));
        editStyles.setOnAction(e -> editStyles());
        HBox styleRow = new HBox(6, theme, editStyles);
        HBox.setHgrow(theme, Priority.ALWAYS);
        themeDescription.setWrapText(true);
        themeDescription.getStyleClass().add("hint");
        themeDescription.setMaxWidth(300);
        accent.disableProperty().bind(customAccent.selectedProperty().not());
        accent.setTooltip(new Tooltip("Links, and headings in the Modern style"));
        setupFontCombo(bodyFont, ThemeFont.textFonts());
        setupFontCombo(headingFont, ThemeFont.textFonts());
        setupFontCombo(codeFont, ThemeFont.codeFonts());
        textSize.setEditable(true);
        textSize.setPrefWidth(90);
        customCss.setPromptText("optional .css file (HTML, PDF, preview)");
        Button browseCss = new Button("...");
        browseCss.setTooltip(new Tooltip("Choose a CSS file applied after the style (HTML, PDF and preview)"));
        browseCss.setOnAction(e -> chooseCss());
        HBox cssRow = new HBox(4, customCss, browseCss);
        HBox.setHgrow(customCss, Priority.ALWAYS);
        theme.valueProperty().addListener((o, old, t) -> themeChanged(t));

        GridPane left = grid(110);
        int row = 0;
        left.add(section("Page"), 0, row++, 2, 1);
        left.addRow(row++, new Label("Page size:"), pageSize);
        left.addRow(row++, new Label("Orientation:"), orientation);
        left.addRow(row++, new Label("Margins (mm):"), margin);
        left.add(toc, 1, row++);
        left.add(section("Diagrams & output"), 0, row++, 2, 1);
        left.addRow(row++, new Label("Mermaid theme:"), mermaidTheme);
        left.addRow(row++, new Label("Diagram scale:"), scale);
        left.add(embed, 0, row++, 2, 1);
        left.add(openAfter, 0, row, 2, 1);

        GridPane right = grid(100);
        row = 0;
        right.add(section("Appearance"), 0, row++, 2, 1);
        right.addRow(row++, new Label("Style:"), styleRow);
        right.add(themeDescription, 1, row++);
        HBox accentRow = new HBox(8, customAccent, accent);
        accentRow.setAlignment(Pos.CENTER_LEFT);
        right.addRow(row++, new Label("Accent colour:"), accentRow);
        right.addRow(row++, new Label("Text font:"), bodyFont);
        right.addRow(row++, new Label("Heading font:"), headingFont);
        right.addRow(row++, new Label("Code font:"), codeFont);
        HBox sizeRow = new HBox(6, textSize, new Label("%"));
        sizeRow.setAlignment(Pos.CENTER_LEFT);
        right.addRow(row++, new Label("Text size:"), sizeRow);
        right.add(justify, 1, row++);
        right.addRow(row, new Label("Custom CSS:"), cssRow);

        getChildren().addAll(left, right);
        HBox.setHgrow(right, Priority.ALWAYS);
        load(options);
    }

    private static GridPane grid(double labelWidth) {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        ColumnConstraints labels = new ColumnConstraints();
        labels.setMinWidth(labelWidth);
        ColumnConstraints fields = new ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        fields.setMinWidth(150);
        grid.getColumnConstraints().addAll(labels, fields);
        return grid;
    }

    private static Label section(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-weight: bold;");
        label.setPadding(new Insets(4, 0, 0, 0));
        return label;
    }

    private void setupFontCombo(ComboBox<FontOption> combo, List<ThemeFont> fonts) {
        List<FontOption> items = new ArrayList<>();
        items.add(new FontOption(null));
        fonts.forEach(f -> items.add(new FontOption(f)));
        combo.getItems().setAll(items);
        combo.setMaxWidth(Double.MAX_VALUE);
        combo.setCellFactory(v -> new FontCell(combo));
        combo.setButtonCell(new FontCell(combo));
    }

    /** Shows "Theme (Georgia)" for the default entry; fonts in their own face. */
    private final class FontCell extends ListCell<FontOption> {
        private final ComboBox<FontOption> combo;

        FontCell(ComboBox<FontOption> combo) {
            this.combo = combo;
        }

        @Override
        protected void updateItem(FontOption item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setStyle("");
                return;
            }
            ThemeFont font = item.font() != null ? item.font() : themeFont(combo);
            setText(item.font() == null ? "Style default (" + font.displayName() + ")" : font.displayName());
            setStyle("-fx-font-family: \"" + font.displayName() + "\";");
        }
    }

    private ThemeFont themeFont(ComboBox<FontOption> combo) {
        StyleDefinition t = theme.getValue() != null ? theme.getValue() : ExportTheme.GITHUB.definition();
        return combo == codeFont ? t.codeFont() : combo == headingFont ? t.headingFont() : t.bodyFont();
    }

    /** Style names; custom styles are marked with a star. */
    private static final class StyleCell extends ListCell<StyleDefinition> {
        @Override
        protected void updateItem(StyleDefinition s, boolean empty) {
            super.updateItem(s, empty);
            setText(empty || s == null ? null : s.name() + (s.builtIn() ? "" : "  \u2605"));
            setTooltip(empty || s == null || s.builtIn() ? null : new Tooltip("Custom style"));
        }
    }

    /** Opens the style editor and selects the style chosen there; reloads the list (styles may have changed). */
    private void editStyles() {
        StyleDefinition current = theme.getValue();
        Optional<StyleDefinition> chosen = styles.editor().apply(current);
        String key = chosen.map(StyleDefinition::key).orElse(current == null ? null : current.key());
        selectStyle(key, current);
    }

    private void selectStyle(String key, StyleDefinition fallback) {
        theme.getItems().setAll(styles.library().all());
        StyleDefinition select = theme.getItems().stream().filter(s -> s.key().equals(key)).findFirst()
                .orElse(null);
        if (select == null && fallback != null && !fallback.builtIn()) {
            // e.g. a style file given on the command line, or a deleted style still in use
            theme.getItems().add(fallback);
            select = fallback;
        }
        theme.setValue(select != null ? select : theme.getItems().get(0));
        themeChanged(theme.getValue());
    }

    private void themeChanged(StyleDefinition t) {
        if (t == null) {
            return;
        }
        themeDescription.setText(t.description());
        if (!customAccent.isSelected()) {
            accent.setValue(Color.web("#" + t.palette().accent()));
        }
        // redraw the "Style default (...)" entries
        for (ComboBox<FontOption> combo : List.of(bodyFont, headingFont, codeFont)) {
            FontOption value = combo.getValue();
            combo.setButtonCell(new FontCell(combo));
            combo.setCellFactory(v -> new FontCell(combo));
            combo.setValue(null);
            combo.setValue(value);
        }
    }

    private void chooseCss() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Custom CSS");
        chooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("CSS", "*.css"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        File current = customCss.getText().isBlank() ? null : new File(customCss.getText().trim());
        if (current != null && current.getParentFile() != null && current.getParentFile().isDirectory()) {
            chooser.setInitialDirectory(current.getParentFile());
        }
        File file = chooser.showOpenDialog(getScene() == null ? null : getScene().getWindow());
        if (file != null) {
            customCss.setText(file.getAbsolutePath());
        }
    }

    private static FontOption option(ComboBox<FontOption> combo, ThemeFont font) {
        return combo.getItems().stream().filter(o -> o.font() == font).findFirst().orElse(combo.getItems().get(0));
    }

    void load(ExportOptions o) {
        pageSize.setValue(o.getPageSize());
        orientation.setValue(o.getOrientation());
        margin.getValueFactory().setValue(o.getMarginMm());
        mermaidTheme.setValue(o.getMermaidTheme());
        scale.getValueFactory().setValue(o.getDiagramScale());
        toc.setSelected(o.isTableOfContents());
        embed.setSelected(o.isEmbedImages());
        openAfter.setSelected(o.isOpenAfterExport());
        customAccent.setSelected(o.getAccentColor() != null);
        selectStyle(o.getStyleDefinition().key(), o.getStyleDefinition());
        if (o.getAccentColor() != null) {
            accent.setValue(Color.web("#" + o.getAccentColor()));
        }
        bodyFont.setValue(option(bodyFont, o.getBodyFont()));
        headingFont.setValue(option(headingFont, o.getHeadingFont()));
        codeFont.setValue(option(codeFont, o.getCodeFont()));
        textSize.getValueFactory().setValue((int) Math.round(o.getTextScale() * 100));
        justify.setSelected(o.isJustify());
        customCss.setText(o.getCustomCss() == null ? "" : o.getCustomCss());
    }

    ExportOptions toOptions() {
        commitSpinner(margin);
        commitSpinner(scale);
        InsertDialogs.commit(textSize);
        return ExportOptions.defaults()
                .setPageSize(pageSize.getValue())
                .setOrientation(orientation.getValue())
                .setMarginMm(margin.getValue())
                .setMermaidTheme(mermaidTheme.getValue())
                .setDiagramScale(scale.getValue())
                .setTableOfContents(toc.isSelected())
                .setEmbedImages(embed.isSelected())
                .setOpenAfterExport(openAfter.isSelected())
                .setStyleDefinition(theme.getValue())
                .setAccentColor(customAccent.isSelected() ? hex(accent.getValue()) : null)
                .setBodyFont(bodyFont.getValue() == null ? null : bodyFont.getValue().font())
                .setHeadingFont(headingFont.getValue() == null ? null : headingFont.getValue().font())
                .setCodeFont(codeFont.getValue() == null ? null : codeFont.getValue().font())
                .setTextScale(textSize.getValue() / 100.0)
                .setJustify(justify.isSelected())
                .setCustomCss(customCss.getText());
    }

    private static String hex(Color c) {
        return String.format(Locale.ROOT, "%02X%02X%02X", Math.round(c.getRed() * 255),
                Math.round(c.getGreen() * 255), Math.round(c.getBlue() * 255));
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
