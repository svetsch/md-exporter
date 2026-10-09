package io.mdexporter.ui;

import io.mdexporter.core.ExportFormat;
import io.mdexporter.core.ExportOptions;
import io.mdexporter.core.PageSize;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.prefs.Preferences;

/** Persists user preferences (last folders, export options, window geometry). */
final class AppSettings {

    private static final int MAX_RECENT = 8;
    private final Preferences prefs = Preferences.userNodeForPackage(AppSettings.class);

    ExportOptions loadOptions() {
        ExportOptions o = ExportOptions.defaults();
        try {
            o.setPageSize(PageSize.valueOf(prefs.get("pageSize", PageSize.A4.name())));
            o.setOrientation(ExportOptions.Orientation.valueOf(prefs.get("orientation", "PORTRAIT")));
        } catch (IllegalArgumentException ignored) {
            // keep defaults
        }
        o.setMarginMm(prefs.getDouble("marginMm", o.getMarginMm()));
        o.setTableOfContents(prefs.getBoolean("toc", o.isTableOfContents()));
        o.setEmbedImages(prefs.getBoolean("embedImages", o.isEmbedImages()));
        o.setDiagramScale(prefs.getDouble("diagramScale", o.getDiagramScale()));
        o.setMermaidTheme(prefs.get("mermaidTheme", o.getMermaidTheme()));
        o.setOpenAfterExport(prefs.getBoolean("openAfterExport", o.isOpenAfterExport()));
        return o;
    }

    void saveOptions(ExportOptions o) {
        prefs.put("pageSize", o.getPageSize().name());
        prefs.put("orientation", o.getOrientation().name());
        prefs.putDouble("marginMm", o.getMarginMm());
        prefs.putBoolean("toc", o.isTableOfContents());
        prefs.putBoolean("embedImages", o.isEmbedImages());
        prefs.putDouble("diagramScale", o.getDiagramScale());
        prefs.put("mermaidTheme", o.getMermaidTheme());
        prefs.putBoolean("openAfterExport", o.isOpenAfterExport());
    }

    Set<ExportFormat> loadFormats() {
        Set<ExportFormat> set = EnumSet.noneOf(ExportFormat.class);
        for (String s : prefs.get("formats", "DOCX,HTML,PDF").split(",")) {
            try {
                set.add(ExportFormat.valueOf(s));
            } catch (IllegalArgumentException ignored) {
                // skip
            }
        }
        return set.isEmpty() ? EnumSet.allOf(ExportFormat.class) : set;
    }

    void saveFormats(Set<ExportFormat> formats) {
        prefs.put("formats", String.join(",", formats.stream().map(Enum::name).toList()));
    }

    File directory(String key) {
        String path = prefs.get(key, null);
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
            prefs.put(key, dir.getAbsolutePath());
        }
    }

    List<File> recentFiles() {
        List<File> list = new ArrayList<>();
        for (String s : prefs.get("recent", "").split("\n")) {
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
        prefs.put("recent", String.join("\n", list.stream().map(File::getAbsolutePath).toList()));
    }

    double[] windowBounds() {
        return new double[]{prefs.getDouble("winW", 1280), prefs.getDouble("winH", 820),
                prefs.getDouble("divider", 0.5)};
    }

    void windowBounds(double w, double h, double divider) {
        prefs.putDouble("winW", w);
        prefs.putDouble("winH", h);
        prefs.putDouble("divider", divider);
    }

    boolean wrapText() {
        return prefs.getBoolean("wrap", true);
    }

    void wrapText(boolean wrap) {
        prefs.putBoolean("wrap", wrap);
    }
}
