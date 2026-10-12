package io.mdexporter.ui;

import io.mdexporter.ai.AiAgent;
import io.mdexporter.ai.AiClient;
import io.mdexporter.ai.AiException;
import io.mdexporter.ai.AiPrompts;
import io.mdexporter.core.ExportFormat;
import io.mdexporter.core.ExportOptions;
import io.mdexporter.core.ExportService;
import io.mdexporter.core.ExportTheme;
import io.mdexporter.core.MarkdownDocument;
import io.mdexporter.core.MarkdownParser;
import io.mdexporter.core.ProgressListener;
import io.mdexporter.core.ResourceLoader;
import io.mdexporter.core.StyleDefinition;
import io.mdexporter.core.StyleLibrary;
import io.mdexporter.core.ThemeFont;
import io.mdexporter.diagram.DiagramService;
import io.mdexporter.export.ExportContext;
import io.mdexporter.export.HtmlBuilder;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Duration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Edits the export styles (templates): built-in styles can be tweaked and saved as custom copies, custom styles
 * are created, imported, exported and deleted, optionally generated or changed by the AI. A live preview renders
 * a sample document (or the current one); Word and PDF test exports show the result in those formats.
 * The result is the style to use, or nothing when the dialog was closed.
 */
final class StyleEditorDialog extends Dialog<StyleDefinition> {

    /** What the editor needs from the main window. */
    record Services(AppSettings settings, ExportService exportService, DiagramService diagrams,
                    ExecutorService executor, Consumer<Path> opener, Supplier<MarkdownDocument> currentDocument) {
    }

    private static final String SAMPLE = loadSample();

    private final Services services;
    private final StyleLibrary library;
    private final ListView<StyleDefinition> list = new ListView<>();
    private final TextField name = new TextField();
    private final TextField description = new TextField();
    private final Map<String, ColorPicker> colors = new LinkedHashMap<>();
    private final ComboBox<ThemeFont> bodyFont = new ComboBox<>();
    private final ComboBox<ThemeFont> headingFont = new ComboBox<>();
    private final ComboBox<ThemeFont> codeFont = new ComboBox<>();
    private final CheckBox headingRules = new CheckBox("Lines under level 1 and 2 headings");
    private final CheckBox coloredHeadings = new CheckBox("Headings in the accent colour");
    private final CheckBox dark = new CheckBox("Dark page (HTML and PDF; Word stays white)");
    private final TextArea css = new TextArea();
    private final Label builtInNote = new Label();
    private final Button save = new Button("Save");
    private final Button revert = new Button("Revert");
    private final WebView preview = new WebView();
    private final CheckBox previewCurrent = new CheckBox("Preview the current document");
    private final TextField aiRequest = new TextField();
    private final CheckBox aiModify = new CheckBox("Change the current style");
    private final Button aiGenerate = new Button("✨ Generate");
    private final ProgressIndicator aiSpinner = new ProgressIndicator();
    private final Label status = new Label();
    private final PauseTransition previewDelay = new PauseTransition(Duration.millis(300));
    private final AtomicLong previewGeneration = new AtomicLong();
    private final ButtonType useType = new ButtonType("Use This Style", ButtonBar.ButtonData.OK_DONE);

    /** The style the form was loaded from (built-in or saved custom style). */
    private StyleDefinition editing;
    private boolean loadingForm;
    private boolean switching;
    private Path previewFile;
    private AiClient.Cancellation aiCancel;

    StyleEditorDialog(Window owner, Services services, StyleDefinition initial) {
        this.services = services;
        this.library = services.settings().styleLibrary();
        initOwner(owner);
        setTitle("Export Styles");
        setHeaderText(null);
        setResizable(true);

        list.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(StyleDefinition s, boolean empty) {
                super.updateItem(s, empty);
                setText(empty || s == null ? null : s.name() + (s.builtIn() ? "  (built-in)" : ""));
                setStyle(empty || s == null || !s.builtIn() ? "" : "-fx-text-fill: #59636e;");
            }
        });
        list.setPrefWidth(200);
        list.getSelectionModel().selectedItemProperty().addListener((o, old, s) -> {
            if (switching || s == null || s == editing) {
                return;
            }
            if (!confirmLeave()) {
                switching = true;
                Platform.runLater(() -> {
                    list.getSelectionModel().select(old);
                    switching = false;
                });
                return;
            }
            loadForm(s);
        });

        Button add = smallButton("New", "New style based on GitHub", this::newStyle);
        Button duplicate = smallButton("Duplicate", "Save a copy of the selected style", this::duplicate);
        Button delete = smallButton("Delete", "Delete the selected custom style", this::delete);
        Button importButton = smallButton("Import...", "Import a style .json file", this::importStyle);
        Button exportButton = smallButton("Export...", "Save the style as a .json file to share it",
                this::exportStyle);
        Button folder = smallButton("Open Folder", "Show the folder of the custom styles", () -> {
            try {
                Files.createDirectories(library.directory());
                services.opener().accept(library.directory());
            } catch (IOException e) {
                status.setText("✖ " + e.getMessage());
            }
        });
        delete.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
        GridPane listButtons = new GridPane();
        listButtons.setHgap(6);
        listButtons.setVgap(6);
        listButtons.addRow(0, add, duplicate);
        listButtons.addRow(1, delete, importButton);
        listButtons.addRow(2, exportButton, folder);
        VBox left = new VBox(8, new Label("Styles"), list, listButtons);
        VBox.setVgrow(list, Priority.ALWAYS);
        left.setPadding(new Insets(0, 8, 0, 0));

        SplitPane center = new SplitPane(new ScrollPane(form()), previewPane());
        center.setDividerPositions(0.48);
        ((ScrollPane) center.getItems().get(0)).setFitToWidth(true);

        BorderPane content = new BorderPane(center);
        content.setLeft(left);
        status.setWrapText(true);
        content.setBottom(new VBox(6, aiBar(), status));
        BorderPane.setMargin(content.getBottom(), new Insets(8, 0, 0, 0));
        content.setPrefSize(1240, 760);
        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().addAll(useType, ButtonType.CLOSE);

        // keep the dialog open when changes are not saved yet
        Button use = (Button) getDialogPane().lookupButton(useType);
        use.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            if (!confirmLeave()) {
                e.consume();
            }
        });
        Button close = (Button) getDialogPane().lookupButton(ButtonType.CLOSE);
        close.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            if (!confirmLeave()) {
                e.consume();
            }
        });
        setResultConverter(b -> {
            if (aiCancel != null) {
                aiCancel.cancel();
            }
            disposePreview();
            return b == useType ? editing : null;
        });
        previewDelay.setOnFinished(e -> renderPreview());

        reloadList(initial != null ? initial.key() : ExportTheme.GITHUB.name());
    }

    private static String loadSample() {
        try (InputStream in = StyleEditorDialog.class.getResourceAsStream("/io/mdexporter/style-sample.md")) {
            return in == null ? "# Sample\n\nText." : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "# Sample\n\nText.";
        }
    }

    private static Button smallButton(String text, String tooltip, Runnable action) {
        Button b = new Button(text);
        b.setTooltip(new Tooltip(tooltip));
        b.setOnAction(e -> action.run());
        b.setMaxWidth(Double.MAX_VALUE);
        return b;
    }

    // ------------------------------------------------------------------ layout

    private VBox form() {
        String[][] colorFields = {
                {"text", "Text"}, {"heading", "Headings"}, {"accent", "Accent (links)"}, {"muted", "Muted text"},
                {"background", "Page background"}, {"border", "Borders, rules"}, {"subtle", "Code blocks"},
                {"codeBg", "Inline code"}, {"tableHeader", "Table header"}};
        GridPane colorGrid = new GridPane();
        colorGrid.setHgap(8);
        colorGrid.setVgap(6);
        for (int i = 0; i < colorFields.length; i++) {
            ColorPicker picker = new ColorPicker();
            picker.setMaxWidth(Double.MAX_VALUE);
            picker.valueProperty().addListener((o, a, v) -> formChanged());
            colors.put(colorFields[i][0], picker);
            int col = (i % 2) * 2;
            colorGrid.add(new Label(colorFields[i][1] + ":"), col, i / 2);
            colorGrid.add(picker, col + 1, i / 2);
        }
        ColumnConstraints l1 = new ColumnConstraints(118);
        ColumnConstraints f1 = new ColumnConstraints();
        f1.setHgrow(Priority.ALWAYS);
        ColumnConstraints l2 = new ColumnConstraints(104);
        ColumnConstraints f2 = new ColumnConstraints();
        f2.setHgrow(Priority.ALWAYS);
        colorGrid.getColumnConstraints().addAll(l1, f1, l2, f2);

        bodyFont.getItems().setAll(ThemeFont.textFonts());
        headingFont.getItems().setAll(ThemeFont.textFonts());
        codeFont.getItems().setAll(ThemeFont.codeFonts());
        for (ComboBox<ThemeFont> combo : List.of(bodyFont, headingFont, codeFont)) {
            combo.setMaxWidth(Double.MAX_VALUE);
            combo.setCellFactory(v -> fontCell());
            combo.setButtonCell(fontCell());
            combo.valueProperty().addListener((o, a, v) -> formChanged());
        }
        for (CheckBox box : List.of(headingRules, coloredHeadings, dark)) {
            box.selectedProperty().addListener((o, a, v) -> formChanged());
        }
        name.textProperty().addListener((o, a, v) -> formChanged());
        description.textProperty().addListener((o, a, v) -> formChanged());
        css.textProperty().addListener((o, a, v) -> formChanged());
        css.setFont(Font.font("Consolas", 12));
        css.setPrefRowCount(7);
        css.setPromptText("""
                Optional CSS for HTML, PDF and the preview, e.g.
                h1, h2 { text-transform: uppercase; letter-spacing: .05em; }
                blockquote { font-style: italic; }
                Use plain CSS 2.1 for PDF output (no variables, flexbox or web fonts).""");

        GridPane main = new GridPane();
        main.setHgap(8);
        main.setVgap(8);
        ColumnConstraints labels = new ColumnConstraints(100);
        ColumnConstraints fields = new ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        main.getColumnConstraints().addAll(labels, fields);
        main.addRow(0, new Label("Name:"), name);
        main.addRow(1, new Label("Description:"), description);
        GridPane fonts = new GridPane();
        fonts.setHgap(8);
        fonts.setVgap(6);
        fonts.getColumnConstraints().addAll(new ColumnConstraints(100), fields);
        fonts.addRow(0, new Label("Text:"), bodyFont);
        fonts.addRow(1, new Label("Headings:"), headingFont);
        fonts.addRow(2, new Label("Code:"), codeFont);

        save.setOnAction(e -> saveStyle());
        save.getStyleClass().add("accent");
        revert.setOnAction(e -> loadForm(editing));
        Button testPdf = new Button("Test PDF");
        testPdf.setTooltip(new Tooltip("Export the preview document with this style to a temporary PDF and open it"));
        testPdf.setOnAction(e -> testExport(ExportFormat.PDF));
        Button testWord = new Button("Test Word");
        testWord.setTooltip(new Tooltip("Export the preview document with this style to a temporary .docx and "
                + "open it"));
        testWord.setOnAction(e -> testExport(ExportFormat.DOCX));
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox buttons = new HBox(8, save, revert, grow, testPdf, testWord);
        builtInNote.setWrapText(true);
        builtInNote.getStyleClass().add("hint");

        VBox box = new VBox(10, builtInNote, main, section("Colours"), colorGrid, section("Fonts"), fonts,
                section("Layout"), headingRules, coloredHeadings, dark, section("Extra CSS"), css, buttons);
        box.setPadding(new Insets(4, 12, 4, 4));
        return box;
    }

    private static Label section(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-weight: bold;");
        return label;
    }

    private static ListCell<ThemeFont> fontCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(ThemeFont f, boolean empty) {
                super.updateItem(f, empty);
                setText(empty || f == null ? null : f.displayName());
                setStyle(empty || f == null ? "" : "-fx-font-family: \"" + f.displayName() + "\";");
            }
        };
    }

    private VBox previewPane() {
        Label title = new Label("Preview");
        title.setStyle("-fx-font-weight: bold;");
        boolean hasDocument = services.currentDocument() != null && services.currentDocument().get() != null;
        previewCurrent.setDisable(!hasDocument);
        previewCurrent.selectedProperty().addListener((o, a, v) -> renderPreview());
        preview.setContextMenuEnabled(false);
        HBox top = new HBox(12, title, previewCurrent);
        top.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(6, top, preview);
        VBox.setVgrow(preview, Priority.ALWAYS);
        box.setPadding(new Insets(0, 0, 0, 8));
        return box;
    }

    private HBox aiBar() {
        aiRequest.setPromptText("Describe a style, e.g. \"warm sepia book style with serif headings and a dark red "
                + "accent\", or a change: \"bigger, uppercase headings\"");
        aiRequest.setOnAction(e -> generateWithAi());
        aiModify.setSelected(true);
        aiModify.setTooltip(new Tooltip("Send the current style to the AI and ask for changes; unchecked creates a "
                + "new style from scratch"));
        aiGenerate.setOnAction(e -> generateWithAi());
        aiSpinner.setPrefSize(18, 18);
        aiSpinner.setVisible(false);
        HBox.setHgrow(aiRequest, Priority.ALWAYS);
        HBox bar = new HBox(8, new Label("AI:"), aiRequest, aiModify, aiGenerate, aiSpinner);
        bar.setAlignment(Pos.CENTER_LEFT);
        boolean enabled = services.settings().aiEnabled() && services.settings().activeAgent() != null;
        bar.setVisible(enabled);
        bar.setManaged(enabled);
        return bar;
    }

    // ------------------------------------------------------------------ list

    private void reloadList(String selectKey) {
        switching = true;
        try {
            list.getItems().setAll(library.all());
        } finally {
            switching = false;
        }
        StyleDefinition select = list.getItems().stream().filter(s -> s.key().equals(selectKey)).findFirst()
                .orElse(list.getItems().get(0));
        editing = null;
        list.getSelectionModel().select(select);
        if (editing != select) {
            loadForm(select);
        }
        list.scrollTo(select);
    }

    private void loadForm(StyleDefinition s) {
        editing = s;
        loadingForm = true;
        try {
            name.setText(s.name());
            description.setText(s.description());
            ExportTheme.Palette p = s.palette();
            setColor("text", p.text());
            setColor("heading", p.heading());
            setColor("accent", p.accent());
            setColor("muted", p.muted());
            setColor("background", p.background());
            setColor("border", p.border());
            setColor("subtle", p.subtle());
            setColor("codeBg", p.codeBg());
            setColor("tableHeader", p.tableHeader());
            bodyFont.setValue(s.bodyFont());
            headingFont.setValue(s.headingFont());
            codeFont.setValue(s.codeFont());
            headingRules.setSelected(s.headingRules());
            coloredHeadings.setSelected(s.accentHeadings());
            dark.setSelected(s.dark());
            css.setText(s.css());
        } finally {
            loadingForm = false;
        }
        builtInNote.setText(s.builtIn()
                ? "Built-in style: your changes are saved as a new custom style."
                : "Custom style, saved in " + library.directory().resolve(s.id() + ".json"));
        formChanged();
    }

    private void setColor(String key, String rrggbb) {
        colors.get(key).setValue(Color.web("#" + rrggbb));
    }

    private String color(String key) {
        Color c = colors.get(key).getValue();
        return String.format(Locale.ROOT, "%02X%02X%02X", Math.round(c.getRed() * 255),
                Math.round(c.getGreen() * 255), Math.round(c.getBlue() * 255));
    }

    /** The style as currently shown in the form. */
    private StyleDefinition current() {
        ExportTheme.Palette p = new ExportTheme.Palette(color("text"), color("muted"), color("border"),
                color("subtle"), color("background"), color("accent"), color("heading"), color("codeBg"),
                color("tableHeader"));
        return new StyleDefinition(editing == null ? null : editing.id(), name.getText(), description.getText(), p,
                bodyFont.getValue(), headingFont.getValue(), codeFont.getValue(), headingRules.isSelected(),
                coloredHeadings.isSelected(), dark.isSelected(), css.getText(),
                editing != null && editing.builtIn());
    }

    private boolean isModified() {
        return editing != null && !current().sameContent(editing);
    }

    private void formChanged() {
        if (loadingForm) {
            return;
        }
        boolean modified = isModified();
        save.setDisable(!modified);
        revert.setDisable(!modified);
        previewDelay.playFromStart();
    }

    /** Returns false when the user cancels leaving a modified style. */
    private boolean confirmLeave() {
        if (!isModified()) {
            return true;
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(getDialogPane().getScene().getWindow());
        alert.setTitle("Export Styles");
        alert.setHeaderText("Save the changes to " + name.getText() + "?");
        ButtonType saveButton = new ButtonType("Save", ButtonBar.ButtonData.YES);
        ButtonType discard = new ButtonType("Don't Save", ButtonBar.ButtonData.NO);
        alert.getButtonTypes().setAll(saveButton, discard, ButtonType.CANCEL);
        Optional<ButtonType> answer = alert.showAndWait();
        if (answer.isEmpty() || answer.get() == ButtonType.CANCEL) {
            return false;
        }
        if (answer.get() == saveButton) {
            return saveStyle();
        }
        loadForm(editing); // discard
        return true;
    }

    // ------------------------------------------------------------------ actions

    private boolean saveStyle() {
        StyleDefinition style = current();
        if (style.builtIn() && style.name().equalsIgnoreCase(editing.name())) {
            style = style.withName(style.name() + " (custom)");
        }
        try {
            StyleDefinition saved = library.save(style.builtIn() ? style.asCustom(style.name()) : style);
            editing = saved;
            reloadList(saved.key());
            status.setText("Saved " + saved.name() + " to " + library.directory().resolve(saved.id() + ".json"));
            return true;
        } catch (IOException e) {
            status.setText("✖ Cannot save the style: " + e.getMessage());
            return false;
        }
    }

    private void newStyle() {
        if (!confirmLeave()) {
            return;
        }
        TextInputDialog ask = new TextInputDialog("My style");
        ask.initOwner(getDialogPane().getScene().getWindow());
        ask.setTitle("New Style");
        ask.setHeaderText("Name of the new style (based on the selected one)");
        ask.showAndWait().filter(n -> !n.isBlank()).ifPresent(n -> saveCopy(current().asCustom(n.strip())));
    }

    private void duplicate() {
        if (!confirmLeave()) {
            return;
        }
        saveCopy(current().asCustom(current().name() + " copy"));
    }

    private void saveCopy(StyleDefinition copy) {
        try {
            StyleDefinition saved = library.save(copy);
            reloadList(saved.key());
            status.setText("Created " + saved.name());
        } catch (IOException e) {
            status.setText("✖ " + e.getMessage());
        }
    }

    private void delete() {
        StyleDefinition s = list.getSelectionModel().getSelectedItem();
        if (s == null) {
            return;
        }
        if (s.builtIn()) {
            status.setText("Built-in styles cannot be deleted.");
            return;
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "Delete the style \"" + s.name() + "\"?",
                ButtonType.OK, ButtonType.CANCEL);
        alert.initOwner(getDialogPane().getScene().getWindow());
        alert.setHeaderText(null);
        if (alert.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        try {
            library.delete(s);
            editing = null;
            reloadList(ExportTheme.GITHUB.name());
            status.setText("Deleted " + s.name());
        } catch (IOException e) {
            status.setText("✖ " + e.getMessage());
        }
    }

    private void importStyle() {
        if (!confirmLeave()) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import Style");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Style", "*.json"));
        File file = chooser.showOpenDialog(getDialogPane().getScene().getWindow());
        if (file == null) {
            return;
        }
        try {
            StyleDefinition s = StyleLibrary.fromJson(Files.readString(file.toPath(), StandardCharsets.UTF_8),
                    ExportTheme.GITHUB.definition());
            saveCopy(s.asCustom(s.name()));
        } catch (IOException | IllegalArgumentException e) {
            status.setText("✖ Cannot import " + file.getName() + ": " + e.getMessage());
        }
    }

    private void exportStyle() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export Style");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Style", "*.json"));
        chooser.setInitialFileName(name.getText().replaceAll("[\\\\/:*?\"<>|]", "_") + ".json");
        File file = chooser.showSaveDialog(getDialogPane().getScene().getWindow());
        if (file == null) {
            return;
        }
        try {
            Files.writeString(file.toPath(), StyleLibrary.toJson(current()), StandardCharsets.UTF_8);
            status.setText("Exported to " + file);
        } catch (IOException e) {
            status.setText("✖ " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ preview and tests

    private MarkdownDocument previewDocument() {
        if (previewCurrent.isSelected() && services.currentDocument() != null) {
            MarkdownDocument doc = services.currentDocument().get();
            if (doc != null) {
                return doc;
            }
        }
        return MarkdownParser.parse(SAMPLE, Path.of(System.getProperty("user.home")), "style-sample");
    }

    /** The user's page settings, text size and justification with this style (no accent / font overrides). */
    private ExportOptions previewOptions() {
        ExportOptions o = services.settings().loadOptions().copy();
        return o.setStyleDefinition(current()).setAccentColor(null).setBodyFont(null).setHeadingFont(null)
                .setCodeFont(null).setCustomCss(null);
    }

    private void renderPreview() {
        if (editing == null) {
            return;
        }
        long generation = previewGeneration.incrementAndGet();
        MarkdownDocument document = previewDocument();
        ExportOptions options = previewOptions();
        services.executor().submit(() -> {
            if (generation != previewGeneration.get()) {
                return;
            }
            try {
                ExportContext context = new ExportContext(services.diagrams(),
                        new ResourceLoader(document.baseDir()), options, ProgressListener.NONE);
                String html = new HtmlBuilder(HtmlBuilder.Target.PREVIEW, context, null).build(document).toHtml();
                Platform.runLater(() -> {
                    if (generation == previewGeneration.get()) {
                        showPreview(html);
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> status.setText("✖ Preview failed: " + e.getMessage()));
            }
        });
    }

    private void showPreview(String html) {
        try {
            if (previewFile == null) {
                Path dir = Files.createTempDirectory("md-exporter-style");
                dir.toFile().deleteOnExit();
                previewFile = dir.resolve("preview.html");
                previewFile.toFile().deleteOnExit();
            }
            Files.writeString(previewFile, html, StandardCharsets.UTF_8);
            preview.getEngine().load(previewFile.toUri() + "?v=" + previewGeneration.get());
        } catch (IOException e) {
            preview.getEngine().loadContent(html);
        }
    }

    private void disposePreview() {
        previewDelay.stop();
        previewGeneration.incrementAndGet();
        preview.getEngine().load(null);
        if (previewFile != null) {
            try {
                Files.deleteIfExists(previewFile);
                Files.deleteIfExists(previewFile.getParent());
            } catch (IOException ignored) {
                // deleted on exit
            }
        }
    }

    private void testExport(ExportFormat format) {
        MarkdownDocument document = previewDocument();
        ExportOptions options = previewOptions().setTableOfContents(false);
        status.setText("Exporting a test " + format.displayName() + " document...");
        services.executor().submit(() -> {
            try {
                Path dir = Files.createTempDirectory("md-exporter-style-test");
                List<Path> written = services.exportService().export(document, EnumSet.of(format), dir,
                        "style-test", options, null);
                Platform.runLater(() -> {
                    status.setText("Test " + format.displayName() + " written to " + written.get(0));
                    services.opener().accept(written.get(0));
                });
            } catch (Exception e) {
                Platform.runLater(() -> status.setText("✖ Test export failed: " + e.getMessage()));
            }
        });
    }

    // ------------------------------------------------------------------ AI

    private void generateWithAi() {
        AiAgent agent = services.settings().activeAgent();
        String request = aiRequest.getText();
        if (agent == null || request.isBlank() || aiCancel != null) {
            if (request.isBlank()) {
                status.setText("Describe the style you want first.");
            }
            return;
        }
        StyleDefinition base = current();
        String user = AiPrompts.styleUser(request, aiModify.isSelected() ? StyleLibrary.toJson(base) : null);
        String system = AiPrompts.styleSystem(ThemeFont.textFonts().stream().map(ThemeFont::displayName).toList(),
                ThemeFont.codeFonts().stream().map(ThemeFont::displayName).toList());
        AiClient client = agent.provider().createClient(agent);
        AiClient.Cancellation cancel = new AiClient.Cancellation();
        aiCancel = cancel;
        aiGenerate.setDisable(true);
        aiSpinner.setVisible(true);
        long started = System.currentTimeMillis();
        Timeline clock = new Timeline(new KeyFrame(Duration.seconds(1), e -> status.setText(
                "Asking " + agent.name() + " (" + agent.model() + ")... "
                        + (System.currentTimeMillis() - started) / 1000 + " s")));
        clock.setCycleCount(Timeline.INDEFINITE);
        clock.play();
        status.setText("Asking " + agent.name() + " (" + agent.model() + ")...");
        Thread.ofVirtual().name("md-exporter-ai-style").start(() -> {
            StringBuilder answer = new StringBuilder();
            String error = null;
            try {
                client.stream(new AiClient.AiRequest(system, user, Math.min(agent.maxTokens(), 8000)),
                        answer::append, cancel);
            } catch (AiException e) {
                error = e.getMessage();
            } catch (RuntimeException e) {
                error = AgentDialog.message(e);
            }
            String failure = error;
            Platform.runLater(() -> {
                clock.stop();
                aiCancel = null;
                aiGenerate.setDisable(false);
                aiSpinner.setVisible(false);
                if (failure != null) {
                    status.setText("✖ " + failure);
                    return;
                }
                applyAiStyle(answer.toString(), base);
            });
        });
    }

    /** Fills the form with the AI's style; nothing is saved until the user clicks Save. */
    private void applyAiStyle(String answer, StyleDefinition base) {
        StyleDefinition generated;
        try {
            generated = StyleLibrary.fromJson(answer, base);
        } catch (IllegalArgumentException e) {
            status.setText("✖ The answer is not a valid style (" + e.getMessage() + ")");
            return;
        }
        loadingForm = true;
        try {
            name.setText(generated.name());
            description.setText(generated.description());
            ExportTheme.Palette p = generated.palette();
            setColor("text", p.text());
            setColor("heading", p.heading());
            setColor("accent", p.accent());
            setColor("muted", p.muted());
            setColor("background", p.background());
            setColor("border", p.border());
            setColor("subtle", p.subtle());
            setColor("codeBg", p.codeBg());
            setColor("tableHeader", p.tableHeader());
            bodyFont.setValue(generated.bodyFont());
            headingFont.setValue(generated.headingFont());
            codeFont.setValue(generated.codeFont());
            headingRules.setSelected(generated.headingRules());
            coloredHeadings.setSelected(generated.accentHeadings());
            dark.setSelected(generated.dark());
            css.setText(generated.css());
        } finally {
            loadingForm = false;
        }
        formChanged();
        status.setText("AI suggestion loaded: review it in the preview (and with Test PDF / Test Word), then "
                + "Save it, or Revert.");
    }

    static Optional<StyleDefinition> edit(Window owner, Services services, StyleDefinition initial) {
        return new StyleEditorDialog(owner, services, initial).showAndWait();
    }
}
