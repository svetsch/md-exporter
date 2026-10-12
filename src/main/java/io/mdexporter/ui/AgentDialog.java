package io.mdexporter.ui;

import io.mdexporter.ai.AiAgent;
import io.mdexporter.ai.AiClient;
import io.mdexporter.ai.AiException;
import io.mdexporter.ai.AiProvider;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.stage.Window;

import java.util.List;
import java.util.Optional;

/** Creates or edits one AI agent (provider, endpoint, model, API key...). */
final class AgentDialog extends Dialog<AiAgent> {

    private static final List<String> EFFORTS = List.of("", "low", "medium", "high", "xhigh", "max");

    private final ComboBox<AiProvider> provider = new ComboBox<>();
    private final TextField name = new TextField();
    private final TextField endpoint = new TextField();
    private final ComboBox<String> model = new ComboBox<>();
    private final PasswordField apiKey = new PasswordField();
    private final TextField apiKeyVisible = new TextField();
    private final Label keyHint = new Label();
    private final Spinner<Integer> maxTokens = new Spinner<>(256, 128_000, 8192, 1024);
    private final ComboBox<String> effort = new ComboBox<>();
    private final CheckBox fallback = new CheckBox("Re-run declined requests on a fallback model (server-side)");
    private final Label status = new Label();
    private final String id;

    AgentDialog(Window owner, AiAgent agent) {
        initOwner(owner);
        setTitle(agent == null ? "Add AI Agent" : "Edit AI Agent");
        setHeaderText("AI service used by the \"Ask AI\" commands");
        setResizable(true);
        AiAgent a = agent != null ? agent : AiAgent.defaults(AiProvider.ANTHROPIC);
        id = a.id();

        provider.getItems().setAll(AiProvider.values());
        provider.setValue(a.provider());
        name.setText(a.name());
        endpoint.setText(a.endpoint());
        model.setEditable(true);
        model.setMaxWidth(Double.MAX_VALUE);
        model.setValue(a.model());
        apiKey.setText(a.apiKey());
        apiKeyVisible.textProperty().bindBidirectional(apiKey.textProperty());
        apiKeyVisible.setVisible(false);
        maxTokens.setEditable(true);
        maxTokens.getValueFactory().setValue(a.maxTokens());
        effort.getItems().setAll(EFFORTS);
        effort.setValue(a.effort());
        effort.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(String s) {
                return s == null || s.isEmpty() ? "model default" : s;
            }

            @Override
            public String fromString(String s) {
                return "model default".equals(s) ? "" : s;
            }
        });
        fallback.setSelected(a.fallback());
        fallback.setTooltip(new Tooltip("""
                Claude may decline a request with a "refusal" (rare for writing tasks). With this option the API \
                retries it on Anthropic's recommended fallback model instead. Uses the server-side-fallback beta."""));
        keyHint.getStyleClass().add("hint");
        keyHint.setWrapText(true);
        status.setWrapText(true);

        ToggleButton show = new ToggleButton("Show");
        show.setFocusTraversable(false);
        show.selectedProperty().addListener((o, was, v) -> {
            apiKey.setVisible(!v);
            apiKeyVisible.setVisible(v);
        });
        StackPane keyStack = new StackPane(apiKey, apiKeyVisible);
        HBox keyRow = new HBox(6, keyStack, show);
        HBox.setHgrow(keyStack, Priority.ALWAYS);

        Button loadModels = new Button("Load Models");
        loadModels.setTooltip(new Tooltip("Ask the service which models are available"));
        loadModels.setOnAction(e -> loadModels(loadModels));
        HBox modelRow = new HBox(6, model, loadModels);
        HBox.setHgrow(model, Priority.ALWAYS);

        Button test = new Button("Test Connection");
        test.setOnAction(e -> test(test));

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(10));
        ColumnConstraints labels = new ColumnConstraints();
        labels.setMinWidth(100);
        ColumnConstraints fields = new ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        fields.setPrefWidth(460);
        grid.getColumnConstraints().addAll(labels, fields);
        int row = 0;
        grid.addRow(row++, new Label("Provider:"), provider);
        grid.addRow(row++, new Label("Name:"), name);
        grid.addRow(row++, new Label("Endpoint:"), endpoint);
        grid.addRow(row++, new Label("Model:"), modelRow);
        grid.addRow(row++, new Label("API key:"), keyRow);
        grid.add(keyHint, 1, row++);
        grid.addRow(row++, new Label("Max. tokens:"), maxTokens);
        Label effortLabel = new Label("Effort:");
        grid.addRow(row++, effortLabel, effort);
        grid.add(fallback, 1, row++);
        grid.add(new HBox(10, test, status), 1, row);
        getDialogPane().setContent(grid);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        provider.valueProperty().addListener((o, old, p) -> {
            // switch defaults that were not customised
            if (old != null && (endpoint.getText().isBlank() || endpoint.getText().equals(old.defaultEndpoint()))) {
                endpoint.setText(p.defaultEndpoint());
            }
            if (old != null && (model.getValue() == null || model.getValue().isBlank()
                    || model.getValue().equals(old.defaultModel()))) {
                model.getItems().clear();
                model.setValue(p.defaultModel());
            }
            if (old != null && (name.getText().isBlank() || name.getText().equals(old.displayName()))) {
                name.setText(p.displayName());
            }
            if (old != null && maxTokens.getValue() == old.defaultMaxTokens()) {
                maxTokens.getValueFactory().setValue(p.defaultMaxTokens());
            }
            updateProvider(effortLabel);
        });
        updateProvider(effortLabel);

        setResultConverter(b -> b == ButtonType.OK ? toAgent() : null);
    }

    private void updateProvider(Label effortLabel) {
        AiProvider p = provider.getValue();
        boolean claude = p == AiProvider.ANTHROPIC;
        effort.setVisible(claude);
        effortLabel.setVisible(claude);
        fallback.setVisible(claude);
        String variable = p.apiKeyVariable();
        keyHint.setText(!p.requiresApiKey()
                ? "Optional: local servers (Ollama, LM Studio) need none."
                : "Leave empty to use the " + variable + " environment variable. A key entered here is stored in "
                + "plain text in the settings location (see the Storage tab).");
    }

    AiAgent toAgent() {
        InsertDialogs.commit(maxTokens);
        return new AiAgent(id, name.getText(), provider.getValue(), endpoint.getText(),
                model.getEditor().getText(), apiKey.getText(), maxTokens.getValue(),
                effort.getValue() == null ? "" : effort.getValue(), fallback.isSelected());
    }

    private void loadModels(Button button) {
        AiAgent agent = toAgent();
        button.setDisable(true);
        status.setText("Loading models...");
        Task<List<String>> task = new Task<>() {
            @Override
            protected List<String> call() throws AiException {
                return agent.provider().createClient(agent).listModels();
            }
        };
        task.setOnSucceeded(e -> {
            button.setDisable(false);
            String current = model.getEditor().getText();
            model.getItems().setAll(task.getValue());
            model.setValue(current);
            status.setText(task.getValue().size() + " models available");
            Platform.runLater(model::show);
        });
        task.setOnFailed(e -> {
            button.setDisable(false);
            status.setText(message(task.getException()));
        });
        Thread.ofVirtual().start(task);
    }

    private void test(Button button) {
        AiAgent agent = toAgent();
        button.setDisable(true);
        status.setText("Sending a test request to " + agent.model() + "...");
        Task<String> task = new Task<>() {
            @Override
            protected String call() throws AiException {
                StringBuilder answer = new StringBuilder();
                agent.provider().createClient(agent).stream(new AiClient.AiRequest(
                                "You are a connectivity check.", "Reply with the single word OK.", 256),
                        answer::append, new AiClient.Cancellation());
                return answer.toString().strip();
            }
        };
        task.setOnSucceeded(e -> {
            button.setDisable(false);
            String answer = task.getValue();
            status.setText("✔ Connected. Answer: " + (answer.length() > 60 ? answer.substring(0, 60) + "..."
                    : answer.isEmpty() ? "(empty)" : answer));
        });
        task.setOnFailed(e -> {
            button.setDisable(false);
            status.setText("✖ " + message(task.getException()));
        });
        Thread.ofVirtual().start(task);
    }

    static String message(Throwable t) {
        if (t == null) {
            return "Unknown error";
        }
        return t.getMessage() != null ? t.getMessage() : t.toString();
    }

    static Optional<AiAgent> edit(Window owner, AiAgent agent) {
        return new AgentDialog(owner, agent).showAndWait();
    }
}
