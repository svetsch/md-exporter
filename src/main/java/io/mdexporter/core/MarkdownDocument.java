package io.mdexporter.core;

import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Heading;
import org.commonmark.node.Node;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * A parsed Markdown document together with the location used to resolve relative resources.
 *
 * @param source      the raw Markdown text
 * @param root        the CommonMark AST
 * @param baseDir     directory used to resolve relative image paths
 * @param name        document name without extension (used for default output file names)
 * @param frontMatter YAML front matter key/values (may be empty)
 */
public record MarkdownDocument(String source, Node root, Path baseDir, String name,
                               Map<String, List<String>> frontMatter) {

    /** Title from the front matter, else the first level-1 heading, else the document name. */
    public String title() {
        List<String> fm = frontMatter.get("title");
        if (fm != null && !fm.isEmpty() && !fm.get(0).isBlank()) {
            return fm.get(0).trim();
        }
        String[] found = new String[1];
        root.accept(new AbstractVisitor() {
            @Override
            public void visit(Heading heading) {
                if (found[0] == null && heading.getLevel() == 1) {
                    found[0] = Nodes.textContent(heading).trim();
                }
            }
        });
        return found[0] != null && !found[0].isEmpty() ? found[0] : name;
    }

    public String frontMatterValue(String key) {
        List<String> values = frontMatter.get(key);
        return values == null || values.isEmpty() ? null : String.join(", ", values);
    }
}
