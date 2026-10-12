package io.mdexporter.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;

/** HTTP + JSON + server-sent events helpers shared by the provider clients. */
final class HttpAiSupport {

    static final ObjectMapper JSON = new ObjectMapper();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private HttpAiSupport() {
    }

    static URI uri(String base, String path) throws AiException {
        String b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        try {
            return URI.create(b + path);
        } catch (IllegalArgumentException e) {
            throw new AiException("Invalid endpoint URL: " + base, e);
        }
    }

    /** GET returning JSON. */
    static JsonNode getJson(HttpRequest.Builder request) throws AiException {
        try {
            HttpResponse<InputStream> response = HTTP.send(request.GET().timeout(Duration.ofSeconds(30)).build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() / 100 != 2) {
                    throw error(response.statusCode(), new String(body.readAllBytes(), StandardCharsets.UTF_8));
                }
                return JSON.readTree(body);
            }
        } catch (IOException e) {
            throw new AiException(describe(e, request.build().uri()), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiException("Interrupted", e);
        }
    }

    /** User readable description of a connection problem. */
    static String describe(Throwable t, URI uri) {
        String where = uri == null ? "" : " " + uri.getScheme() + "://" + uri.getAuthority();
        String message = null;
        for (Throwable cause = t; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.net.ConnectException) {
                return "Cannot connect to" + (where.isEmpty() ? " the server" : where)
                        + " (is the service running and the endpoint correct?)";
            }
            if (cause instanceof java.net.UnknownHostException) {
                return "Unknown host" + where + " (check the endpoint and the network connection)";
            }
            if (cause instanceof java.net.http.HttpTimeoutException) {
                return "Timed out connecting to" + (where.isEmpty() ? " the server" : where);
            }
            if (message == null && cause.getMessage() != null) {
                message = cause.getMessage();
            }
        }
        return "Network error: " + (message != null ? message : t.getClass().getSimpleName());
    }

    /**
     * POSTs {@code body} and passes every {@code data:} payload of the event stream to {@code onData}; stops at
     * {@code [DONE]}.
     */
    static void postStream(HttpRequest.Builder request, JsonNode body, AiClient.Cancellation cancel,
                           DataHandler onData) throws AiException {
        try {
            HttpRequest req = request
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            var future = HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofInputStream());
            cancel.onCancel(() -> future.cancel(true));
            HttpResponse<InputStream> response;
            try {
                response = future.get();
            } catch (java.util.concurrent.CancellationException e) {
                throw new AiException("Cancelled");
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                throw new AiException(describe(cause, req.uri()), cause);
            }
            try (InputStream in = response.body();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                cancel.onCancel(() -> closeQuietly(in));
                if (response.statusCode() / 100 != 2) {
                    StringBuilder text = new StringBuilder();
                    for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                        text.append(line).append('\n');
                    }
                    throw error(response.statusCode(), text.toString());
                }
                String line;
                while ((line = reader.readLine()) != null) {
                    cancel.check();
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring(5).trim();
                    if (data.isEmpty()) {
                        continue;
                    }
                    if ("[DONE]".equals(data)) {
                        break;
                    }
                    onData.accept(JSON.readTree(data));
                }
            }
        } catch (IOException e) {
            if (cancel.isCancelled()) {
                throw new AiException("Cancelled");
            }
            throw new AiException(describe(e, null), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiException("Interrupted", e);
        }
        cancel.check();
    }

    /** Builds an exception from an HTTP error response, using the JSON error message when there is one. */
    static AiException error(int status, String body) {
        String message = body == null ? "" : body.trim();
        try {
            JsonNode node = JSON.readTree(message);
            JsonNode err = node.isArray() && !node.isEmpty() ? node.get(0).path("error") : node.path("error");
            if (err.isTextual()) {
                message = err.asText();
            } else if (err.has("message")) {
                message = err.get("message").asText();
            }
        } catch (IOException | RuntimeException ignored) {
            // keep the raw body
        }
        if (message.length() > 500) {
            message = message.substring(0, 500) + "...";
        }
        String hint = switch (status) {
            case 401 -> "The API key was rejected (401)";
            case 403 -> "Access denied (403)";
            case 404 -> "Unknown model or endpoint (404)";
            case 429 -> "Rate limit or quota reached (429)";
            case 0 -> "Error";
            default -> "HTTP " + status;
        };
        return new AiException(message.isEmpty() ? hint + "." : hint + ": " + message);
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // already closed
        }
    }

    @FunctionalInterface
    interface DataHandler {
        void accept(JsonNode data) throws AiException;
    }

    /** Text of {@code node} or empty. */
    static void emit(JsonNode node, Consumer<String> onText) {
        if (node != null && node.isTextual() && !node.asText().isEmpty()) {
            onText.accept(node.asText());
        }
    }
}
