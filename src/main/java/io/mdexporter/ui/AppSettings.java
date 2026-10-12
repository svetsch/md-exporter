package io.mdexporter.ui;

import io.mdexporter.ai.AiAgent;
import io.mdexporter.core.ExportFormat;
import io.mdexporter.core.ExportOptions;
import io.mdexporter.core.ExportTheme;
import io.mdexporter.core.ThemeFont;
import io.mdexporter.core.PageSize;
import io.mdexporter.core.StyleLibrary;
import io.mdexporter.settings.SettingsLocation;
import io.mdexporter.settings.SettingsStore;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Persists user preferences (last folders, export options, window geometry, editor and AI settings) in the
 * {@link SettingsStore} chosen by the user: the registry or a settings file.
 */
final class AppSettings {

    private static final int MAX_RECENT = 8;
    static final double DEFAULT_FONT_SIZE = 14;

    private SettingsStore store;

    AppSettings() {
        SettingsStore resolved;
        try {
            resolved = SettingsLocation.resolve();
        } catch (RuntimeException e) {
            resolved = SettingsLocation.SYSTEM.open(); // unreadable settings file
        }
        this.store = resolved;
    }

    SettingsStore store() {
        return store;
    }

    /** Moves every setting (and the custom styles) to {@code location}. */
    void moveTo(SettingsLocation location) {
        StyleLibrary old = styleLibrary();
        store = SettingsLocation.moveTo(store, location);
        try {
            old.moveTo(styleLibrary().directory());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("Cannot move the styles folder " + old.directory(), e);
        }
    }

    /** Built-in and custom export styles; custom ones live in a folder next to the settings. */
    StyleLibrary styleLibrary() {
        return new StyleLibrary(store.location().stylesDirectory());
    }

    void flush() {
        store.flush();
    }

    ExportOptions loadOptions() {
        ExportOptions o = ExportOptions.defaults();
        try {
            o.setPageSize(PageSize.valueOf(store.get("pageSize", PageSize.A4.name())));
            o.setOrientation(ExportOptions.Orientation.valueOf(store.get("orientation", "PORTRAIT")));
        } catch (IllegalArgumentException ignored) {
            // keep defaults
        }
        o.setMarginMm(store.getDouble("marginMm", o.getMarginMm()));
        o.setTableOfContents(store.getBoolean("toc", o.isTableOfContents()));
        o.setEmbedImages(store.getBoolean("embedImages", o.isEmbedImages()));
        o.setDiagramScale(store.getDouble("diagramScale", o.getDiagramScale()));
        o.setMermaidTheme(store.get("mermaidTheme", o.getMermaidTheme()));
        o.setOpenAfterExport(store.getBoolean("openAfterExport", o.isOpenAfterExport()));
        o.setStyleDefinition(styleLibrary().find(store.get("style.theme", ExportTheme.GITHUB.name()))
                .orElse(ExportTheme.GITHUB.definition()));
        o.setAccentColor(store.get("style.accent", null));
        o.setBodyFont(font(store.get("style.bodyFont", null)));
        o.setHeadingFont(font(store.get("style.headingFont", null)));
        o.setCodeFont(font(store.get("style.codeFont", null)));
        o.setTextScale(store.getDouble("style.textScale", 1.0));
        o.setJustify(store.getBoolean("style.justify", false));
        o.setCustomCss(store.get("style.css", null));
        return o;
    }

    private static ThemeFont font(String name) {
        try {
            return name == null ? null : ThemeFont.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    void saveOptions(ExportOptions o) {
        store.put("pageSize", o.getPageSize().name());
        store.put("orientation", o.getOrientation().name());
        store.putDouble("marginMm", o.getMarginMm());
        store.putBoolean("toc", o.isTableOfContents());
        store.putBoolean("embedImages", o.isEmbedImages());
        store.putDouble("diagramScale", o.getDiagramScale());
        store.put("mermaidTheme", o.getMermaidTheme());
        store.putBoolean("openAfterExport", o.isOpenAfterExport());
        store.put("style.theme", o.getStyleDefinition().key());
        store.put("style.accent", o.getAccentColor());
        store.put("style.bodyFont", o.getBodyFont() == null ? null : o.getBodyFont().name());
        store.put("style.headingFont", o.getHeadingFont() == null ? null : o.getHeadingFont().name());
        store.put("style.codeFont", o.getCodeFont() == null ? null : o.getCodeFont().name());
        store.putDouble("style.textScale", o.getTextScale());
        store.putBoolean("style.justify", o.isJustify());
        store.put("style.css", o.getCustomCss());
    }

    Set<ExportFormat> loadFormats() {
        Set<ExportFormat> set = EnumSet.noneOf(ExportFormat.class);
        for (String s : store.get("formats", "DOCX,HTML,PDF").split(",")) {
            try {
                set.add(ExportFormat.valueOf(s));
            } catch (IllegalArgumentException ignored) {
                // skip
            }
        }
        return set.isEmpty() ? EnumSet.allOf(ExportFormat.class) : set;
    }

    void saveFormats(Set<ExportFormat> formats) {
        store.put("formats", String.join(",", formats.stream().map(Enum::name).toList()));
    }

    File directory(String key) {
        String path = store.get(key, null);
        if (path != null) {
            File f = new File(path);
            if (f.isDirectory()) {
                return f;
            }
        }
        return null;
    }

    void directory(String key, File dir) {
        if (dir != null) {
            store.put(key, dir.getAbsolutePath());
        }
    }

    List<File> recentFiles() {
        List<File> list = new ArrayList<>();
        for (String s : store.get("recent", "").split("\n")) {
            if (!s.isBlank() && new File(s).isFile()) {
                list.add(new File(s));
            }
        }
        return list;
    }

    void addRecent(File file) {
        List<File> list = recentFiles();
        list.removeIf(f -> f.getAbsolutePath().equals(file.getAbsolutePath()));
        list.add(0, file.getAbsoluteFile());
        while (list.size() > MAX_RECENT) {
            list.remove(list.size() - 1);
        }
        store.put("recent", String.join("\n", list.stream().map(File::getAbsolutePath).toList()));
    }

    void clearRecent() {
        store.remove("recent");
    }

    double[] windowBounds() {
        return new double[]{store.getDouble("winW", 1280), store.getDouble("winH", 820),
                store.getDouble("divider", 0.5)};
    }

    void windowBounds(double w, double h, double divider) {
        store.putDouble("winW", w);
        store.putDouble("winH", h);
        store.putDouble("divider", divider);
    }

    boolean wrapText() {
        return store.getBoolean("wrap", true);
    }

    void wrapText(boolean wrap) {
        store.putBoolean("wrap", wrap);
    }

    // ------------------------------------------------------------------ editor

    boolean showPreview() {
        return store.getBoolean("preview", true);
    }

    void showPreview(boolean value) {
        store.putBoolean("preview", value);
    }

    boolean syncPreview() {
        return store.getBoolean("syncPreview", true);
    }

    void syncPreview(boolean value) {
        store.putBoolean("syncPreview", value);
    }

    boolean showFormatBar() {
        return store.getBoolean("formatBar", true);
    }

    void showFormatBar(boolean value) {
        store.putBoolean("formatBar", value);
    }

    double fontSize() {
        return Math.max(8, Math.min(40, store.getDouble("fontSize", DEFAULT_FONT_SIZE)));
    }

    void fontSize(double size) {
        store.putDouble("fontSize", size);
    }

    String fontFamily() {
        return store.get("fontFamily", "Consolas");
    }

    void fontFamily(String family) {
        store.put("fontFamily", family);
    }

    /** Enter continues lists, Tab moves between table cells and indents list items. */
    boolean smartEditing() {
        return store.getBoolean("smartEditing", true);
    }

    void smartEditing(boolean value) {
        store.putBoolean("smartEditing", value);
    }

    // ------------------------------------------------------------------ AI

    boolean aiEnabled() {
        return store.getBoolean("ai.enabled", false);
    }

    void aiEnabled(boolean value) {
        store.putBoolean("ai.enabled", value);
    }

    List<AiAgent> aiAgents() {
        return AiAgent.load(store);
    }

    void aiAgents(List<AiAgent> agents) {
        AiAgent.save(store, agents);
    }

    String activeAgentId() {
        return store.get("ai.active", "");
    }

    void activeAgentId(String id) {
        store.put("ai.active", id);
    }

    /** The active agent, else the first one, else {@code null}. */
    AiAgent activeAgent() {
        List<AiAgent> agents = aiAgents();
        String id = activeAgentId();
        return agents.stream().filter(a -> a.id().equals(id)).findFirst()
                .orElse(agents.isEmpty() ? null : agents.get(0));
    }

    boolean aiSendContext() {
        return store.getBoolean("ai.context", true);
    }

    void aiSendContext(boolean value) {
        store.putBoolean("ai.context", value);
    }

    String aiLastInstruction() {
        return store.get("ai.instruction", "");
    }

    void aiLastInstruction(String value) {
        store.put("ai.instruction", value);
    }
}
