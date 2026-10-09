package io.mdexporter.diagram;

import javafx.application.Platform;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Starts the JavaFX toolkit on demand (command line mode) and runs work on the FX thread. */
public final class FxToolkit {

    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static volatile boolean startedByUs;

    private FxToolkit() {
    }

    public static void ensureStarted() {
        if (STARTED.get()) {
            return;
        }
        synchronized (FxToolkit.class) {
            if (STARTED.get()) {
                return;
            }
            CountDownLatch latch = new CountDownLatch(1);
            try {
                Platform.startup(latch::countDown);
                startedByUs = true;
                Platform.setImplicitExit(false);
                latch.await();
            } catch (IllegalStateException alreadyRunning) {
                // toolkit already started by the JavaFX application
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            STARTED.set(true);
        }
    }

    /** True when the toolkit was started by {@link #ensureStarted()} rather than by an Application. */
    public static boolean startedByUs() {
        return startedByUs;
    }

    /**
     * Runs an asynchronous operation on the FX thread and waits for its completion.
     *
     * @param operation receives the future to complete (possibly later, from FX callbacks)
     */
    public static <T> T callAsync(Consumer<CompletableFuture<T>> operation, long timeoutSeconds)
            throws Exception {
        if (Platform.isFxApplicationThread()) {
            throw new IllegalStateException("Blocking FX call from the FX application thread");
        }
        ensureStarted();
        CompletableFuture<T> future = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                operation.accept(future);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw e;
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new TimeoutException("Timed out after " + timeoutSeconds + " s");
        }
    }
}
