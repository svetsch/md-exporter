package io.mdexporter.export;

import io.mdexporter.core.ImageSupport;
import io.mdexporter.core.MarkdownDocument;
import io.mdexporter.core.MarkdownParser;
import io.mdexporter.core.Nodes;
import io.mdexporter.core.ResourceLoader;
import io.mdexporter.diagram.RenderedDiagram;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Node;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.html.HtmlNodeRendererContext;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.renderer.html.HtmlWriter;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Builds a complete HTML document from Markdown. Used for the live preview, the HTML export and as the input of the
 * PDF renderer, each with slightly different handling of images and diagrams.
 */
public final class HtmlBuilder {

    /** What the HTML is generated for. */
    public enum Target {
        /** Live preview in the JavaFX WebView: local images by absolute URL, diagrams as inline SVG. */
        PREVIEW,
        /** Standalone HTML file: images embedded (or copied), diagrams as inline SVG. */
        HTML,
        /** Input for openhtmltopdf: everything embedded as raster images, print CSS. */
        PDF
    }

    private static final String TOKEN_PREFIX = "@@MDX_DIAGRAM_";

    private final Target target;
    private final ExportContext context;
    private final Path assetsDir;
    private final Map<String, String> svgPlaceholders = new LinkedHashMap<>();
    private final Map<String, String> copiedAssets = new HashMap<>();

    /**
     * @param assetsDir for {@link Target#HTML} without embedded images: directory receiving copied images
     */
    public HtmlBuilder(Target target, ExportContext context, Path assetsDir) {
        this.target = target;
        this.context = context;
        this.assetsDir = assetsDir;
    }

    /** Generated document; call {@link #toHtml()} to serialise it with diagrams inlined. */
    public final class Result {
        private final Document document;

        private Result(Document document) {
            this.document = document;
        }

        public Document document() {
            return document;
        }

        public String toHtml() {
            String html = document.outerHtml();
            for (Map.Entry<String, String> e : svgPlaceholders.entrySet()) {
                html = html.replace(e.getKey(), e.getValue());
            }
            return html;
        }
    }

    public Result build(MarkdownDocument md) {
        HtmlRenderer renderer = HtmlRenderer.builder()
                .extensions(MarkdownParser.EXTENSIONS)
                .nodeRendererFactory(CodeBlockRenderer::new)
                .build();
        String body = renderer.render(md.root());
        String title = md.title();

        StringBuilder html = new StringBuilder(body.length() + 4096);
        html.append("<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"utf-8\"/>")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>")
                .append("<meta name=\"generator\" content=\"md-exporter\"/>")
                .append("<title>").append(Entities.escape(title)).append("</title>");
        String author = md.frontMatterValue("author");
        if (author != null) {
            html.append("<meta name=\"author\" content=\"").append(Entities.escape(author)).append("\"/>");
        }
        String description = md.frontMatterValue("description");
        if (description != null) {
            html.append("<meta name=\"subject\" content=\"").append(Entities.escape(description)).append("\"/>");
        }
        html.append("<style>\n").append(css()).append("\n</style></head><body>")
                .append("<article class=\"markdown-body\">")
                .append(titleBlock(md))
                .append(body)
                .append("</article></body></html>");

        Document document = Jsoup.parse(html.toString());
        document.outputSettings().prettyPrint(false).charset(StandardCharsets.UTF_8);

        processImages(document);
        if (target == Target.PDF) {
            replaceCheckboxes(document);
            addPdfBookmarks(document);
        } else {
            wrapTables(document);
        }
        if (context.options().isTableOfContents()) {
            insertToc(document);
        }
        return new Result(document);
    }

    // ------------------------------------------------------------------ code blocks & diagrams

    private final class CodeBlockRenderer implements NodeRenderer {
        private final HtmlNodeRendererContext ctx;
        private final HtmlWriter html;

        CodeBlockRenderer(HtmlNodeRendererContext ctx) {
            this.ctx = ctx;
            this.html = ctx.getWriter();
        }

        @Override
        public Set<Class<? extends Node>> getNodeTypes() {
            return Set.of(FencedCodeBlock.class);
        }

        @Override
        public void render(Node node) {
            FencedCodeBlock block = (FencedCodeBlock) node;
            if (context.isDiagram(block)) {
                renderDiagram(block);
            } else {
                renderCode(block);
            }
        }

        private void renderCode(FencedCodeBlock block) {
            String language = Nodes.language(block.getInfo());
            Map<String, String> codeAttrs = new LinkedHashMap<>();
            if (!language.isEmpty()) {
                codeAttrs.put("class", "language-" + language);
            }
            html.line();
            html.tag("pre", ctx.extendAttributes(block, "pre", Map.of()));
            html.tag("code", ctx.extendAttributes(block, "code", codeAttrs));
            html.text(block.getLiteral());
            html.tag("/code");
            html.tag("/pre");
            html.line();
        }

        private void renderDiagram(FencedCodeBlock block) {
            boolean pdf = target == Target.PDF;
            RenderedDiagram d = context.diagram(block, pdf);
            String language = Nodes.language(block.getInfo());
            html.line();
            if (d.failed() || (pdf && !d.hasPng()) || (!pdf && d.svg() == null)) {
                html.raw("<div class=\"diagram-error\"><p><strong>" + Entities.escape(label(language))
                        + " diagram error:</strong> " + Entities.escape(String.valueOf(d.error())) + "</p><pre><code>"
                        + Entities.escape(block.getLiteral()) + "</code></pre></div>");
            } else if (pdf) {
                String uri = "data:image/png;base64," + Base64.getEncoder().encodeToString(d.png());
                html.raw("<figure class=\"diagram diagram-" + language + "\"><img src=\"" + uri + "\" alt=\""
                        + label(language) + " diagram\" style=\"width:" + Math.round(d.width()) + "px\"/></figure>");
            } else {
                String token = TOKEN_PREFIX + svgPlaceholders.size() + "@@";
                svgPlaceholders.put(token, d.svg());
                html.raw("<figure class=\"diagram diagram-" + language + "\">" + token + "</figure>");
            }
            html.line();
        }
    }

    static String label(String language) {
        return switch (language) {
            case "mermaid", "mmd" -> "Mermaid";
            case "dot", "graphviz" -> "Graphviz";
            default -> "PlantUML";
        };
    }

    // ------------------------------------------------------------------ images

    private void processImages(Document document) {
        ResourceLoader resources = context.resources();
        for (Element img : document.select("img[src]")) {
            String src = img.attr("src");
            if (src.startsWith("data:image/png;base64,") && img.parent() != null
                    && img.parent().hasClass("diagram")) {
                continue; // rendered diagram
            }
            switch (target) {
                case PREVIEW -> img.attr("src", resources.toBrowserUri(src));
                case HTML -> {
                    if (context.options().isEmbedImages()) {
                        resources.load(src).ifPresent(r -> img.attr("src", r.toDataUri()));
                    } else if (assetsDir != null && !ResourceLoader.isRemote(src) && !src.startsWith("data:")) {
                        copyAsset(src).ifPresent(rel -> img.attr("src", rel));
                    }
                }
                case PDF -> {
                    Optional<ResourceLoader.Resource> res = resources.load(src);
                    ImageSupport.Raster raster = res.map(this::pdfRaster).orElse(null);
                    if (raster != null) {
                        img.attr("src", "data:" + raster.mimeType() + ";base64,"
                                + Base64.getEncoder().encodeToString(raster.data()));
                        applyPdfSize(img, raster);
                    } else {
                        Element span = new Element("span").addClass("missing-image")
                                .text("[image: " + (img.attr("alt").isEmpty() ? src : img.attr("alt")) + "]");
                        img.replaceWith(span);
                    }
                }
            }
        }
    }

    private ImageSupport.Raster pdfRaster(ResourceLoader.Resource resource) {
        try {
            return ImageSupport.toRaster(resource, context.options().getDiagramScale());
        } catch (IOException e) {
            return null;
        }
    }

    /** openhtmltopdf ignores width/height attributes: translate them (or the raster density) to CSS. */
    private static double px(String cssPx) {
        return Double.parseDouble(cssPx.substring(0, cssPx.length() - 2));
    }

    private void applyPdfSize(Element img, ImageSupport.Raster raster) {
        String width = cssLength(img.attr("width"));
        String height = cssLength(img.attr("height"));
        double ratio = raster.logicalHeight() / Math.max(1, raster.logicalWidth());
        if (width == null && height == null && Math.abs(raster.scale() - 1) > 0.01) {
            width = Math.round(raster.logicalWidth()) + "px";
        }
        // inline images need both dimensions, otherwise the intrinsic size wins
        if (width != null && height == null && width.endsWith("px")) {
            height = Math.round(px(width) * ratio) + "px";
        } else if (height != null && width == null && height.endsWith("px")) {
            width = Math.round(px(height) / ratio) + "px";
        }
        double maxWidth = (context.options().pageWidthMm() - 2 * context.options().getMarginMm()) / 25.4 * 96;
        if (width != null && height != null && width.endsWith("px") && height.endsWith("px")
                && px(width) > maxWidth) {
            height = Math.round(px(height) * maxWidth / px(width)) + "px";
            width = Math.round(maxWidth) + "px";
        }
        StringBuilder style = new StringBuilder(img.attr("style"));
        if (width != null) {
            style.append(";width:").append(width);
        }
        if (height != null) {
            style.append(";height:").append(height);
        }
        if (!style.isEmpty()) {
            img.attr("style", style.toString().replaceFirst("^;", ""));
        }
        img.removeAttr("width").removeAttr("height");
    }

    private static String cssLength(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        if (v.matches("\\d+(\\.\\d+)?")) {
            return v + "px";
        }
        return v.matches("\\d+(\\.\\d+)?(px|%|pt|mm|cm|in|em)") ? v : null;
    }

    private Optional<String> copyAsset(String src) {
        String existing = copiedAssets.get(src);
        if (existing != null) {
            return Optional.of(existing);
        }
        return context.resources().load(src).map(resource -> {
            try {
                Files.createDirectories(assetsDir);
                String name = safeFileName(resource.fileName(), resource.extension());
                Path target = assetsDir.resolve(name);
                int n = 1;
                while (Files.exists(target) && copiedAssets.containsValue(relative(target))) {
                    target = assetsDir.resolve(n++ + "-" + name);
                }
                Files.write(target, resource.data());
                String rel = relative(target);
                copiedAssets.put(src, rel);
                return rel;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private String relative(Path file) {
        return assetsDir.getFileName() + "/" + file.getFileName();
    }

    private static String safeFileName(String name, String ext) {
        String clean = name == null ? "" : name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (clean.isBlank() || clean.equals("embedded")) {
            clean = "image." + ext;
        }
        return clean;
    }

    // ------------------------------------------------------------------ structure helpers

    private static String titleBlock(MarkdownDocument md) {
        String title = md.frontMatterValue("title");
        if (title == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<header class=\"doc-header\"><h1 class=\"doc-title\">")
                .append(Entities.escape(title)).append("</h1>");
        String subtitle = md.frontMatterValue("subtitle");
        if (subtitle != null) {
            sb.append("<p class=\"doc-subtitle\">").append(Entities.escape(subtitle)).append("</p>");
        }
        String author = md.frontMatterValue("author");
        String date = md.frontMatterValue("date");
        if (author != null || date != null) {
            sb.append("<p class=\"doc-meta\">");
            if (author != null) {
                sb.append(Entities.escape(author));
            }
            if (author != null && date != null) {
                sb.append(" · ");
            }
            if (date != null) {
                sb.append(Entities.escape(date));
            }
            sb.append("</p>");
        }
        return sb.append("</header>").toString();
    }

    private static void wrapTables(Document document) {
        for (Element table : document.select("article table")) {
            table.wrap("<div class=\"table-wrapper\"></div>");
        }
    }

    private static void replaceCheckboxes(Document document) {
        for (Element box : document.select("input[type=checkbox]")) {
            boolean checked = box.hasAttr("checked");
            Element span = new Element("span").addClass("task-box").addClass(checked ? "checked" : "unchecked")
                    .text(checked ? "☑" : "☐");
            box.replaceWith(span);
            if (span.parent() != null) {
                span.parent().addClass("task-list-item");
            }
        }
    }

    private static List<Element> headings(Document document) {
        return document.select("article h1[id], article h2[id], article h3[id], article h4[id], "
                + "article h5[id], article h6[id]");
    }

    private void insertToc(Document document) {
        List<Element> headings = headings(document);
        if (headings.isEmpty()) {
            return;
        }
        Element nav = new Element("nav").addClass("toc");
        nav.appendElement("p").addClass("toc-title").text("Contents");
        Element list = nav.appendElement("ul");
        Deque<Element> stack = new ArrayDeque<>();
        Deque<Integer> levels = new ArrayDeque<>();
        stack.push(list);
        levels.push(1);
        for (Element h : headings) {
            int level = h.tagName().charAt(1) - '0';
            if (level > 3) {
                continue;
            }
            while (level > levels.peek() && stack.peek().children().size() > 0) {
                Element sub = stack.peek().children().last().appendElement("ul");
                stack.push(sub);
                levels.push(levels.peek() + 1);
            }
            while (level < levels.peek() && stack.size() > 1) {
                stack.pop();
                levels.pop();
            }
            Element li = stack.peek().appendElement("li");
            li.appendElement("a").addClass("toc-link").attr("href", "#" + h.id()).text(h.text());
        }
        Element article = document.selectFirst("article");
        Element header = article.selectFirst("header.doc-header");
        if (header != null) {
            header.after(nav);
        } else {
            article.prependChild(nav);
        }
    }

    private static void addPdfBookmarks(Document document) {
        List<Element> headings = headings(document);
        if (headings.isEmpty()) {
            return;
        }
        Element bookmarks = document.head().appendElement("bookmarks");
        Deque<Element> stack = new ArrayDeque<>();
        Deque<Integer> levels = new ArrayDeque<>();
        for (Element h : headings) {
            int level = h.tagName().charAt(1) - '0';
            while (!levels.isEmpty() && levels.peek() >= level) {
                levels.pop();
                stack.pop();
            }
            Element parent = stack.isEmpty() ? bookmarks : stack.peek();
            Element bm = parent.appendElement("bookmark").attr("name", h.text()).attr("href", "#" + h.id());
            stack.push(bm);
            levels.push(level);
        }
    }

    // ------------------------------------------------------------------ CSS

    private String css() {
        if (target == Target.PDF) {
            var o = context.options();
            String page = String.format(Locale.ROOT, "@page { size: %.1fmm %.1fmm; margin: %.1fmm; }%n",
                    o.pageWidthMm(), o.pageHeightMm(), o.getMarginMm());
            return page + resource("css/pdf.css");
        }
        return resource("css/document.css");
    }

    static String resource(String name) {
        try (InputStream in = HtmlBuilder.class.getResourceAsStream("/io/mdexporter/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
