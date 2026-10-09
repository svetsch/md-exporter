package io.mdexporter.ui;

import io.mdexporter.core.ExportOptions;
import io.mdexporter.core.MarkdownDocument;
import io.mdexporter.core.MarkdownParser;
import io.mdexporter.core.ProgressListener;
import io.mdexporter.core.ResourceLoader;
import io.mdexporter.diagram.DiagramService;
import io.mdexporter.export.ExportContext;
import io.mdexporter.export.HtmlBuilder;
import javafx.animation.PauseTransition;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.util.Duration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;

/** One open document: a tab holding a Markdown editor and its live preview. */
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
    }

    private final Host host;
    private final Tab tab = new Tab();
    private final TextArea editor = new TextArea();
    private final WebView preview = new WebView();
    private final SplitPane split = new SplitPane();
    private final PauseTransition previewDelay = new PauseTransition(Duration.millis(350));
    private final AtomicLong previewGeneration = new AtomicLong();
    private final String untitledName;
    private final boolean showcase;

    private Path file;
    private boolean dirty;
    private boolean loading;
    private String lastPreviewHtml = "";
    private double pendingScroll = -1;
    private Path previewFile;

    DocumentTab(Host host, String untitledName, boolean showcase, double divider, boolean wrap) {
        this.host = host;
        this.untitledName = untitledName;
        this.showcase = showcase;

        editor.setFont(Font.font("Consolas", 14));
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
        previewDelay.setOnFinished(e -> refreshPreview());

        preview.setContextMenuEnabled(false);
        WebEngine engine = preview.getEngine();
        engine.getLoadWorker().stateProperty().addListener((obs, old, state) -> {
            if (state == Worker.State.SUCCEEDED && pendingScroll > 0) {
                engine.executeScript("window.scrollTo(0, " + pendingScroll + ")");
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

        VBox editorBox = new VBox(header("Markdown"), editor);
        VBox.setVgrow(editor, Priority.ALWAYS);
        VBox previewBox = new VBox(header("Preview"), preview);
        VBox.setVgrow(preview, Priority.ALWAYS);
        split.getItems().addAll(editorBox, previewBox);
        split.setDividerPositions(divider);

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

    // ------------------------------------------------------------------ state

    Tab tab() {
        return tab;
    }

    TextArea editor() {
        return editor;
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
        return split.getDividerPositions().length > 0 ? split.getDividerPositions()[0] : 0.5;
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

    MarkdownDocument parse() {
        return MarkdownParser.parse(editor.getText(), baseDir(), baseName());
    }

    private void updateTabText() {
        tab.setText((dirty ? "• " : "") + displayName());
        tab.setTooltip(new Tooltip(file != null ? file.toString()
                : showcase ? "Built-in document demonstrating every supported feature" : "Not saved yet"));
    }

    // ------------------------------------------------------------------ preview

    void refreshPreview() {
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

    /** Releases resources once the tab is closed. */
    void dispose() {
        previewDelay.stop();
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
