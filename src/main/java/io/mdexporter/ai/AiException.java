package io.mdexporter.ai;

/** Failure of an AI request, with a message suitable for the user. */
public class AiException extends Exception {

    public AiException(String message) {
        super(message);
    }

    public AiException(String message, Throwable cause) {
        super(message, cause);
    }
}
