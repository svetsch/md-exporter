package io.mdexporter.ui;

import io.mdexporter.ai.AiAgent;
import io.mdexporter.ai.AiClient;
import io.mdexporter.ai.AiException;
import io.mdexporter.ai.AiPrompts;
import io.mdexporter.editor.TextDiff;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Window;
import javafx.util.Duration;

import java.util.List;
import java.util.function.Consumer;

/**
 * Asks the active AI agent to change the selection or the whole document. The answer streams into an editable
 * suggestion, can be reviewed as a word level diff and is then applied (replace or insert below).
 */
final class AiAssistDialog extends Dialog<AiAssistDialog.Outcome> {

    enum Action { REPLACE, INSERT_BELOW }

    /**
     * What to do with the answer.
     *
     * @param action        replace the target or insert below it
     * @param text          the (possibly edited) suggestion
     * @param wholeDocument whether the target is the whole document rather than the selection
     */
    record Outcome(Action action, String text, boolean wholeDocument) {
    }

    private final AppSettings settings;
    private final String document;
    private final String selection;
    private final ComboBox<AiAgent> agents = new ComboBox<>();
    private final RadioButton scopeSelection = new RadioButton();
    private final RadioButton scopeDocument = new RadioButton("Whole document");
    private final CheckBox context = new CheckBox("Send the whole document as context");
    private final ComboBox<AiPrompts.Preset> presets = new ComboBox<>();
    private final TextArea instruction = new TextArea();
    private final TextArea original = new TextArea();
    private final TextArea suggestion = new TextArea();
    private final TextFlow changes = new TextFlow();
    private final TabPane results = new TabPane();
    private final Tab changesTab = new Tab("Changes");
    private final Label status = new Label();
    private final ProgressIndicator spinner = new ProgressIndicator();
    private final Button send = new Button("Send");
    private final Button stop = new Button("Stop");
    private final ButtonType replaceType = new ButtonType("Replace", ButtonBar.ButtonData.OK_DONE);
    private final ButtonType insertType = new ButtonType("Insert Below", ButtonBar.ButtonData.OTHER);
    private final ButtonType copyType = new ButtonType("Copy", ButtonBar.ButtonData.LEFT);

    private final Label reviewSummary = new Label();
    /** Changes between the original and the suggestion; each one can be rejected. */
    private List<Hunk> hunks;
    private boolean internalUpdate;

    private AiClient.Cancellation cancellation;
    private Timeline clock;
    private boolean generates;
    private boolean running;

    AiAssistDialog(Window owner, AppSettings settings, String document, String selection, Font editorFont,
                   AiPrompts.Preset preset) {
        this.settings = settings;
        this.document = document;
        this.selection = selection == null || selection.isEmpty() ? null : selection;
        initOwner(owner);
        setTitle("Ask AI");
        setHeaderText(null);
        setResizable(true);

        List<AiAgent> list = settings.aiAgents();
        agents.getItems().setAll(list);
        agents.setValue(settings.activeAgent());
        agents.setTooltip(new Tooltip("AI agent (configure them in Settings > AI)"));
        agents.valueProperty().addListener((o, a, v) -> {
            if (v != null) {
                settings.activeAgentId(v.id());
            }
        });

        ToggleGroup scope = new ToggleGroup();
        scopeSelection.setToggleGroup(scope);
        scopeDocument.setToggleGroup(scope);
        if (this.selection != null) {
            scopeSelection.setText("Selection (" + this.selection.length() + " characters)");
            scopeSelection.setSelected(true);
        } else {
            scopeSelection.setText("Selection (none)");
            scopeSelection.setDisable(true);
            scopeDocument.setSelected(true);
        }
        context.setSelected(settings.aiSendContext());
        context.disableProperty().bind(scopeDocument.selectedProperty());
        context.setTooltip(new Tooltip("Gives the model the surrounding text; only the selection is changed"));
        scope.selectedToggleProperty().addListener((o, a, v) -> updateOriginal());

        presets.getItems().setAll(AiPrompts.PRESETS);
        presets.setPromptText("Choose a ready-made instruction...");
        presets.valueProperty().addListener((o, a, p) -> {
            if (p != null) {
                instruction.setText(p.instruction());
                generates = p.generates();
            }
        });
        instruction.setPromptText("What should the AI do? e.g. \"Rewrite this as a bullet list\" "
                + "(Ctrl+Enter to send)");
        instruction.setWrapText(true);
        instruction.setPrefRowCount(3);
        instruction.setText(preset != null ? preset.instruction() : settings.aiLastInstruction());
        instruction.textProperty().addListener((o, a, v) -> {
            AiPrompts.Preset p = presets.getValue();
            if (p != null && !p.instruction().equals(v)) {
                generates = false;
            }
        });
        instruction.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER && e.isShortcutDown()) {
                send();
                e.consume();
            }
        });
        if (preset != null) {
            presets.setValue(preset);
        }

        for (TextArea area : List.of(original, suggestion)) {
            area.setWrapText(true);
            area.setFont(editorFont);
        }
        original.setEditable(false);
        suggestion.setPromptText("The suggestion appears here; you can edit it before applying it.");
        updateOriginal();
        ScrollPane changesScroll = new ScrollPane(changes);
        changesScroll.setFitToWidth(true);
        changes.setPadding(new Insets(8));
        changes.setLineSpacing(2);
        Button acceptAll = new Button("Accept All");
        acceptAll.setOnAction(e -> setAllRejected(false));
        Button rejectAll = new Button("Reject All");
        rejectAll.setOnAction(e -> setAllRejected(true));
        Label reviewHint = new Label("Click a change to reject it (or to accept it again). Only accepted changes "
                + "are applied.");
        reviewHint.getStyleClass().add("hint");
        HBox reviewBar = new HBox(8, acceptAll, rejectAll, reviewSummary, reviewHint);
        reviewBar.setAlignment(Pos.CENTER_LEFT);
        reviewBar.setPadding(new Insets(4, 6, 4, 6));
        VBox.setVgrow(changesScroll, Priority.ALWAYS);
        changesTab.setContent(new VBox(reviewBar, changesScroll));
        suggestion.textProperty().addListener((o, a, v) -> {
            if (!internalUpdate) {
                hunks = null; // edited by hand: review against the new text
            }
        });
        Tab suggestionTab = new Tab("Suggestion", suggestion);
        results.getTabs().addAll(suggestionTab, changesTab, new Tab("Original", original));
        results.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        results.getSelectionModel().selectedItemProperty().addListener((o, a, t) -> {
            if (t == changesTab) {
                renderChanges();
            }
        });
        VBox.setVgrow(results, Priority.ALWAYS);

        send.setDefaultButton(false);
        send.getStyleClass().add("accent");
        send.setOnAction(e -> send());
        stop.setOnAction(e -> cancel());
        stop.setDisable(true);
        spinner.setPrefSize(18, 18);
        spinner.setVisible(false);
        status.setWrapText(true);
        status.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(status, Priority.ALWAYS);

        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox top = new HBox(10, new Label("Agent:"), agents, grow, scopeSelection, scopeDocument);
        top.setAlignment(Pos.CENTER_LEFT);
        HBox presetRow = new HBox(10, presets, context);
        presetRow.setAlignment(Pos.CENTER_LEFT);
        HBox actions = new HBox(8, send, stop, spinner, status);
        actions.setAlignment(Pos.CENTER_LEFT);
        Label privacy = new Label("The text is sent to the selected AI service. Review the suggestion before "
                + "applying it; Ctrl+Z undoes the change.");
        privacy.getStyleClass().add("hint");
        privacy.setWrapText(true);
        VBox content = new VBox(8, top, presetRow, instruction, actions, results, privacy);
        content.setPadding(new Insets(10));
        content.setPrefSize(900, 640);
        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().addAll(copyType, insertType, replaceType, ButtonType.CLOSE);

        Button copyButton = (Button) getDialogPane().lookupButton(copyType);
        copyButton.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            ClipboardContent clip = new ClipboardContent();
            clip.putString(suggestion.getText());
            Clipboard.getSystemClipboard().setContent(clip);
            status.setText("Copied to the clipboard");
            e.consume(); // keep the dialog open
        });
        updateButtons();
        suggestion.textProperty().addListener((o, a, v) -> updateButtons());

        setResultConverter(b -> {
            cancel();
            if (b == replaceType || b == insertType) {
                settings.aiSendContext(context.isSelected());
                String text = AiPrompts.clean(suggestion.getText(), target());
                return new Outcome(b == replaceType ? Action.REPLACE : Action.INSERT_BELOW, text,
                        scopeDocument.isSelected());
            }
            return null;
        });
        setOnShown(e -> {
            if (preset != null) {
                send();
            } else {
                instruction.requestFocus();
                instruction.selectAll();
            }
        });
    }

    private String target() {
        return scopeDocument.isSelected() ? document : selection;
    }

    private void updateOriginal() {
        original.setText(target() == null ? "" : target());
    }

    private void updateButtons() {
        boolean empty = suggestion.getText().isBlank();
        getDialogPane().lookupButton(replaceType).setDisable(running || empty);
        getDialogPane().lookupButton(insertType).setDisable(running || empty);
        getDialogPane().lookupButton(copyType).setDisable(empty);
        Button replace = (Button) getDialogPane().lookupButton(replaceType);
        Button insert = (Button) getDialogPane().lookupButton(insertType);
        // generated text (summary, continuation) is inserted by default
        replace.setDefaultButton(!generates);
        insert.setDefaultButton(generates);
    }

    private void send() {
        if (running) {
            return;
        }
        AiAgent agent = agents.getValue();
        if (agent == null) {
            status.setText("No AI agent configured: add one in Settings > AI.");
            return;
        }
        String text = instruction.getText();
        if (text.isBlank()) {
            status.setText("Enter an instruction first.");
            instruction.requestFocus();
            return;
        }
        if (presets.getValue() == null || !presets.getValue().instruction().equals(text)) {
            settings.aiLastInstruction(text);
        }
        settings.aiSendContext(context.isSelected());
        boolean wholeDocument = scopeDocument.isSelected();
        String user = AiPrompts.user(document, wholeDocument ? null : selection, context.isSelected(), text,
                generates);
        AiClient.AiRequest request = new AiClient.AiRequest(AiPrompts.system(), user, agent.maxTokens());
        AiClient client = agent.provider().createClient(agent);

        suggestion.clear();
        changes.getChildren().clear();
        results.getSelectionModel().select(0);
        setRunning(true);
        long started = System.currentTimeMillis();
        status.setText("Waiting for " + agent.name() + " (" + agent.model() + ")...");
        clock = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            long seconds = (System.currentTimeMillis() - started) / 1000;
            status.setText((suggestion.getLength() == 0 ? "Waiting for " : "Receiving from ") + agent.name()
                    + " (" + agent.model() + ")... " + seconds + " s");
        }));
        clock.setCycleCount(Timeline.INDEFINITE);
        clock.play();

        AiClient.Cancellation cancel = new AiClient.Cancellation();
        cancellation = cancel;
        Consumer<String> sink = new Batcher(suggestion);
        Thread.ofVirtual().name("md-exporter-ai").start(() -> {
            try {
                client.stream(request, sink, cancel);
                ((Batcher) sink).flushNow();
                Platform.runLater(() -> finished(cancel, null, started));
            } catch (AiException e) {
                ((Batcher) sink).flushNow();
                Platform.runLater(() -> finished(cancel, e.getMessage(), started));
            } catch (RuntimeException e) {
                Platform.runLater(() -> finished(cancel, AgentDialog.message(e), started));
            }
        });
    }

    private void finished(AiClient.Cancellation cancel, String error, long started) {
        if (cancel != cancellation) {
            return; // a newer request runs
        }
        setRunning(false);
        long seconds = Math.max(1, (System.currentTimeMillis() - started) / 1000);
        if (cancel.isCancelled()) {
            status.setText("Stopped.");
        } else if (error != null) {
            status.setText("✖ " + error);
        } else {
            String cleaned = AiPrompts.clean(suggestion.getText(), target());
            if (!cleaned.equals(suggestion.getText())) {
                suggestion.setText(cleaned);
            }
            int changed = generates ? suggestion.getLength()
                    : TextDiff.changedCharacters(TextDiff.diff(original.getText(), suggestion.getText()));
            status.setText("Done in " + seconds + " s" + (generates ? ". Review and edit the suggestion, then "
                    + "insert it." : " – " + changed + " characters changed. Review the changes, then apply "
                    + "them."));
            hunks = null;
            if (!generates) {
                if (results.getSelectionModel().getSelectedItem() == changesTab) {
                    renderChanges();
                } else {
                    results.getSelectionModel().select(changesTab);
                }
            }
        }
    }

    private void setRunning(boolean value) {
        running = value;
        send.setDisable(value);
        stop.setDisable(!value);
        spinner.setVisible(value);
        agents.setDisable(value);
        if (!value && clock != null) {
            clock.stop();
        }
        updateButtons();
    }

    private void cancel() {
        if (cancellation != null && running) {
            cancellation.cancel();
        }
    }

    /** A run of unchanged text ({@code deleted == inserted}) or one change. */
    private static final class Hunk {
        final String deleted;
        final String inserted;
        final boolean change;
        boolean rejected;

        Hunk(String deleted, String inserted, boolean change) {
            this.deleted = deleted;
            this.inserted = inserted;
            this.change = change;
        }

        String result() {
            return change && rejected ? deleted : inserted;
        }
    }

    private static List<Hunk> hunks(String before, String after) {
        List<Hunk> list = new java.util.ArrayList<>();
        StringBuilder del = new StringBuilder();
        StringBuilder ins = new StringBuilder();
        for (TextDiff.Op op : TextDiff.diff(before, after)) {
            if (op.type() == TextDiff.Type.EQUAL) {
                if (!del.isEmpty() || !ins.isEmpty()) {
                    list.add(new Hunk(del.toString(), ins.toString(), true));
                    del.setLength(0);
                    ins.setLength(0);
                }
                list.add(new Hunk(op.text(), op.text(), false));
            } else if (op.type() == TextDiff.Type.DELETE) {
                del.append(op.text());
            } else {
                ins.append(op.text());
            }
        }
        if (!del.isEmpty() || !ins.isEmpty()) {
            list.add(new Hunk(del.toString(), ins.toString(), true));
        }
        return list;
    }

    private void setAllRejected(boolean rejected) {
        if (hunks == null) {
            renderChanges();
        }
        hunks.forEach(h -> h.rejected = rejected);
        mergeAndRender();
    }

    /** Applies the accept / reject choices to the suggestion and redraws the review. */
    private void mergeAndRender() {
        StringBuilder merged = new StringBuilder();
        hunks.forEach(h -> merged.append(h.result()));
        internalUpdate = true;
        try {
            suggestion.setText(merged.toString());
        } finally {
            internalUpdate = false;
        }
        drawHunks();
    }

    /**
     * Word level review of original and suggestion: deletions struck through in red, insertions in green; a click
     * on a change rejects it (shown greyed) or accepts it again.
     */
    private void renderChanges() {
        if (hunks == null) {
            hunks = hunks(original.getText(), suggestion.getText());
        }
        drawHunks();
    }

    private void drawHunks() {
        changes.getChildren().clear();
        int total = 0;
        int accepted = 0;
        for (Hunk h : hunks) {
            if (!h.change) {
                changes.getChildren().add(text(h.inserted, "diff-equal", false));
                continue;
            }
            total++;
            if (!h.rejected) {
                accepted++;
            }
            List<Text> parts = new java.util.ArrayList<>();
            if (!h.deleted.isEmpty()) {
                parts.add(text(h.deleted, h.rejected ? "diff-kept" : "diff-delete", !h.rejected));
            }
            if (!h.inserted.isEmpty()) {
                parts.add(text(h.inserted, h.rejected ? "diff-rejected" : "diff-insert", h.rejected));
            }
            for (Text t : parts) {
                t.setCursor(javafx.scene.Cursor.HAND);
                Tooltip.install(t, new Tooltip(h.rejected ? "Rejected: click to accept this change"
                        : "Click to reject this change"));
                t.setOnMouseClicked(e -> {
                    h.rejected = !h.rejected;
                    mergeAndRender();
                });
                changes.getChildren().add(t);
            }
        }
        if (total == 0) {
            changes.getChildren().add(new Text(suggestion.getText().isEmpty() ? "No suggestion yet." : "No changes."));
        }
        reviewSummary.setText(total == 0 ? "" : accepted + " of " + total + " changes accepted");
    }

    private Text text(String content, String style, boolean strike) {
        Text t = new Text(content);
        t.setFont(suggestion.getFont());
        t.getStyleClass().add(style);
        t.setStrikethrough(strike);
        return t;
    }

    /** Collects streamed text and appends it on the FX thread at most once per pulse. */
    private static final class Batcher implements Consumer<String> {
        private final TextArea target;
        private final StringBuilder pending = new StringBuilder();
        private boolean scheduled;

        Batcher(TextArea target) {
            this.target = target;
        }

        @Override
        public synchronized void accept(String text) {
            pending.append(text);
            if (!scheduled) {
                scheduled = true;
                Platform.runLater(this::flush);
            }
        }

        private void flush() {
            String text;
            synchronized (this) {
                text = pending.toString();
                pending.setLength(0);
                scheduled = false;
            }
            if (!text.isEmpty()) {
                target.appendText(text);
            }
        }

        void flushNow() {
            Platform.runLater(this::flush);
        }
    }
}
