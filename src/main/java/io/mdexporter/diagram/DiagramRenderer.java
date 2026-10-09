package io.mdexporter.diagram;

import java.util.Set;

/** Renders the source of a diagram code block into SVG and (optionally) PNG. */
public interface DiagramRenderer {

    /**
     * Rendering parameters.
     *
     * @param png   whether a raster version is needed
     * @param scale raster pixel density
     * @param theme renderer specific theme name
     */
    record Request(boolean png, double scale, String theme) {
    }

    /** Code block languages handled by this renderer (lower case). */
    Set<String> languages();

    /** Renders a diagram. Implementations report syntax errors through {@link RenderedDiagram#failure}. */
    RenderedDiagram render(String language, String source, Request request) throws Exception;
}
