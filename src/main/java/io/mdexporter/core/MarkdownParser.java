package io.mdexporter.core;

import org.commonmark.Extension;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.footnotes.FootnotesExtension;
import org.commonmark.ext.front.matter.YamlFrontMatterExtension;
import org.commonmark.ext.front.matter.YamlFrontMatterVisitor;
import org.commonmark.ext.gfm.alerts.AlertsExtension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.heading.anchor.HeadingAnchorExtension;
import org.commonmark.ext.image.attributes.ImageAttributesExtension;
import org.commonmark.ext.ins.InsExtension;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Parses Markdown (CommonMark + GitHub flavoured extensions) into a {@link MarkdownDocument}.
 */
public final class MarkdownParser {

    /** Extensions shared by the parser and every renderer. */
    public static final List<Extension> EXTENSIONS = List.of(
            YamlFrontMatterExtension.create(),
            TablesExtension.create(),
            StrikethroughExtension.create(),
            InsExtension.create(),
            AlertsExtension.create(),
            AutolinkExtension.create(),
            TaskListItemsExtension.create(),
            HeadingAnchorExtension.create(),
            FootnotesExtension.builder().inlineFootnotes(true).build(),
            ImageAttributesExtension.create());

    private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();

    private MarkdownParser() {
    }

    public static MarkdownDocument parse(String markdown, Path baseDir, String name) {
        String text = markdown == null ? "" : markdown;
        if (!text.isEmpty() && text.charAt(0) == '﻿') {
            text = text.substring(1);
        }
        Node root = PARSER.parse(text);
        Map<String, List<String>> frontMatter = YamlFrontMatterVisitor.readData(root);
        Path base = baseDir != null ? baseDir.toAbsolutePath() : Path.of("").toAbsolutePath();
        return new MarkdownDocument(text, root, base, name == null ? "document" : name, frontMatter);
    }

    public static MarkdownDocument parse(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        Path abs = file.toAbsolutePath();
        return parse(text, abs.getParent(), baseName(abs.getFileName().toString()));
    }

    public static String baseName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
