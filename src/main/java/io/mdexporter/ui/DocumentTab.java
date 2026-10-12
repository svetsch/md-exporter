package io.mdexporter.ui;

import io.mdexporter.core.ExportOptions;
import io.mdexporter.core.MarkdownDocument;
import io.mdexporter.core.MarkdownParser;
import io.mdexporter.core.ProgressListener;
import io.mdexporter.core.ResourceLoader;
import io.mdexporter.diagram.DiagramService;
import io.mdexporter.editor.MarkdownEditing;
import io.mdexporter.editor.MarkdownTable;
import io.mdexporter.editor.TextEdit;
import io.mdexporter.export.ExportContext;
import io.mdexporter.export.HtmlBuilder;
import javafx.animation.PauseTransition;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.geometry.Bounds;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.control.skin.TextAreaSkin;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.util.Duration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;

/** One open document: a tab holding a Markdown editor and its (optional) live preview. */
final class DocumentTab {

    /** Services shared by all tabs, provided by the main window. */
    interface Host {
        DiagramService diagrams();

        ExecutorService previewExecutor();

        ExportOptions options();

        HostServices hostServices();

        /** Called when the name, dirty flag or text of the document changed. */
        void documentChanged(DocumentTab document);

        void showStatus(String message);

        /** Enter continues lists, Tab navigates tables and indents list items. */
        boolean smartEditing();

        /** Whether the preview follows the caret. */
        boolean syncPreview();

        /** Ctrl+mouse wheel in the editor: +1 / -1. */
        void zoom(int direction);
    }

    private static final String SYNC_SCRIPT = loadSyncScript();

    private final Host host;
    private final Tab tab = new Tab();
    private final TextArea editor = new TextArea();
    private final FindBar findBar = new FindBar(editor);
    private final WebView preview = new WebView();
    private final VBox previewBox;
    private final SplitPane split = new SplitPane();
    private final PauseTransition previewDelay = new PauseTransition(Duration.millis(350));
    private final PauseTransition syncDelay = new PauseTransition(Duration.millis(120));
    /** Throttles the preview while the editor scrolls (runs at most every 40 ms, not only once it stops). */
    private final PauseTransition scrollSync = new PauseTransition(Duration.millis(40));
    private final AtomicLong previewGeneration = new AtomicLong();
    private final String untitledName;
    private final boolean showcase;

    private Path file;
    private boolean dirty;
    private boolean loading;
    private boolean previewVisible = true;
    private boolean previewStale;
    private double divider;
    private String lastPreviewHtml = "";
    private double pendingScroll = -1;
    private Path previewFile;

    DocumentTab(Host host, String untitledName, boolean showcase, double divider, boolean wrap, Font font,
                boolean showPreview) {
        this.host = host;
        this.untitledName = untitledName;
        this.showcase = showcase;
        this.divider = divider;

        editor.setFont(font);
        editor.setWrapText(wrap);
        editor.setPromptText("Type or paste Markdown here, or drop .md files...");
        editor.textProperty().addListener((obs, old, text) -> {
            if (!loading && !dirty) {
                dirty = true;
                updateTabText();
            }
            if (!loading) {
                previewDelay.playFromStart();
            }
            host.documentChanged(this);
        });
        editor.caretPositionProperty().addListener((obs, old, pos) -> {
            if (previewVisible && host.syncPreview() && editor.isFocused()) {
                syncDelay.playFromStart();
            }
        });
        editor.scrollTopProperty().addListener((obs, old, top) -> {
            if (previewVisible && host.syncPreview() && !loading
                    && scrollSync.getStatus() != javafx.animation.Animation.Status.RUNNING) {
                scrollSync.playFromStart();
            }
        });
        editor.addEventFilter(KeyEvent.KEY_PRESSED, this::smartKeys);
        editor.addEventFilter(ScrollEvent.SCROLL, e -> {
            if (e.isShortcutDown() && e.getDeltaY() != 0) {
                host.zoom(e.getDeltaY() > 0 ? 1 : -1);
                e.consume();
            }
        });
        previewDelay.setOnFinished(e -> refreshPreview());
        syncDelay.setOnFinished(e -> syncPreview(true));
        scrollSync.setOnFinished(e -> syncPreview(false));

        preview.setContextMenuEnabled(false);
        WebEngine engine = preview.getEngine();
        engine.getLoadWorker().stateProperty().addListener((obs, old, state) -> {
            if (state == Worker.State.SUCCEEDED) {
                installSyncScript();
                if (host.syncPreview() && pendingScroll >= 0) {
                    syncPreview(true);
                } else if (pendingScroll > 0) {
                    engine.executeScript("window.scrollTo(0, " + pendingScroll + ")");
                }
                pendingScroll = -1;
            }
        });
        engine.locationProperty().addListener((obs, old, location) -> {
            if (location != null && location.matches("(?i)^(https?|mailto):.*")) {
                Platform.runLater(() -> {
                    host.hostServices().showDocument(location);
                    showPreview(lastPreviewHtml);
                });
            }
        });

        VBox editorBox = new VBox(header("Markdown"), findBar, editor);
        VBox.setVgrow(editor, Priority.ALWAYS);
        previewBox = new VBox(header("Preview"), preview);
        VBox.setVgrow(preview, Priority.ALWAYS);
        split.getItems().add(editorBox);
        setPreviewVisible(showPreview);

        tab.setContent(split);
        tab.setUserData(this);
        updateTabText();
    }

    private static Label header(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("pane-header");
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    private static String loadSyncScript() {
        try (InputStream in = DocumentTab.class.getResourceAsStream("/io/mdexporter/preview-sync.js")) {
            return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    // ------------------------------------------------------------------ state

    Tab tab() {
        return tab;
    }

    TextArea editor() {
        return editor;
    }

    FindBar findBar() {
        return findBar;
    }

    Path file() {
        return file;
    }

    boolean isDirty() {
        return dirty;
    }

    boolean isShowcase() {
        return showcase;
    }

    /** An untouched, empty, never saved document that can be replaced when opening a file. */
    boolean isPristine() {
        return file == null && !dirty && !showcase && editor.getText().isEmpty();
    }

    double dividerPosition() {
        if (previewVisible && split.getDividerPositions().length > 0) {
            divider = split.getDividerPositions()[0];
        }
        return divider;
    }

    String displayName() {
        if (file != null) {
            return file.getFileName().toString();
        }
        return showcase ? "Feature Showcase" : untitledName;
    }

    /** Name used for exported files. */
    String baseName() {
        if (file != null) {
            return MarkdownParser.baseName(file.getFileName().toString());
        }
        return showcase ? "showcase" : MarkdownParser.baseName(untitledName);
    }

    Path baseDir() {
        return file != null ? file.getParent() : Path.of(System.getProperty("user.home"));
    }

    /** Replaces the content (not marked as modified). */
    void load(Path newFile, String text) {
        file = newFile == null ? null : newFile.toAbsolutePath();
        loading = true;
        try {
            editor.setText(text);
        } finally {
            loading = false;
        }
        editor.positionCaret(0);
        editor.setScrollTop(0);
        dirty = false;
        updateTabText();
        host.documentChanged(this);
        previewDelay.stop();
        refreshPreview();
    }

    /** Marks the document as saved to {@code target}. */
    void saved(Path target) {
        boolean moved = file == null || !file.equals(target.toAbsolutePath());
        file = target.toAbsolutePath();
        dirty = false;
        updateTabText();
        host.documentChanged(this);
        if (moved) {
            refreshPreview(); // relative images resolve against the new folder
        }
    }

    void setWrapText(boolean wrap) {
        editor.setWrapText(wrap);
    }

    void setEditorFont(Font font) {
        editor.setFont(font);
    }

    boolean isPreviewVisible() {
        return previewVisible;
    }

    void setPreviewVisible(boolean visible) {
        if (visible == previewVisible && split.getItems().contains(previewBox) == visible) {
            return;
        }
        if (!visible) {
            dividerPosition();
            split.getItems().remove(previewBox);
        } else {
            if (!split.getItems().contains(previewBox)) {
                split.getItems().add(previewBox);
            }
            split.setDividerPositions(divider);
        }
        previewVisible = visible;
        if (visible && previewStale) {
            refreshPreview();
        }
    }

    MarkdownDocument parse() {
        return MarkdownParser.parse(editor.getText(), baseDir(), baseName());
    }

    private void updateTabText() {
        tab.setText((dirty ? "• " : "") + displayName());
        tab.setTooltip(new Tooltip(file != null ? file.toString()
                : showcase ? "Built-in document demonstrating every supported feature" : "Not saved yet"));
    }

    // ------------------------------------------------------------------ editing

    /** Applies an edit as one undoable change (only the part that really differs is replaced). */
    void apply(TextEdit edit) {
        String text = editor.getText();
        TextEdit inner = TextEdit.between(text.substring(edit.start(), edit.end()), edit.replacement());
        if (!inner.isEmpty()) {
            editor.replaceText(edit.start() + inner.start(), edit.start() + inner.end(), inner.replacement());
        }
        editor.selectRange(edit.anchor(), edit.caret());
        editor.requestFocus();
    }

    /** Replaces the whole text (keeps undo history, changes only the differing region). */
    void replaceAll(String newText) {
        TextEdit edit = TextEdit.between(editor.getText(), newText);
        if (!edit.isEmpty()) {
            apply(edit);
        }
    }

    private void smartKeys(KeyEvent e) {
        if (!host.smartEditing() || e.isShortcutDown() || e.isAltDown()) {
            return;
        }
        String text = editor.getText();
        int start = editor.getSelection().getStart();
        int end = editor.getSelection().getEnd();
        if (e.getCode() == KeyCode.ENTER && !e.isShiftDown()) {
            TextEdit edit = MarkdownEditing.continueList(text, start, end);
            if (edit != null) {
                apply(edit);
                e.consume();
            }
        } else if (e.getCode() == KeyCode.TAB) {
            if (moveInTable(!e.isShiftDown())) {
                e.consume();
                return;
            }
            boolean multiLine = editor.getSelectedText().indexOf('\n') >= 0;
            String line = text.substring(MarkdownEditing.lineStart(text, start), MarkdownEditing.lineEnd(text, start));
            boolean listItem = line.matches("\\s*([-*+]|\\d{1,9}[.)])( .*)?");
            if (e.isShiftDown()) {
                apply(MarkdownEditing.outdent(text, start, end));
                e.consume();
            } else if (multiLine || listItem && start == end) {
                apply(MarkdownEditing.indent(text, start, end));
                e.consume();
            }
        }
    }

    /** Tab / Shift+Tab in a table: formats it and selects the next / previous cell (adding a row at the end). */
    boolean moveInTable(boolean forward) {
        String text = editor.getText();
        MarkdownTable.Located t = MarkdownTable.find(text, editor.getCaretPosition());
        if (t == null) {
            return false;
        }
        MarkdownTable table = t.table();
        int row = t.caretRow();
        int col = t.caretCol();
        if (forward) {
            if (++col >= table.columnCount()) {
                col = 0;
                if (++row >= table.rows().size()) {
                    table.insertRow(row);
                }
            }
        } else if (--col < 0) {
            if (row == 0) {
                col = 0;
            } else {
                row--;
                col = table.columnCount() - 1;
            }
        }
        selectCell(t, row, col);
        return true;
    }

    /** Re-formats a (modified) table in place and selects the content of cell {@code row}/{@code col}. */
    void selectCell(MarkdownTable.Located t, int row, int col) {
        MarkdownTable.Formatted f = t.table().format();
        int r = Math.max(0, Math.min(row, t.table().rows().size() - 1));
        int c = Math.max(0, Math.min(col, t.table().columnCount() - 1));
        int cellStart = t.start() + f.cellStart(r, c);
        int length = t.table().rows().get(r).get(c).length();
        apply(new TextEdit(t.start(), t.end(), f.text(), cellStart, cellStart + length));
    }

    // ------------------------------------------------------------------ preview

    void refreshPreview() {
        if (!previewVisible) {
            previewStale = true;
            return;
        }
        previewStale = false;
        long generation = previewGeneration.incrementAndGet();
        MarkdownDocument document;
        try {
            document = parse();
        } catch (RuntimeException e) {
            host.showStatus("Preview failed: " + e.getMessage());
            return;
        }
        ExportOptions options = host.options().copy();
        DiagramService diagrams = host.diagrams();
        host.previewExecutor().submit(() -> {
            if (generation != previewGeneration.get()) {
                return; // a newer edit is already queued
            }
            try {
                ExportContext context = new ExportContext(diagrams, new ResourceLoader(document.baseDir()), options,
                        ProgressListener.NONE);
                String html = new HtmlBuilder(HtmlBuilder.Target.PREVIEW, context, null).build(document).toHtml();
                Platform.runLater(() -> {
                    if (generation == previewGeneration.get()) {
                        showPreview(html);
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> host.showStatus("Preview failed: " + e.getMessage()));
            }
        });
    }

    private void showPreview(String html) {
        WebEngine engine = preview.getEngine();
        try {
            Object y = engine.executeScript("window.scrollY || 0");
            pendingScroll = y instanceof Number n ? n.doubleValue() : -1;
        } catch (RuntimeException e) {
            pendingScroll = -1;
        }
        lastPreviewHtml = html;
        // Documents injected with loadContent() may not load file: images, so serve the preview from a file.
        try {
            if (previewFile == null) {
                Path dir = Files.createTempDirectory("md-exporter-preview");
                dir.toFile().deleteOnExit();
                previewFile = dir.resolve("preview.html");
                previewFile.toFile().deleteOnExit();
            }
            Files.writeString(previewFile, html, StandardCharsets.UTF_8);
            engine.load(previewFile.toUri() + "?v=" + previewGeneration.get());
        } catch (IOException e) {
            engine.loadContent(html);
        }
    }

    private void installSyncScript() {
        try {
            preview.getEngine().executeScript(SYNC_SCRIPT);
        } catch (RuntimeException ignored) {
            // page without script support
        }
    }

    /** Scrolls the preview to the caret (if visible), else to the editor's scroll position. */
    void syncPreviewToCaret() {
        syncPreview(true);
    }

    /**
     * Scrolls the preview so that it shows what the editor shows: the block of a source line appears at the same
     * height as that line in the editor. With {@code preferCaret} the caret line is used when it is visible,
     * otherwise the line in the middle of the editor; the first and last positions of the editor map to the top
     * and bottom of the preview.
     */
    void syncPreview(boolean preferCaret) {
        if (!previewVisible || preview.getEngine().getLoadWorker().getState() != Worker.State.SUCCEEDED
                || !(editor.getSkin() instanceof TextAreaSkin skin) || editor.getHeight() <= 0) {
            return;
        }
        double height = editor.getHeight();
        String text = editor.getText();
        int line;
        double fraction;
        Bounds caret = skin.getCaretBounds();
        if (preferCaret && caret != null && caret.getMinY() >= 0 && caret.getMaxY() <= height) {
            line = MarkdownEditing.lineIndex(text, editor.getCaretPosition());
            fraction = caret.getMinY() / height;
        } else {
            fraction = 0.5;
            line = MarkdownEditing.lineIndex(text,
                    skin.getIndex(8, editor.getScrollTop() + height * fraction).getInsertionIndex());
        }
        int edge = 0;
        if (editor.lookup(".scroll-pane") instanceof ScrollPane pane) {
            if (pane.getVvalue() <= pane.getVmin()) {
                edge = -1;
            } else if (pane.getVvalue() >= pane.getVmax()) {
                edge = 1;
            }
        }
        if (preferCaret && edge != 0 && caret != null && caret.getMinY() >= 0 && caret.getMaxY() <= height) {
            // at the very top / bottom both panes show their start / end
            edge = 0;
        }
        try {
            preview.getEngine().executeScript("window.mdxSyncToLine && window.mdxSyncToLine(" + line + ", "
                    + fraction + ", " + edge + ")");
        } catch (RuntimeException ignored) {
            // page still loading
        }
    }

    /** Releases resources once the tab is closed. */
    void dispose() {
        previewDelay.stop();
        syncDelay.stop();
        scrollSync.stop();
        previewGeneration.incrementAndGet();
        preview.getEngine().load(null);
        if (previewFile != null) {
            try {
                Files.deleteIfExists(previewFile);
                Files.deleteIfExists(previewFile.getParent());
            } catch (IOException ignored) {
                // deleted on exit at the latest
            }
        }
    }
}
