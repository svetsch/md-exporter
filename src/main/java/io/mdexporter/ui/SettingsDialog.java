package io.mdexporter.ui;

import io.mdexporter.ai.AiAgent;
import io.mdexporter.core.ExportOptions;
import io.mdexporter.settings.SettingsLocation;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.stage.Window;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Application settings: editor, export options, AI agents and where the settings are stored. */
final class SettingsDialog extends Dialog<ButtonType> {

    private static final List<String> MONOSPACED = List.of("Consolas", "Cascadia Mono", "Cascadia Code",
            "JetBrains Mono", "Fira Code", "Source Code Pro", "Courier New", "Lucida Console", "DejaVu Sans Mono",
            "Menlo", "Monaco", "Monospaced");

    private final AppSettings settings;
    private final ComboBox<String> fontFamily = new ComboBox<>();
    private final Spinner<Integer> fontSize = new Spinner<>(8, 40, 14);
    private final CheckBox wrap = new CheckBox("Wrap long lines");
    private final CheckBox smart = new CheckBox(
            "Smart editing: Enter continues lists, Tab moves between table cells and indents list items");
    private final CheckBox formatBar = new CheckBox("Show the formatting tool bar");
    private final CheckBox preview = new CheckBox("Show the live preview (Ctrl+Shift+V)");
    private final CheckBox sync = new CheckBox("Scroll the preview to the cursor position");
    private final OptionsPane exportOptions;
    private final CheckBox aiEnabled = new CheckBox("Enable AI features (Ask AI menu, tool bar button and "
            + "context menu)");
    private final ListView<AiAgent> agents = new ListView<>();
    private final Map<SettingsLocation, RadioButton> locations = new EnumMap<>(SettingsLocation.class);
    private String activeId;

    SettingsDialog(Window owner, AppSettings settings, ExportOptions options, int initialTab,
                   OptionsPane.Styles styles) {
        this.settings = settings;
        initOwner(owner);
        setTitle("Settings");
        setHeaderText(null);
        setResizable(true);
        exportOptions = new OptionsPane(options, styles);

        TabPane tabs = new TabPane(
                new Tab("Editor", editorTab()),
                new Tab("Export", padded(exportOptions)),
                new Tab("AI", aiTab()),
                new Tab("Storage", storageTab()));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getSelectionModel().select(initialTab);
        tabs.setPrefSize(840, 500);
        getDialogPane().setContent(tabs);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
    }

    static final int TAB_EDITOR = 0;
    static final int TAB_EXPORT = 1;
    static final int TAB_AI = 2;
    static final int TAB_STORAGE = 3;

    private static VBox padded(javafx.scene.Node node) {
        VBox box = new VBox(node);
        box.setPadding(new Insets(14));
        return box;
    }

    private static Label hint(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add("hint");
        return label;
    }

    // ------------------------------------------------------------------ editor

    private VBox editorTab() {
        fontFamily.getItems().setAll(MONOSPACED.stream().filter(f -> Font.getFamilies().contains(f)
                || f.equals("Monospaced")).toList());
        fontFamily.setEditable(true);
        fontFamily.setValue(settings.fontFamily());
        fontSize.setEditable(true);
        fontSize.getValueFactory().setValue((int) Math.round(settings.fontSize()));
        wrap.setSelected(settings.wrapText());
        smart.setSelected(settings.smartEditing());
        formatBar.setSelected(settings.showFormatBar());
        preview.setSelected(settings.showPreview());
        sync.setSelected(settings.syncPreview());
        sync.disableProperty().bind(preview.selectedProperty().not());

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.getColumnConstraints().addAll(new ColumnConstraints(110), new ColumnConstraints());
        grid.addRow(0, new Label("Font:"), fontFamily);
        grid.addRow(1, new Label("Font size:"), new HBox(8, fontSize, hint("Ctrl + mouse wheel zooms")));
        VBox box = new VBox(10, grid, wrap, smart, formatBar, preview, sync);
        box.setPadding(new Insets(14));
        return box;
    }

    // ------------------------------------------------------------------ AI

    private VBox aiTab() {
        aiEnabled.setSelected(settings.aiEnabled());
        List<AiAgent> list = settings.aiAgents();
        agents.getItems().setAll(list);
        AiAgent active = settings.activeAgent();
        activeId = active == null ? null : active.id();
        agents.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(AiAgent agent, boolean empty) {
                super.updateItem(agent, empty);
                if (empty || agent == null) {
                    setText(null);
                    setStyle("");
                } else {
                    boolean isActive = agent.id().equals(activeId);
                    String key = agent.apiKey().isEmpty()
                            ? agent.resolvedApiKey().isEmpty()
                            ? (agent.provider().requiresApiKey() ? "no API key" : "no key")
                            : "key from " + agent.provider().apiKeyVariable()
                            : "key stored";
                    setText((isActive ? "✔ " : "    ") + agent.name() + "  –  " + agent.model()
                            + "  (" + key + ")");
                    setTooltip(new javafx.scene.control.Tooltip(agent.provider().displayName() + "\n"
                            + agent.endpoint()));
                    setStyle(isActive ? "-fx-font-weight: bold;" : "");
                }
            }
        });
        agents.setPrefHeight(200);
        agents.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                editAgent();
            }
        });
        agents.disableProperty().bind(aiEnabled.selectedProperty().not());

        Button add = new Button("Add...");
        add.setOnAction(e -> AgentDialog.edit(getOwner(), null).ifPresent(a -> {
            agents.getItems().add(a);
            if (activeId == null) {
                activeId = a.id();
            }
            agents.getSelectionModel().select(a);
            agents.refresh();
        }));
        Button edit = new Button("Edit...");
        edit.setOnAction(e -> editAgent());
        Button duplicate = new Button("Duplicate");
        duplicate.setOnAction(e -> {
            AiAgent a = agents.getSelectionModel().getSelectedItem();
            if (a != null) {
                AiAgent copy = new AiAgent(null, a.name() + " (copy)", a.provider(), a.endpoint(), a.model(),
                        a.apiKey(), a.maxTokens(), a.effort(), a.fallback());
                agents.getItems().add(copy);
                agents.getSelectionModel().select(copy);
            }
        });
        Button remove = new Button("Remove");
        remove.setOnAction(e -> {
            AiAgent a = agents.getSelectionModel().getSelectedItem();
            if (a != null) {
                agents.getItems().remove(a);
                if (a.id().equals(activeId)) {
                    activeId = agents.getItems().isEmpty() ? null : agents.getItems().get(0).id();
                }
                agents.refresh();
            }
        });
        Button activate = new Button("Set Active");
        activate.setOnAction(e -> {
            AiAgent a = agents.getSelectionModel().getSelectedItem();
            if (a != null) {
                activeId = a.id();
                agents.refresh();
            }
        });
        for (Button b : List.of(edit, duplicate, remove, activate)) {
            b.disableProperty().bind(agents.getSelectionModel().selectedItemProperty().isNull()
                    .or(aiEnabled.selectedProperty().not()));
            b.setMaxWidth(Double.MAX_VALUE);
        }
        add.disableProperty().bind(aiEnabled.selectedProperty().not());
        add.setMaxWidth(Double.MAX_VALUE);
        VBox buttons = new VBox(6, add, edit, duplicate, remove, activate);
        buttons.setMinWidth(100);
        HBox row = new HBox(8, agents, buttons);
        HBox.setHgrow(agents, Priority.ALWAYS);

        VBox box = new VBox(10, aiEnabled, new Label("Agents (one is active at a time):"), row,
                hint("Supported: Anthropic Claude, OpenAI, Google Gemini and any OpenAI-compatible "
                        + "service such as Ollama, LM Studio, Mistral or OpenRouter. The selected text (and, if "
                        + "chosen, the whole document) is sent to the active agent's service when you ask the AI. "
                        + "API keys entered here are stored in plain text in the settings location; leave the key "
                        + "empty to use the provider's environment variable instead."));
        box.setPadding(new Insets(14));
        return box;
    }

    private void editAgent() {
        AiAgent a = agents.getSelectionModel().getSelectedItem();
        if (a == null) {
            return;
        }
        AgentDialog.edit(getOwner(), a).ifPresent(updated -> {
            int index = agents.getItems().indexOf(a);
            agents.getItems().set(index, updated);
            agents.getSelectionModel().select(index);
        });
    }

    // ------------------------------------------------------------------ storage

    private VBox storageTab() {
        ToggleGroup group = new ToggleGroup();
        VBox options = new VBox(10);
        SettingsLocation current = settings.store().location();
        for (SettingsLocation location : SettingsLocation.values()) {
            if (location == SettingsLocation.CUSTOM_FILE && current != SettingsLocation.CUSTOM_FILE) {
                continue;
            }
            RadioButton radio = new RadioButton(location.displayName());
            radio.setToggleGroup(group);
            radio.setSelected(location == current);
            boolean available = location.isAvailable();
            radio.setDisable(!available || current == SettingsLocation.CUSTOM_FILE);
            Path file = location.file();
            String where = location == SettingsLocation.SYSTEM
                    ? (location == current ? settings.store().description()
                    : "Windows: registry key HKEY_CURRENT_USER\\Software\\JavaSoft\\Prefs\\io\\mdexporter\\ui; "
                    + "other systems: Java preferences")
                    : file == null ? "Not available when running from the development build"
                    : file + (available ? "" : " (folder not writable)");
            locations.put(location, radio);
            options.getChildren().add(new VBox(2, radio, indent(hint(where))));
        }
        Label currentLabel = new Label("Settings are currently stored in: " + settings.store().description());
        currentLabel.setWrapText(true);
        VBox box = new VBox(12, currentLabel, options, hint("""
                When you choose another location, all settings (including AI agents and their API keys) are \
                moved there and removed from the old location. At start-up the application looks for a \
                portable settings file next to the application first, then for the file in the user folder, \
                and falls back to the system preferences. The start option -Dmdx.settings=<file> forces a \
                specific file."""));
        box.setPadding(new Insets(14));
        return box;
    }

    private static HBox indent(Label label) {
        HBox box = new HBox(label);
        box.setPadding(new Insets(0, 0, 0, 24));
        return box;
    }

    // ------------------------------------------------------------------ result

    /** Saves the settings (call when the dialog was confirmed); returns the export options. */
    ExportOptions apply() {
        InsertDialogs.commit(fontSize);
        String family = fontFamily.getEditor().getText();
        settings.fontFamily(family == null || family.isBlank() ? "Consolas" : family.trim());
        settings.fontSize(fontSize.getValue());
        settings.wrapText(wrap.isSelected());
        settings.smartEditing(smart.isSelected());
        settings.showFormatBar(formatBar.isSelected());
        settings.showPreview(preview.isSelected());
        settings.syncPreview(sync.isSelected());
        ExportOptions options = exportOptions.toOptions();
        settings.saveOptions(options);
        settings.aiEnabled(aiEnabled.isSelected());
        settings.aiAgents(new ArrayList<>(agents.getItems()));
        settings.activeAgentId(activeId == null ? "" : activeId);

        SettingsLocation target = locations.entrySet().stream().filter(e -> e.getValue().isSelected())
                .map(Map.Entry::getKey).findFirst().orElse(settings.store().location());
        if (target != settings.store().location()) {
            try {
                settings.moveTo(target);
            } catch (RuntimeException e) {
                Alert alert = new Alert(Alert.AlertType.ERROR, AgentDialog.message(e));
                alert.initOwner(getOwner());
                alert.setHeaderText("Cannot move the settings to " + target.displayName());
                alert.showAndWait();
            }
        }
        return options;
    }

    static Optional<ExportOptions> show(Window owner, AppSettings settings, ExportOptions options, int tab,
                                        OptionsPane.Styles styles) {
        SettingsDialog dialog = new SettingsDialog(owner, settings, options, tab, styles);
        return dialog.showAndWait().filter(b -> b == ButtonType.OK).map(b -> dialog.apply());
    }
}
