package io.mdexporter.ai;

import java.util.List;
import java.util.function.Consumer;

/** Talks to one AI service. Calls block; run them on a background thread. */
public interface AiClient {

    /**
     * Sends the request and streams the answer.
     *
     * @param request what to ask
     * @param onText  receives the answer piece by piece (on the calling thread)
     * @param cancel  checked while streaming; {@link Cancellation#onCancel} aborts the connection
     * @throws AiException on failure, refusal or cancellation
     */
    void stream(AiRequest request, Consumer<String> onText, Cancellation cancel) throws AiException;

    /** Model ids offered by the service. */
    List<String> listModels() throws AiException;

    /** What to send. */
    record AiRequest(String system, String user, int maxTokens) {
    }

    /** Cooperative cancellation shared between the UI and the worker thread. */
    final class Cancellation {
        private volatile boolean cancelled;
        private volatile Runnable onCancel;

        public void cancel() {
            cancelled = true;
            Runnable r = onCancel;
            if (r != null) {
                r.run();
            }
        }

        public boolean isCancelled() {
            return cancelled;
        }

        /** Registers the action closing the current connection. */
        public void onCancel(Runnable action) {
            onCancel = action;
            if (cancelled && action != null) {
                action.run();
            }
        }

        public void check() throws AiException {
            if (cancelled) {
                throw new AiException("Cancelled");
            }
        }
    }
}
