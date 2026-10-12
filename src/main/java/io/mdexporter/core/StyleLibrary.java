package io.mdexporter.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * The built-in styles plus the custom styles stored as {@code <id>.json} files in a folder. The JSON format is
 * documented in the README and used for import / export and by the AI style generator:
 * <pre>
 * { "name": "Corporate", "description": "...",
 *   "colors": { "text": "#222222", "muted": ..., "border": ..., "subtle": ..., "background": ..., "accent": ...,
 *               "heading": ..., "codeBackground": ..., "tableHeader": ... },
 *   "fonts": { "text": "Georgia", "headings": "Georgia", "code": "Consolas" },
 *   "headingRules": true, "coloredHeadings": false, "dark": false,
 *   "css": "h1 { letter-spacing: .02em; }" }
 * </pre>
 */
public final class StyleLibrary {

    private static final Logger LOG = Logger.getLogger(StyleLibrary.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final String EXTENSION = ".json";

    private final Path directory;

    public StyleLibrary(Path directory) {
        this.directory = directory;
    }

    public Path directory() {
        return directory;
    }

    // ------------------------------------------------------------------ listing

    public static List<StyleDefinition> builtIn() {
        return Arrays.stream(ExportTheme.values()).map(ExportTheme::definition).toList();
    }

    /** Custom styles, by name. Unreadable files are skipped (and logged). */
    public List<StyleDefinition> custom() {
        List<StyleDefinition> list = new ArrayList<>();
        if (directory == null || !Files.isDirectory(directory)) {
            return list;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*" + EXTENSION)) {
            for (Path file : files) {
                try {
                    list.add(read(file));
                } catch (IOException | IllegalArgumentException e) {
                    LOG.warning("Skipping style " + file + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            LOG.warning("Cannot list styles in " + directory + ": " + e);
        }
        list.sort(Comparator.comparing(s -> s.name().toLowerCase(Locale.ROOT)));
        return list;
    }

    /** Built-in styles first, then the custom ones. */
    public List<StyleDefinition> all() {
        List<StyleDefinition> list = new ArrayList<>(builtIn());
        list.addAll(custom());
        return list;
    }

    /** Finds a style by settings key ({@link StyleDefinition#key()}), built-in name or custom style name. */
    public Optional<StyleDefinition> find(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        if (StyleDefinition.isCustomKey(key)) {
            String id = StyleDefinition.customId(key);
            return custom().stream().filter(s -> s.id().equals(id)).findFirst();
        }
        for (ExportTheme t : ExportTheme.values()) {
            if (t.name().equalsIgnoreCase(key) || t.displayName().equalsIgnoreCase(key)) {
                return Optional.of(t.definition());
            }
        }
        return custom().stream().filter(s -> s.name().equalsIgnoreCase(key) || s.id().equalsIgnoreCase(key))
                .findFirst();
    }

    // ------------------------------------------------------------------ changes

    /**
     * Saves a custom style. A style without id (new, or a copy of a built-in style) gets one derived from its name.
     *
     * @return the saved style, with its id
     */
    public StyleDefinition save(StyleDefinition style) throws IOException {
        Files.createDirectories(directory);
        String id = style.id();
        if (style.builtIn() || id == null || id.isBlank()) {
            id = uniqueId(slug(style.name()));
        }
        StyleDefinition saved = new StyleDefinition(id, style.name(), style.description(), style.palette(),
                style.bodyFont(), style.headingFont(), style.codeFont(), style.headingRules(), style.accentHeadings(),
                style.dark(), style.css(), false);
        Path tmp = directory.resolve(id + EXTENSION + ".tmp");
        Files.writeString(tmp, toJson(saved), StandardCharsets.UTF_8);
        Files.move(tmp, directory.resolve(id + EXTENSION), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return saved;
    }

    public void delete(StyleDefinition style) throws IOException {
        if (!style.builtIn() && style.id() != null) {
            Files.deleteIfExists(directory.resolve(style.id() + EXTENSION));
        }
    }

    /** Copies the custom style files to another folder and removes them here (settings moved elsewhere). */
    public void moveTo(Path target) throws IOException {
        if (directory == null || !Files.isDirectory(directory) || directory.equals(target)) {
            return;
        }
        Files.createDirectories(target);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*" + EXTENSION)) {
            for (Path file : files) {
                Path dest = target.resolve(file.getFileName());
                if (!Files.exists(dest)) {
                    Files.move(file, dest);
                }
            }
        }
    }

    private String uniqueId(String base) {
        String id = base;
        for (int n = 2; Files.exists(directory.resolve(id + EXTENSION)); n++) {
            id = base + "-" + n;
        }
        return id;
    }

    static String slug(String name) {
        String s = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        return s.isEmpty() ? "style" : s.length() > 40 ? s.substring(0, 40) : s;
    }

    // ------------------------------------------------------------------ JSON

    public static StyleDefinition read(Path file) throws IOException {
        String name = file.getFileName().toString();
        String id = name.substring(0, name.length() - EXTENSION.length());
        StyleDefinition s = fromJson(Files.readString(file, StandardCharsets.UTF_8),
                ExportTheme.GITHUB.definition());
        return new StyleDefinition(id, s.name(), s.description(), s.palette(), s.bodyFont(), s.headingFont(),
                s.codeFont(), s.headingRules(), s.accentHeadings(), s.dark(), s.css(), false);
    }

    public static String toJson(StyleDefinition s) {
        ObjectNode root = JSON.createObjectNode();
        root.put("name", s.name());
        root.put("description", s.description());
        ObjectNode colors = root.putObject("colors");
        ExportTheme.Palette p = s.palette();
        colors.put("text", "#" + p.text());
        colors.put("muted", "#" + p.muted());
        colors.put("border", "#" + p.border());
        colors.put("subtle", "#" + p.subtle());
        colors.put("background", "#" + p.background());
        colors.put("accent", "#" + p.accent());
        colors.put("heading", "#" + p.heading());
        colors.put("codeBackground", "#" + p.codeBg());
        colors.put("tableHeader", "#" + p.tableHeader());
        ObjectNode fonts = root.putObject("fonts");
        fonts.put("text", s.bodyFont().displayName());
        fonts.put("headings", s.headingFont().displayName());
        fonts.put("code", s.codeFont().displayName());
        root.put("headingRules", s.headingRules());
        root.put("coloredHeadings", s.accentHeadings());
        root.put("dark", s.dark());
        root.put("css", s.css());
        try {
            return JSON.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Reads a style; missing or invalid values are taken from {@code base}, so partial JSON (or a slightly wrong
     * AI answer) still gives a usable style. A JSON object embedded in other text (e.g. in a code fence) is found.
     *
     * @throws IllegalArgumentException when there is no JSON object at all
     */
    public static StyleDefinition fromJson(String text, StyleDefinition base) {
        JsonNode root = parseObject(text);
        ExportTheme.Palette b = base.palette();
        JsonNode c = root.path("colors");
        ExportTheme.Palette palette = new ExportTheme.Palette(
                color(c, "text", b.text()), color(c, "muted", b.muted()), color(c, "border", b.border()),
                color(c, "subtle", b.subtle()), color(c, "background", b.background()),
                color(c, "accent", b.accent()), color(c, "heading", b.heading()),
                color(c, "codeBackground", b.codeBg()), color(c, "tableHeader", b.tableHeader()));
        JsonNode f = root.path("fonts");
        return new StyleDefinition(base.id(), text(root, "name", base.name()),
                text(root, "description", base.description()), palette,
                font(f, "text", base.bodyFont(), false), font(f, "headings", base.headingFont(), false),
                font(f, "code", base.codeFont(), true),
                bool(root, "headingRules", base.headingRules()), bool(root, "coloredHeadings", base.accentHeadings()),
                bool(root, "dark", base.dark()), text(root, "css", base.css()), base.builtIn());
    }

    private static JsonNode parseObject(String text) {
        String t = text == null ? "" : text.strip();
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("No JSON object found");
        }
        try {
            JsonNode node = JSON.readTree(t.substring(start, end + 1));
            if (!node.isObject()) {
                throw new IllegalArgumentException("Not a JSON object");
            }
            return node;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON: " + e.getOriginalMessage(), e);
        }
    }

    private static String color(JsonNode parent, String field, String fallback) {
        String c = ExportOptions.normalizeColor(parent.path(field).asText(null));
        return c != null ? c : fallback;
    }

    private static ThemeFont font(JsonNode parent, String field, ThemeFont fallback, boolean mono) {
        String name = parent.path(field).asText(null);
        if (name == null) {
            return fallback;
        }
        try {
            ThemeFont font = ThemeFont.parse(name);
            return font.isMonospaced() == mono ? font : fallback;
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static String text(JsonNode parent, String field, String fallback) {
        JsonNode n = parent.get(field);
        return n != null && n.isTextual() ? n.asText() : fallback;
    }

    private static boolean bool(JsonNode parent, String field, boolean fallback) {
        JsonNode n = parent.get(field);
        return n != null && n.isBoolean() ? n.asBoolean() : fallback;
    }
}
