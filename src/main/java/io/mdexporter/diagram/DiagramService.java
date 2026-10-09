package io.mdexporter.diagram;

import io.mdexporter.core.Nodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Dispatches diagram code blocks to the matching renderer and caches the results. */
public final class DiagramService {

    private static final Logger LOG = Logger.getLogger(DiagramService.class.getName());
    private static final int MAX_CACHE = 256;

    private record Key(String language, String theme, String source) {
    }

    private final List<DiagramRenderer> renderers;
    private final Map<Key, RenderedDiagram> cache = new ConcurrentHashMap<>();

    public DiagramService(List<DiagramRenderer> renderers) {
        this.renderers = List.copyOf(renderers);
    }

    /** Service with all built-in renderers (Mermaid via JavaFX WebView, PlantUML/Graphviz via PlantUML). */
    public static DiagramService createDefault() {
        List<DiagramRenderer> list = new ArrayList<>();
        list.add(new MermaidRenderer());
        list.add(new PlantUmlRenderer());
        return new DiagramService(list);
    }

    /** True when the fenced code block info string denotes a supported diagram language. */
    public boolean isDiagram(String info) {
        return find(Nodes.language(info)) != null;
    }

    private DiagramRenderer find(String language) {
        for (DiagramRenderer renderer : renderers) {
            if (renderer.languages().contains(language)) {
                return renderer;
            }
        }
        return null;
    }

    /**
     * Renders (or returns a cached rendering of) a diagram. Never throws: failures are returned as
     * {@link RenderedDiagram#failure}. Must not be called on the JavaFX application thread.
     */
    public RenderedDiagram render(String info, String source, DiagramRenderer.Request request) {
        String language = Nodes.language(info);
        DiagramRenderer renderer = find(language);
        if (renderer == null) {
            return RenderedDiagram.failure(language, "Unsupported diagram language: " + language);
        }
        Key key = new Key(language, Objects.requireNonNullElse(request.theme(), "default"), source);
        RenderedDiagram cached = cache.get(key);
        if (cached != null && (cached.failed() || !request.png()
                || (cached.hasPng() && cached.pngScale() >= request.scale() - 0.01))) {
            return cached;
        }
        RenderedDiagram result;
        try {
            result = renderer.render(language, source, request);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Diagram rendering failed", e);
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            result = RenderedDiagram.failure(language, msg);
        }
        if (cache.size() > MAX_CACHE) {
            cache.clear();
        }
        cache.put(key, result);
        return result;
    }

    public void clearCache() {
        cache.clear();
    }
}
