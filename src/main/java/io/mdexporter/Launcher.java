package io.mdexporter;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Main class of the shaded jar. It does not extend {@link javafx.application.Application} so that JavaFX can be
 * loaded from the class path. With arguments it runs the command line exporter, otherwise the desktop UI.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        configureLogging();
        if (isCommandLine(args)) {
            System.exit(Cli.run(args));
        }
        App.main(args);
    }

    /** Any option switches to command line mode; bare file names open the files in the UI. */
    static boolean isCommandLine(String[] args) {
        for (String arg : args) {
            if (arg.startsWith("-") && !arg.startsWith("-psn")) {
                return true;
            }
        }
        return false;
    }

    /** Strong references: java.util.logging only keeps weak references to configured loggers. */
    private static final List<Logger> QUIET = new ArrayList<>();

    static void configureLogging() {
        // Keep the console quiet: openhtmltopdf is chatty, and JavaFX warns when loaded from the class path
        // (which is how the self-contained jar works).
        System.setProperty("xr.util-logging.loggingEnabled", "false");
        for (String name : List.of("javafx", "com.openhtmltopdf", "org.apache.pdfbox", "org.apache.fontbox")) {
            Logger logger = Logger.getLogger(name);
            logger.setLevel(Level.SEVERE);
            QUIET.add(logger);
        }
    }
}
