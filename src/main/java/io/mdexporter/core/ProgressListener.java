package io.mdexporter.core;

/** Receives export progress updates. */
@FunctionalInterface
public interface ProgressListener {

    ProgressListener NONE = (progress, message) -> { };

    /**
     * @param progress value between 0 and 1, or a negative value when indeterminate
     * @param message  human readable status
     */
    void update(double progress, String message);
}
