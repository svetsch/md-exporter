package io.mdexporter.diagram;

import javafx.animation.AnimationTimer;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.concurrent.Worker;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;
import netscape.javascript.JSObject;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Renders Mermaid diagrams with {@code mermaid.min.js} from the {@code org.webjars.npm:mermaid} WebJar inside an
 * off-screen JavaFX {@link WebView}.
 * <p>
 * The SVG produced by Mermaid is returned for HTML output; for Word/PDF the WebView is resized to the diagram and
 * snapshotted at a higher zoom level to obtain a crisp PNG (Mermaid SVGs use HTML labels inside
 * {@code foreignObject}, which SVG rasterisers such as Batik do not support).
 */
public final class MermaidRenderer implements DiagramRenderer {

    /** Maven metadata of the org.webjars.npm:mermaid dependency, used to locate the versioned script. */
    private static final String WEBJAR_POM = "/META-INF/maven/org.webjars.npm/mermaid/pom.properties";
    private static final String WEBJAR_SCRIPT = "/META-INF/resources/webjars/mermaid/%s/dist/mermaid.min.js";
    private static final double MAX_BITMAP = 8000;
    private static final int PADDING = 8;
    private static final long TIMEOUT_SECONDS = 60;

    private static final String PAGE = """
            <!DOCTYPE html>
            <html><head><meta charset="utf-8">
            <style>
              html { overflow: hidden; background: #ffffff; }
              /* fixed layout width: diagrams such as gantt charts size themselves to the body */
              body { margin: 0; padding: 0; width: 800px; overflow: visible; background: #ffffff; }
              #out { width: max-content; padding: %dpx; }
              #out svg { display: block; }
            </style></head>
            <body><div id="out"></div></body></html>
            """.formatted(PADDING);

    private static final String RENDER_JS = """
            (function () {
              window.__mdx = { state: 'pending' };
              var out = document.getElementById('out');
              out.innerHTML = '';
              var id = window.__mdxId;
              mermaid.render(id, window.__mdxSource).then(function (r) {
                out.innerHTML = r.svg;
                var svg = out.querySelector('svg');
                var w = 0, h = 0;
                var vb = svg.viewBox && svg.viewBox.baseVal;
                if (vb && vb.width > 0 && vb.height > 0) { w = vb.width; h = vb.height; }
                else { var b = svg.getBoundingClientRect(); w = b.width; h = b.height; }
                svg.setAttribute('width', w);
                svg.setAttribute('height', h);
                svg.style.maxWidth = 'none';
                var markup = new XMLSerializer().serializeToString(svg);
                window.__mdx = { state: 'ok', svg: markup, w: w, h: h };
              }).catch(function (e) {
                var junk = document.getElementById('d' + id);
                if (junk) { junk.remove(); }
                out.innerHTML = '';
                window.__mdx = { state: 'error', message: String((e && (e.message || e.str)) || e) };
              });
            })();
            """;

    private final ReentrantLock lock = new ReentrantLock(true);
    private Stage stage;
    private WebView webView;
    private boolean ready;
    private String currentTheme;
    private final List<Runnable> whenReady = new ArrayList<>();
    private int counter;

    @Override
    public Set<String> languages() {
        return Set.of("mermaid", "mmd");
    }

    @Override
    public RenderedDiagram render(String language, String source, Request request) throws Exception {
        lock.lock();
        try {
            return FxToolkit.<RenderedDiagram>callAsync(f -> start(source, request, f), TIMEOUT_SECONDS);
        } catch (TimeoutException e) {
            javafx.application.Platform.runLater(this::dispose);
            throw new TimeoutException("Mermaid rendering timed out");
        } finally {
            lock.unlock();
        }
    }

    // ---- everything below runs on the FX application thread ----

    private void start(String source, Request request, CompletableFuture<RenderedDiagram> result) {
        ensureView(() -> {
            try {
                applyTheme(request.theme());
                runRender(source, request, result);
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        }, result);
    }

    private void ensureView(Runnable then, CompletableFuture<?> result) {
        if (ready) {
            then.run();
            return;
        }
        whenReady.add(then);
        if (webView != null) {
            return; // loading in progress
        }
        webView = new WebView();
        webView.setContextMenuEnabled(false);
        webView.setPrefSize(800, 600);
        Group root = new Group(webView);
        stage = new Stage(StageStyle.UTILITY);
        stage.setTitle("Mermaid renderer");
        stage.setScene(new Scene(root, 800, 600, Color.WHITE));
        stage.setX(-20000);
        stage.setY(-20000);
        stage.setOpacity(0);
        stage.show();

        WebEngine engine = webView.getEngine();
        engine.setJavaScriptEnabled(true);
        engine.getLoadWorker().stateProperty().addListener((obs, old, state) -> {
            if (state == Worker.State.SUCCEEDED && !ready) {
                try {
                    engine.executeScript(loadMermaidScript());
                    Object ok = engine.executeScript("typeof mermaid !== 'undefined'");
                    if (!Boolean.TRUE.equals(ok)) {
                        throw new IllegalStateException("mermaid.js failed to initialise");
                    }
                    ready = true;
                    List<Runnable> pending = new ArrayList<>(whenReady);
                    whenReady.clear();
                    pending.forEach(Runnable::run);
                } catch (Throwable t) {
                    whenReady.clear();
                    result.completeExceptionally(t);
                    dispose();
                }
            } else if (state == Worker.State.FAILED) {
                whenReady.clear();
                result.completeExceptionally(new IllegalStateException("Mermaid host page failed to load"));
                dispose();
            }
        });
        engine.loadContent(PAGE);
    }

    private void applyTheme(String theme) {
        String t = theme == null || theme.isBlank() ? "default" : theme;
        if (t.equals(currentTheme)) {
            return;
        }
        JSObject window = (JSObject) webView.getEngine().executeScript("window");
        window.setMember("__mdxTheme", t);
        webView.getEngine().executeScript("""
                mermaid.initialize({
                  startOnLoad: false,
                  securityLevel: 'strict',
                  theme: window.__mdxTheme,
                  fontFamily: '"Segoe UI", "Helvetica Neue", Arial, sans-serif'
                });
                """);
        currentTheme = t;
    }

    private void runRender(String source, Request request, CompletableFuture<RenderedDiagram> result) {
        WebEngine engine = webView.getEngine();
        JSObject window = (JSObject) engine.executeScript("window");
        window.setMember("__mdxSource", source);
        window.setMember("__mdxId", "mdx-mermaid-" + (++counter));
        engine.executeScript(RENDER_JS);

        Timeline poll = new Timeline();
        long started = System.nanoTime();
        poll.getKeyFrames().add(new KeyFrame(Duration.millis(15), e -> {
            if (result.isDone()) {
                poll.stop();
                return;
            }
            JSObject state = (JSObject) engine.executeScript("window.__mdx");
            String s = String.valueOf(state.getMember("state"));
            if ("pending".equals(s)) {
                if (System.nanoTime() - started > 30_000_000_000L) {
                    poll.stop();
                    result.complete(RenderedDiagram.failure("mermaid", "Mermaid rendering did not finish"));
                }
                return;
            }
            poll.stop();
            if ("error".equals(s)) {
                result.complete(RenderedDiagram.failure("mermaid", String.valueOf(state.getMember("message"))));
                return;
            }
            String svg = String.valueOf(state.getMember("svg"));
            double w = ((Number) state.getMember("w")).doubleValue();
            double h = ((Number) state.getMember("h")).doubleValue();
            String inline = SvgUtil.normalizeRoot(svg, w, h);
            if (!request.png()) {
                result.complete(new RenderedDiagram("mermaid", inline, null, 1, w, h, null));
            } else {
                snapshot(inline, w, h, request.scale(), result);
            }
        }));
        poll.setCycleCount(Timeline.INDEFINITE);
        poll.play();
    }

    private void snapshot(String svg, double w, double h, double scale, CompletableFuture<RenderedDiagram> result) {
        double fullW = w + 2 * PADDING;
        double fullH = h + 2 * PADDING;
        double zoom = Math.max(0.25, Math.min(scale, MAX_BITMAP / Math.max(fullW, fullH)));
        int viewW = (int) Math.ceil(fullW * zoom);
        int viewH = (int) Math.ceil(fullH * zoom);
        webView.setZoom(zoom);
        webView.setMinSize(viewW, viewH);
        webView.setPrefSize(viewW, viewH);
        webView.setMaxSize(viewW, viewH);
        stage.setWidth(viewW + 40);
        stage.setHeight(viewH + 60);

        new AnimationTimer() {
            private int frames;

            @Override
            public void handle(long now) {
                if (++frames < 6) {
                    return;
                }
                stop();
                try {
                    WritableImage image = webView.snapshot(null, null);
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", out);
                    double effectiveScale = image.getWidth() / fullW;
                    result.complete(new RenderedDiagram("mermaid", svg, out.toByteArray(), effectiveScale,
                            fullW, fullH, null));
                } catch (Throwable t) {
                    result.completeExceptionally(t);
                } finally {
                    webView.setZoom(1);
                }
            }
        }.start();
    }

    private static String loadMermaidScript() {
        String path = WEBJAR_SCRIPT.formatted(mermaidVersion());
        try (InputStream in = MermaidRenderer.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Version of the Mermaid WebJar on the class path. */
    public static String mermaidVersion() {
        try (InputStream in = MermaidRenderer.class.getResourceAsStream(WEBJAR_POM)) {
            if (in == null) {
                throw new IllegalStateException("Mermaid WebJar (org.webjars.npm:mermaid) not found on the class path");
            }
            Properties properties = new Properties();
            properties.load(in);
            return properties.getProperty("version");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void dispose() {
        if (stage != null) {
            stage.close();
        }
        stage = null;
        webView = null;
        ready = false;
        currentTheme = null;
    }
}
