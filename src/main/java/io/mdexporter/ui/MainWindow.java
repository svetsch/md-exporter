package io.mdexporter.ui;

import io.mdexporter.BuildInfo;
import io.mdexporter.ai.AiAgent;
import io.mdexporter.ai.AiPrompts;
import io.mdexporter.core.ExportFormat;
import io.mdexporter.core.ExportOptions;
import io.mdexporter.core.ExportService;
import io.mdexporter.core.MarkdownDocument;
import io.mdexporter.core.MarkdownParser;
import io.mdexporter.core.StyleDefinition;
import io.mdexporter.diagram.DiagramService;
import io.mdexporter.editor.MarkdownEditing;
import io.mdexporter.editor.TextEdit;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Dialog;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Main window: one tab per open Markdown document (editor + live preview) and the export actions. */
public final class MainWindow implements DocumentTab.Host, EditorActions.Context {

    private static final String APP_NAME = "Markdown Exporter";

    private final Stage stage;
    private final HostServices hostServices;
    private final AppSettings settings = new AppSettings();
    private final DiagramService diagrams = DiagramService.createDefault();
    private final ExportService exportService = new ExportService(diagrams);
    private final ExecutorService previewExecutor = Executors.newSingleThreadExecutor(daemon("preview"));
    private final ExecutorService exportExecutor = Executors.newSingleThreadExecutor(daemon("export"));

    private final TabPane tabs = new TabPane();
    private final Label status = new Label("Ready");
    private final Label stats = new Label();
    private final ProgressBar progress = new ProgressBar();
    private final Hyperlink openResult = new Hyperlink("Open");
    private final Hyperlink showFolder = new Hyperlink("Show folder");
    private final Menu recentMenu = new Menu("Open _Recent");
    private final CheckMenuItem wrapItem = new CheckMenuItem("_Wrap Lines");
    private final CheckMenuItem previewItem = new CheckMenuItem("Show _Preview");
    private final CheckMenuItem syncItem = new CheckMenuItem("_Scroll Preview to Cursor");
    private final CheckMenuItem formatBarItem = new CheckMenuItem("_Formatting Tool Bar");
    private final EditorActions actions = new EditorActions(this);
    private ToolBar formatBar;
    private final ComboBox<StyleDefinition> styleCombo = new ComboBox<>();
    private boolean updatingStyleBox;
    private Menu aiMenu;
    private MenuButton aiButton;
    private Separator aiSeparator;

    private ExportOptions options;
    private boolean busy;
    private boolean closing;
    private Path lastExport;
    private int untitledCounter;

    public MainWindow(Stage stage, HostServices hostServices) {
        this.stage = stage;
        this.hostServices = hostServices;
        this.options = settings.loadOptions();
        buildUi();
    }

    // ------------------------------------------------------------------ DocumentTab.Host

    @Override
    public DiagramService diagrams() {
        return diagrams;
    }

    @Override
    public ExecutorService previewExecutor() {
        return previewExecutor;
    }

    @Override
    public ExportOptions options() {
        return options;
    }

    @Override
    public HostServices hostServices() {
        return hostServices;
    }

    @Override
    public void documentChanged(DocumentTab document) {
        if (document == current()) {
            updateTitle();
            updateStats();
        }
    }

    @Override
    public void showStatus(String message) {
        if (!busy) {
            status.setText(message);
        }
    }

    @Override
    public boolean smartEditing() {
        return settings.smartEditing();
    }

    @Override
    public boolean syncPreview() {
        return syncItem.isSelected();
    }

    @Override
    public void zoom(int direction) {
        double size = direction == 0 ? AppSettings.DEFAULT_FONT_SIZE
                : Math.max(8, Math.min(40, settings.fontSize() + direction));
        settings.fontSize(size);
        Font font = editorFont();
        documents().forEach(d -> d.setEditorFont(font));
        showStatus("Editor font size " + Math.round(size));
    }

    private Font editorFont() {
        return Font.font(settings.fontFamily(), settings.fontSize());
    }

    // ------------------------------------------------------------------ EditorActions.Context

    @Override
    public DocumentTab current() {
        Tab tab = tabs.getSelectionModel().getSelectedItem();
        return tab == null ? null : (DocumentTab) tab.getUserData();
    }

    @Override
    public Window window() {
        return stage;
    }

    @Override
    public boolean aiEnabled() {
        return settings.aiEnabled();
    }

    @Override
    public Menu agentMenu() {
        Menu menu = new Menu("A_gent");
        ToggleGroup group = new ToggleGroup();
        AiAgent active = settings.activeAgent();
        for (AiAgent agent : settings.aiAgents()) {
            RadioMenuItem item = new RadioMenuItem(agent.toString());
            item.setMnemonicParsing(false);
            item.setToggleGroup(group);
            item.setSelected(active != null && active.id().equals(agent.id()));
            item.setOnAction(e -> {
                settings.activeAgentId(agent.id());
                showStatus("AI agent: " + agent);
            });
            menu.getItems().add(item);
        }
        if (!menu.getItems().isEmpty()) {
            menu.getItems().add(new SeparatorMenuItem());
        }
        menu.getItems().add(item("_Manage Agents...", null, () -> openSettings(SettingsDialog.TAB_AI)));
        return menu;
    }

    @Override
    public void askAi(AiPrompts.Preset preset) {
        DocumentTab doc = current();
        if (doc == null || !settings.aiEnabled()) {
            return;
        }
        if (settings.aiAgents().isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                    "Add an AI agent (Claude, OpenAI, Gemini, Ollama...) with its API key first.",
                    ButtonType.OK, ButtonType.CANCEL);
            alert.initOwner(stage);
            alert.setTitle(APP_NAME);
            alert.setHeaderText("No AI agent configured");
            if (alert.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                return;
            }
            openSettings(SettingsDialog.TAB_AI);
            if (settings.aiAgents().isEmpty() || !settings.aiEnabled()) {
                return;
            }
        }
        TextArea editor = doc.editor();
        String text = editor.getText();
        int start = editor.getSelection().getStart();
        int end = editor.getSelection().getEnd();
        AiAssistDialog dialog = new AiAssistDialog(stage, settings, text, editor.getSelectedText(),
                editor.getFont(), preset);
        // the dialog is modal: the document cannot change while it is open
        dialog.showAndWait().ifPresent(outcome -> {
            String answer = outcome.text();
            TextEdit edit;
            if (outcome.action() == AiAssistDialog.Action.REPLACE) {
                edit = outcome.wholeDocument()
                        ? TextEdit.between(text, answer)
                        : new TextEdit(start, end, answer, start, start + answer.length());
            } else {
                int last = end > start && text.charAt(end - 1) == '\n' ? end - 1 : end;
                int at = outcome.wholeDocument() ? text.length() : MarkdownEditing.lineEnd(text, last);
                edit = MarkdownEditing.insert(text, at, at, MarkdownEditing.MARK_START + answer.strip()
                        + MarkdownEditing.MARK_END, true);
            }
            doc.apply(edit);
            showStatus("AI suggestion applied \u2013 Ctrl+Z undoes it");
        });
    }

    // ------------------------------------------------------------------ UI construction

    private void buildUi() {
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        tabs.setTabDragPolicy(TabPane.TabDragPolicy.REORDER);
        tabs.getSelectionModel().selectedItemProperty().addListener((obs, old, tab) -> {
            updateTitle();
            updateStats();
            DocumentTab doc = current();
            if (doc != null) {
                Platform.runLater(() -> doc.editor().requestFocus());
            }
        });
        tabs.getTabs().addListener((ListChangeListener<Tab>) change -> {
            if (tabs.getTabs().isEmpty() && !closing) {
                Platform.runLater(() -> { // always keep one document open (re-checked: drag & drop may re-add)
                    if (tabs.getTabs().isEmpty() && !closing) {
                        newDocument();
                    }
                });
            }
        });

        progress.setPrefWidth(160);
        progress.setVisible(false);
        progress.setManaged(false);
        openResult.setVisible(false);
        showFolder.setVisible(false);
        openResult.setOnAction(e -> openPath(lastExport));
        showFolder.setOnAction(e -> {
            if (lastExport != null) {
                openPath(lastExport.toAbsolutePath().getParent());
            }
        });
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox statusBar = new HBox(8, status, openResult, showFolder, grow, progress, stats);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.setPadding(new Insets(3, 10, 3, 10));
        statusBar.getStyleClass().add("status-bar");

        BorderPane root = new BorderPane(tabs);
        formatBar = actions.formatBar();
        aiSeparator = new Separator();
        aiButton = actions.aiButton();
        formatBar.getItems().addAll(new Separator(), styleBox(), aiSeparator, aiButton);
        root.setTop(new VBox(buildMenu(), formatBar));
        applyViewSettings();
        root.setBottom(statusBar);

        double[] bounds = settings.windowBounds();
        Scene scene = new Scene(root, bounds[0], bounds[1]);
        scene.getStylesheets().add(MainWindow.class.getResource("/io/mdexporter/css/app.css").toExternalForm());
        scene.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) {
                e.acceptTransferModes(TransferMode.COPY);
            }
            e.consume();
        });
        scene.setOnDragDropped(e -> {
            List<File> files = e.getDragboard().getFiles();
            boolean ok = files != null && !files.isEmpty();
            if (ok) {
                List<Path> paths = files.stream().filter(File::isFile).map(File::toPath).toList();
                Platform.runLater(() -> openFiles(paths));
            }
            e.setDropCompleted(ok);
            e.consume();
        });

        stage.setScene(scene);
        stage.getIcons().add(createIcon());
        stage.setOnCloseRequest(e -> {
            if (!confirmCloseAll()) {
                e.consume();
                return;
            }
            DocumentTab doc = current();
            settings.windowBounds(stage.getWidth(), stage.getHeight(),
                    doc != null ? doc.dividerPosition() : 0.5);
            settings.flush();
            closing = true;
            shutdown();
            Platform.exit();
        });
    }

    private MenuBar buildMenu() {
        MenuItem exit = item("E_xit", null, () -> stage.fireEvent(
                new javafx.stage.WindowEvent(stage, javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST)));
        rebuildRecentMenu();
        Menu file = new Menu("_File", null,
                item("_New", "Shortcut+N", this::newDocument),
                item("_Open...", "Shortcut+O", this::openDialog),
                recentMenu,
                new SeparatorMenuItem(),
                item("_Save", "Shortcut+S", () -> save(current())),
                item("Save _As...", "Shortcut+Shift+S", () -> saveAs(current())),
                item("Save A_ll", "Shortcut+Alt+S", this::saveAll),
                new SeparatorMenuItem(),
                item("_Close Tab", "Shortcut+W", () -> closeTab(tabs.getSelectionModel().getSelectedItem())),
                item("Close All Tabs", "Shortcut+Shift+W", () -> closeTabs(List.copyOf(tabs.getTabs()))),
                new SeparatorMenuItem(),
                item("Se_ttings...", "Shortcut+COMMA", () -> openSettings(SettingsDialog.TAB_EDITOR)),
                new SeparatorMenuItem(),
                exit);

        Menu export = new Menu("_Export", null,
                item("Export to _Word...", "Shortcut+Shift+D", () -> exportSingle(ExportFormat.DOCX)),
                item("Export to _HTML...", "Shortcut+Shift+H", () -> exportSingle(ExportFormat.HTML)),
                item("Export to _PDF...", "Shortcut+Shift+P", () -> exportSingle(ExportFormat.PDF)),
                new SeparatorMenuItem(),
                item("Export _Multiple Formats...", "Shortcut+E", this::exportMultiple),
                new SeparatorMenuItem(),
                item("Export _Options...", null, () -> openSettings(SettingsDialog.TAB_EXPORT)),
                item("Export _Styles...", null, this::editStyles));

        wrapItem.setSelected(settings.wrapText());
        wrapItem.selectedProperty().addListener((o, a, v) -> {
            documents().forEach(d -> d.setWrapText(v));
            settings.wrapText(v);
        });
        previewItem.setAccelerator(KeyCombination.keyCombination("Shortcut+Shift+V"));
        previewItem.setSelected(settings.showPreview());
        previewItem.selectedProperty().addListener((o, a, v) -> {
            documents().forEach(d -> d.setPreviewVisible(v));
            settings.showPreview(v);
        });
        syncItem.setSelected(settings.syncPreview());
        syncItem.disableProperty().bind(previewItem.selectedProperty().not());
        syncItem.selectedProperty().addListener((o, a, v) -> {
            settings.syncPreview(v);
            DocumentTab doc = current();
            if (v && doc != null) {
                doc.syncPreviewToCaret();
            }
        });
        formatBarItem.setSelected(settings.showFormatBar());
        formatBarItem.selectedProperty().addListener((o, a, v) -> {
            settings.showFormatBar(v);
            applyViewSettings();
        });
        Menu view = new Menu("_View", null,
                previewItem,
                syncItem,
                item("_Refresh Preview", "F5", () -> {
                    diagrams.clearCache();
                    documents().forEach(DocumentTab::refreshPreview);
                }),
                new SeparatorMenuItem(),
                formatBarItem,
                wrapItem,
                item("Zoom _In", "Shortcut+ADD", () -> zoom(1)),
                item("Zoom _Out", "Shortcut+SUBTRACT", () -> zoom(-1)),
                item("Reset _Zoom", "Shortcut+NUMPAD0", () -> zoom(0)),
                new SeparatorMenuItem(),
                item("_Next Tab", "Shortcut+Page_Down", () -> tabs.getSelectionModel().selectNext()),
                item("_Previous Tab", "Shortcut+Page_Up", () -> tabs.getSelectionModel().selectPrevious()));

        Menu help = new Menu("_Help", null,
                item("_Feature Showcase", null, this::openShowcase),
                item("_About", null, this::about));
        aiMenu = actions.aiMenu();
        MenuBar bar = new MenuBar(file, actions.editMenu(), actions.formatMenu(true), actions.insertMenu(true),
                actions.tableMenu(true), aiMenu, export, view, help);
        bar.setUseSystemMenuBar(true);
        return bar;
    }

    private static MenuItem item(String text, String accelerator, Runnable action) {
        MenuItem item = new MenuItem(text);
        if (accelerator != null) {
            item.setAccelerator(KeyCombination.keyCombination(accelerator));
        }
        item.setOnAction(e -> action.run());
        return item;
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, "md-exporter-" + name);
            t.setDaemon(true);
            return t;
        };
    }

    // ------------------------------------------------------------------ lifecycle

    /** Shows the window with the given files open, or the feature showcase when there are none. */
    public void show(List<Path> initialFiles) {
        stage.show();
        if (initialFiles == null || initialFiles.isEmpty()) {
            openShowcase();
        } else {
            openFiles(initialFiles);
        }
    }

    public void shutdown() {
        previewExecutor.shutdownNow();
        exportExecutor.shutdownNow();
    }

    // ------------------------------------------------------------------ tabs

    private List<DocumentTab> documents() {
        return tabs.getTabs().stream().map(t -> (DocumentTab) t.getUserData()).toList();
    }

    private DocumentTab addTab(boolean showcase) {
        String name = showcase ? "showcase.md" : "Untitled-" + (++untitledCounter) + ".md";
        DocumentTab current = current();
        double divider = current != null ? current.dividerPosition() : settings.windowBounds()[2];
        DocumentTab doc = new DocumentTab(this, name, showcase, divider, wrapItem.isSelected(), editorFont(),
                previewItem.isSelected());
        doc.editor().setContextMenu(actions.contextMenu(doc));
        Tab tab = doc.tab();
        tab.setOnCloseRequest(e -> {
            if (!confirmDiscard(doc)) {
                e.consume();
            }
        });
        tab.setOnClosed(e -> doc.dispose()); // closed with the tab's close button
        tab.setContextMenu(tabMenu(tab));
        tabs.getTabs().add(tab);
        tabs.getSelectionModel().select(tab);
        return doc;
    }

    private ContextMenu tabMenu(Tab tab) {
        MenuItem close = item("Close", null, () -> closeTab(tab));
        MenuItem closeOthers = item("Close Others", null,
                () -> closeTabs(tabs.getTabs().stream().filter(t -> t != tab).toList()));
        MenuItem closeAll = item("Close All", null, () -> closeTabs(List.copyOf(tabs.getTabs())));
        MenuItem reveal = item("Show in Folder", null, () -> {
            Path file = ((DocumentTab) tab.getUserData()).file();
            if (file != null) {
                openPath(file.getParent());
            }
        });
        ContextMenu menu = new ContextMenu(close, closeOthers, closeAll, new SeparatorMenuItem(), reveal);
        menu.setOnShowing(e -> reveal.setDisable(((DocumentTab) tab.getUserData()).file() == null));
        return menu;
    }

    private void closeTab(Tab tab) {
        if (tab != null && confirmDiscard((DocumentTab) tab.getUserData())) {
            removeTab(tab);
        }
    }

    private void closeTabs(List<Tab> toClose) {
        for (Tab tab : toClose) {
            if (!confirmDiscard((DocumentTab) tab.getUserData())) {
                return;
            }
            removeTab(tab);
        }
    }

    /** Removes a tab programmatically (which, unlike the close button, does not fire onClosed). */
    private void removeTab(Tab tab) {
        tabs.getTabs().remove(tab);
        ((DocumentTab) tab.getUserData()).dispose();
    }

    private DocumentTab findTab(Path file) {
        Path abs = file.toAbsolutePath().normalize();
        for (DocumentTab doc : documents()) {
            if (doc.file() != null && doc.file().normalize().equals(abs)) {
                return doc;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ documents

    private void newDocument() {
        addTab(false).editor().requestFocus();
    }

    private void openShowcase() {
        for (DocumentTab doc : documents()) {
            if (doc.isShowcase()) {
                tabs.getSelectionModel().select(doc.tab());
                return;
            }
        }
        try (InputStream in = MainWindow.class.getResourceAsStream("/io/mdexporter/welcome.md")) {
            String text = in == null ? "# Welcome\n" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
            DocumentTab pristine = current() != null && current().isPristine() ? current() : null;
            DocumentTab doc = addTab(true);
            doc.load(null, text);
            if (pristine != null) {
                removeTab(pristine.tab());
            }
        } catch (IOException e) {
            error("Cannot load the showcase document", e);
        }
    }

    private void openDialog() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open Markdown");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Markdown", "*.md", "*.markdown", "*.mdown", "*.mkd", "*.txt"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        DocumentTab doc = current();
        File dir = doc != null && doc.file() != null ? doc.file().getParent().toFile() : settings.directory("openDir");
        if (dir != null && dir.isDirectory()) {
            chooser.setInitialDirectory(dir);
        }
        List<File> files = chooser.showOpenMultipleDialog(stage);
        if (files != null) {
            openFiles(files.stream().map(File::toPath).toList());
        }
    }

    private void openFiles(List<Path> paths) {
        for (Path path : paths) {
            openFile(path);
        }
    }

    /** Opens a file in a new tab, or selects the tab already showing it. */
    private void openFile(Path path) {
        DocumentTab existing = findTab(path);
        if (existing != null) {
            tabs.getSelectionModel().select(existing.tab());
            return;
        }
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            DocumentTab reuse = current() != null && current().isPristine() ? current() : null;
            DocumentTab doc = addTab(false);
            doc.load(path, text);
            if (reuse != null) {
                removeTab(reuse.tab());
            }
            Path abs = path.toAbsolutePath();
            settings.directory("openDir", abs.getParent().toFile());
            settings.addRecent(abs.toFile());
            rebuildRecentMenu();
            status.setText("Opened " + abs);
        } catch (IOException e) {
            error("Cannot open " + path, e);
        }
    }

    private void rebuildRecentMenu() {
        recentMenu.getItems().clear();
        for (File f : settings.recentFiles()) {
            MenuItem item = new MenuItem(f.getAbsolutePath());
            item.setMnemonicParsing(false);
            item.setOnAction(e -> openFile(f.toPath()));
            recentMenu.getItems().add(item);
        }
        recentMenu.setDisable(recentMenu.getItems().isEmpty());
    }

    private boolean save(DocumentTab doc) {
        if (doc == null) {
            return false;
        }
        if (doc.file() == null) {
            return saveAs(doc);
        }
        return write(doc, doc.file());
    }

    private boolean saveAs(DocumentTab doc) {
        if (doc == null) {
            return false;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save Markdown");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Markdown", "*.md"));
        File dir = doc.file() != null ? doc.file().getParent().toFile() : settings.directory("openDir");
        if (dir != null && dir.isDirectory()) {
            chooser.setInitialDirectory(dir);
        }
        chooser.setInitialFileName(doc.baseName() + ".md");
        File file = chooser.showSaveDialog(stage);
        if (file == null) {
            return false;
        }
        DocumentTab other = findTab(file.toPath());
        if (other != null && other != doc) {
            error("Cannot save", new IOException(file + " is open in another tab; close that tab first."));
            return false;
        }
        boolean ok = write(doc, file.toPath());
        if (ok) {
            settings.addRecent(file);
            rebuildRecentMenu();
        }
        return ok;
    }

    private void saveAll() {
        for (DocumentTab doc : documents()) {
            if (doc.isDirty()) {
                tabs.getSelectionModel().select(doc.tab());
                if (!save(doc)) {
                    return;
                }
            }
        }
    }

    private boolean write(DocumentTab doc, Path target) {
        try {
            Files.writeString(target, doc.editor().getText(), StandardCharsets.UTF_8);
            doc.saved(target);
            status.setText("Saved " + target.toAbsolutePath());
            return true;
        } catch (IOException e) {
            error("Cannot save " + target, e);
            return false;
        }
    }

    /** Returns true when the document may be closed (saved, discarded or unmodified). */
    private boolean confirmDiscard(DocumentTab doc) {
        if (doc == null || !doc.isDirty() || doc.editor().getText().isEmpty()) {
            return true;
        }
        tabs.getSelectionModel().select(doc.tab());
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(stage);
        alert.setTitle(APP_NAME);
        alert.setHeaderText("Save changes to " + doc.displayName() + "?");
        ButtonType saveButton = new ButtonType("Save", ButtonBar.ButtonData.YES);
        ButtonType discard = new ButtonType("Don't Save", ButtonBar.ButtonData.NO);
        alert.getButtonTypes().setAll(saveButton, discard, ButtonType.CANCEL);
        Optional<ButtonType> answer = alert.showAndWait();
        if (answer.isEmpty() || answer.get() == ButtonType.CANCEL) {
            return false;
        }
        return answer.get() != saveButton || save(doc);
    }

    private boolean confirmCloseAll() {
        for (DocumentTab doc : new ArrayList<>(documents())) {
            if (!confirmDiscard(doc)) {
                return false;
            }
        }
        return true;
    }

    private void updateTitle() {
        DocumentTab doc = current();
        stage.setTitle(doc == null ? APP_NAME
                : (doc.isDirty() ? "• " : "") + doc.displayName() + " — " + APP_NAME);
    }

    private void updateStats() {
        DocumentTab doc = current();
        if (doc == null) {
            stats.setText("");
            return;
        }
        String text = doc.editor().getText();
        int words = text.isBlank() ? 0 : text.trim().split("\\s+").length;
        stats.setText(words + " words · " + doc.editor().getParagraphs().size() + " lines");
    }

    // ------------------------------------------------------------------ export

    private void exportSingle(ExportFormat format) {
        DocumentTab doc = current();
        if (busy || doc == null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export " + doc.displayName() + " to " + format.displayName());
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter(format.displayName(), "*." + format.extension()));
        File dir = exportDirectory(doc);
        if (dir != null) {
            chooser.setInitialDirectory(dir);
        }
        chooser.setInitialFileName(doc.baseName() + "." + format.extension());
        File file = chooser.showSaveDialog(stage);
        if (file == null) {
            return;
        }
        Path target = file.toPath();
        if (!target.getFileName().toString().toLowerCase(Locale.ROOT).endsWith("." + format.extension())) {
            target = target.resolveSibling(target.getFileName() + "." + format.extension());
        }
        settings.directory("exportDir", target.toAbsolutePath().getParent().toFile());
        runExport(doc, EnumSet.of(format), target.toAbsolutePath().getParent(),
                MarkdownParser.baseName(target.getFileName().toString()), options.copy());
    }

    private void exportMultiple() {
        DocumentTab doc = current();
        if (busy || doc == null) {
            return;
        }
        ExportDialog dialog = new ExportDialog(stage, settings.loadFormats(), exportDirectory(doc), doc.baseName(),
                options, styles());
        dialog.setHeaderText("Export " + doc.displayName() + " to Word, HTML and/or PDF");
        dialog.showAndWait().ifPresent(request -> {
            boolean restyled = !request.options().style().equals(options.style())
                    || !request.options().getMermaidTheme().equals(options.getMermaidTheme());
            options = request.options();
            settings.saveOptions(options);
            if (restyled) {
                documents().forEach(DocumentTab::refreshPreview);
                updateStyleBox();
            }
            settings.saveFormats(request.formats());
            settings.directory("exportDir", request.directory().toFile());
            runExport(doc, request.formats(), request.directory(), request.baseName(), options.copy());
        });
    }

    /** Opens the settings dialog on the given tab and applies the changes. */
    private void openSettings(int tab) {
        String oldTheme = options.getMermaidTheme();
        var oldStyle = options.style();
        SettingsDialog.show(stage, settings, options, tab, styles()).ifPresent(o -> {
            options = o;
            wrapItem.setSelected(settings.wrapText());
            previewItem.setSelected(settings.showPreview());
            syncItem.setSelected(settings.syncPreview());
            formatBarItem.setSelected(settings.showFormatBar());
            Font font = editorFont();
            documents().forEach(d -> d.setEditorFont(font));
            applyViewSettings();
            if (!o.getMermaidTheme().equals(oldTheme) || !o.style().equals(oldStyle)) {
                documents().forEach(DocumentTab::refreshPreview);
            }
            updateStyleBox();
            showStatus("Settings saved to " + settings.store().description());
        });
    }

    /** Shows or hides the formatting tool bar and the AI entries. */
    private void applyViewSettings() {
        boolean bar = settings.showFormatBar();
        formatBar.setVisible(bar);
        formatBar.setManaged(bar);
        boolean ai = settings.aiEnabled();
        aiMenu.setVisible(ai);
        aiButton.setVisible(ai);
        aiButton.setManaged(ai);
        aiSeparator.setVisible(ai);
        aiSeparator.setManaged(ai);
    }

    private File exportDirectory(DocumentTab doc) {
        File dir = settings.directory("exportDir");
        if (dir == null && doc.file() != null) {
            dir = doc.file().getParent().toFile();
        }
        return dir;
    }

    private void runExport(DocumentTab doc, Set<ExportFormat> formats, Path directory, String baseName,
                           ExportOptions opts) {
        MarkdownDocument document = doc.parse();
        Task<List<Path>> task = new Task<>() {
            @Override
            protected List<Path> call() throws Exception {
                return exportService.export(document, formats, directory, baseName, opts, (value, message) -> {
                    updateMessage(message);
                    updateProgress(value < 0 ? -1 : value, 1);
                });
            }
        };
        setBusy(true);
        status.textProperty().bind(task.messageProperty());
        progress.progressProperty().bind(task.progressProperty());
        task.setOnSucceeded(e -> {
            setBusy(false);
            List<Path> written = task.getValue();
            lastExport = written.get(written.size() - 1);
            status.setText(written.size() == 1
                    ? "Exported to " + lastExport
                    : "Exported " + written.size() + " files to " + directory);
            openResult.setVisible(true);
            showFolder.setVisible(true);
            if (opts.isOpenAfterExport()) {
                written.forEach(this::openPath);
            }
        });
        task.setOnFailed(e -> {
            setBusy(false);
            status.setText("Export failed");
            error("Export failed", task.getException());
        });
        exportExecutor.submit(task);
    }

    private void setBusy(boolean value) {
        busy = value;
        if (!value) {
            status.textProperty().unbind();
            progress.progressProperty().unbind();
        }
        openResult.setVisible(false);
        showFolder.setVisible(false);
        progress.setVisible(value);
        progress.setManaged(value);
        stage.getScene().setCursor(value ? javafx.scene.Cursor.WAIT : javafx.scene.Cursor.DEFAULT);
    }

    // ------------------------------------------------------------------ export styles

    private StyleEditorDialog.Services styleServices() {
        DocumentTab doc = current();
        return new StyleEditorDialog.Services(settings, exportService, diagrams, previewExecutor, this::openPath,
                doc == null ? null : doc::parse);
    }

    /** Library and editor for the option forms; the editor is opened above the form's own dialog. */
    private OptionsPane.Styles styles() {
        return new OptionsPane.Styles(settings.styleLibrary(), current -> {
            javafx.stage.Window owner = javafx.stage.Window.getWindows().stream()
                    .filter(javafx.stage.Window::isFocused).findFirst().orElse(stage);
            return StyleEditorDialog.edit(owner, styleServices(), current);
        });
    }

    /** Export → Export Styles: edit the styles; the chosen one becomes the export style. */
    private void editStyles() {
        var oldStyle = options.style();
        StyleEditorDialog.edit(stage, styleServices(), options.getStyleDefinition()).ifPresentOrElse(
                chosen -> options.setStyleDefinition(chosen),
                // the current style may have been edited or deleted
                () -> options.setStyleDefinition(settings.styleLibrary().find(options.getStyleDefinition().key())
                        .orElse(io.mdexporter.core.ExportTheme.GITHUB.definition())));
        settings.saveOptions(options);
        if (!options.style().equals(oldStyle)) {
            documents().forEach(DocumentTab::refreshPreview);
        }
        updateStyleBox();
        showStatus("Export style: " + options.getStyleDefinition().name());
    }

    /** Tool bar: export style selector (built-in and custom styles) and a button opening the style editor. */
    private HBox styleBox() {
        styleCombo.setTooltip(new Tooltip("Export style of Word, HTML and PDF documents (also shown in the "
                + "preview)"));
        styleCombo.setPrefWidth(150);
        styleCombo.setFocusTraversable(false);
        styleCombo.setCellFactory(v -> new StyleNameCell());
        styleCombo.setButtonCell(new StyleNameCell());
        // the list is re-read when opened: styles may have been added in the editor or the styles folder
        styleCombo.setOnShowing(e -> updateStyleBox());
        styleCombo.valueProperty().addListener((o, old, style) -> {
            if (updatingStyleBox || style == null || style.key().equals(options.getStyleDefinition().key())
                    && style.sameContent(options.getStyleDefinition())) {
                return;
            }
            var oldStyle = options.style();
            options.setStyleDefinition(style);
            settings.saveOptions(options);
            if (!options.style().equals(oldStyle)) {
                documents().forEach(DocumentTab::refreshPreview);
            }
            showStatus("Export style: " + style.name());
            DocumentTab doc = current();
            if (doc != null) {
                Platform.runLater(() -> doc.editor().requestFocus());
            }
        });
        Button edit = new Button("\u270E");
        edit.setTooltip(new Tooltip("Edit the export styles, create your own (also with the AI)"));
        edit.setFocusTraversable(false);
        edit.getStyleClass().add("format-button");
        edit.setOnAction(e -> editStyles());
        Label label = new Label("Style:");
        HBox box = new HBox(4, label, styleCombo, edit);
        box.setAlignment(Pos.CENTER_LEFT);
        updateStyleBox();
        return box;
    }

    /** Shows the current export style in the tool bar (after it changed in a dialog). */
    private void updateStyleBox() {
        updatingStyleBox = true;
        try {
            StyleDefinition current = options.getStyleDefinition();
            List<StyleDefinition> all = new ArrayList<>(settings.styleLibrary().all());
            StyleDefinition select = all.stream().filter(s -> s.key().equals(current.key())).findFirst()
                    .orElse(null);
            if (select == null) {
                all.add(current); // e.g. a deleted custom style still in use
                select = current;
            }
            styleCombo.getItems().setAll(all);
            styleCombo.setValue(select);
        } finally {
            updatingStyleBox = false;
        }
    }

    /** Style names; custom styles are marked with a star. */
    private static final class StyleNameCell extends javafx.scene.control.ListCell<StyleDefinition> {
        @Override
        protected void updateItem(StyleDefinition s, boolean empty) {
            super.updateItem(s, empty);
            setText(empty || s == null ? null : s.name() + (s.builtIn() ? "" : "  \u2605"));
        }
    }

    private void openPath(Path path) {
        if (path != null) {
            hostServices.showDocument(path.toUri().toString());
        }
    }

    // ------------------------------------------------------------------ misc

    private void about() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(stage);
        alert.setTitle("About " + APP_NAME);
        alert.setHeaderText(APP_NAME + " " + BuildInfo.version());
        String commit = BuildInfo.commit();
        StringBuilder build = new StringBuilder();
        build.append("Version: ").append(BuildInfo.version()).append('\n');
        if (commit != null) {
            build.append("Commit: ").append(commit);
            if (BuildInfo.branch() != null) {
                build.append(" (").append(BuildInfo.branch()).append(')');
            }
            build.append('\n');
            if (BuildInfo.commitTime() != null) {
                build.append("Commit date: ").append(BuildInfo.commitTime()).append('\n');
            }
        }
        if (BuildInfo.buildTime() != null) {
            build.append("Built: ").append(BuildInfo.buildTime()).append('\n');
        }
        alert.setContentText("""
                Markdown editor with live preview, exporting to Word, HTML and PDF.

                Supports GitHub flavoured Markdown: tables, task lists, alerts, footnotes, \
                strikethrough, images, YAML front matter, as well as Mermaid, PlantUML and \
                Graphviz diagrams.

                %s
                JavaFX %s \u00b7 Java %s
                Settings: %s""".formatted(build.toString().strip(),
                System.getProperty("javafx.runtime.version", "?"), System.getProperty("java.version"),
                settings.store().description()));
        ButtonType copy = new ButtonType("Copy Version", ButtonBar.ButtonData.LEFT);
        alert.getButtonTypes().add(0, copy);
        if (alert.showAndWait().orElse(ButtonType.OK) == copy) {
            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.putString(APP_NAME + " " + BuildInfo.version()
                    + (BuildInfo.commitFull() != null ? " (" + BuildInfo.commitFull() + ")" : ""));
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
        }
    }

    private void error(String message, Throwable t) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(stage);
        alert.setTitle(APP_NAME);
        alert.setHeaderText(message);
        String detail = t == null ? "" : (t.getMessage() != null ? t.getMessage() : t.toString());
        alert.setContentText(detail);
        alert.showAndWait();
    }

    private static Image createIcon() {
        Canvas canvas = new Canvas(64, 64);
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setFill(Color.web("#0969da"));
        g.fillRoundRect(2, 2, 60, 60, 16, 16);
        g.setFill(Color.WHITE);
        g.setFont(Font.font("Segoe UI", FontWeight.BOLD, 26));
        g.fillText("M↓", 9, 42);
        WritableImage image = new WritableImage(64, 64);
        javafx.scene.SnapshotParameters params = new javafx.scene.SnapshotParameters();
        params.setFill(Color.TRANSPARENT);
        return canvas.snapshot(params, image);
    }
}
