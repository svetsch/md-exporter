package io.mdexporter.diagram;

/**
 * Result of rendering a diagram code block.
 *
 * @param language the diagram language (mermaid, plantuml, ...)
 * @param svg      SVG markup suitable for inlining in HTML (may be null)
 * @param png      raster version (may be null when not requested)
 * @param pngScale pixel density of {@code png} (pixels per logical pixel)
 * @param width    logical width in CSS pixels
 * @param height   logical height in CSS pixels
 * @param error    error message when rendering failed (other fields may then be null)
 */
public record RenderedDiagram(String language, String svg, byte[] png, double pngScale,
                              double width, double height, String error) {

    public static RenderedDiagram failure(String language, String error) {
        return new RenderedDiagram(language, null, null, 1, 0, 0, error);
    }

    public boolean failed() {
        return error != null;
    }

    public boolean hasPng() {
        return png != null && png.length > 0;
    }
}
