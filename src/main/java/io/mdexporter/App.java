package io.mdexporter;

import io.mdexporter.ui.MainWindow;
import javafx.application.Application;
import javafx.stage.Stage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** JavaFX application: Markdown editor with live preview and export to Word, HTML and PDF. */
public final class App extends Application {

    private MainWindow window;

    @Override
    public void start(Stage stage) {
        Launcher.configureLogging();
        window = new MainWindow(stage, getHostServices());
        List<Path> files = getParameters().getRaw().stream()
                .map(Path::of)
                .filter(Files::isRegularFile)
                .toList();
        window.show(files); // one tab per file, or the feature showcase when none

    }

    @Override
    public void stop() {
        if (window != null) {
            window.shutdown();
        }
    }

    public static void main(String[] args) {
        launch(App.class, args);
    }
}
