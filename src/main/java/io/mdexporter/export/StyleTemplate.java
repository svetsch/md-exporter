package io.mdexporter.export;

import io.mdexporter.core.DocumentStyle;
import io.mdexporter.core.ExportTheme;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fills the theme placeholders of the stylesheets and of the Word styles:
 * {@code {{text}}}, {@code {{accent}}}... (colours as {@code RRGGBB}), {@code {{bodyCss}}} / {@code {{bodyWord}}}
 * (fonts), and sizes scaled by the text size: {@code {{pt:10.5}}}, {@code {{px:16}}}, {@code {{hp:22}}} (Word half
 * points).
 */
final class StyleTemplate {

    private static final Logger LOG = Logger.getLogger(StyleTemplate.class.getName());
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)(?::([0-9.]+))?}}");

    private StyleTemplate() {
    }

    static String render(String template, DocumentStyle style) {
        Map<String, String> vars = variables(style);
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder(template.length() + 256);
        while (m.find()) {
            String name = m.group(1);
            String value;
            if (m.group(2) != null) {
                double v = Double.parseDouble(m.group(2)) * style.scale();
                value = switch (name) {
                    case "pt" -> number(v) + "pt";
                    case "px" -> number(v) + "px";
                    case "hp" -> Long.toString(Math.max(2, Math.round(v)));
                    default -> throw new IllegalArgumentException("Unknown size unit " + name);
                };
            } else {
                value = vars.get(name);
                if (value == null) {
                    throw new IllegalArgumentException("Unknown style placeholder " + name);
                }
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static Map<String, String> variables(DocumentStyle style) {
        ExportTheme.Palette p = style.palette();
        Map<String, String> vars = new HashMap<>();
        vars.put("text", p.text());
        vars.put("muted", p.muted());
        vars.put("border", p.border());
        vars.put("subtle", p.subtle());
        vars.put("background", p.background());
        vars.put("accent", p.accent());
        vars.put("heading", p.heading());
        vars.put("codeBg", p.codeBg());
        vars.put("tableHeader", p.tableHeader());
        vars.put("bodyCss", style.bodyFont().cssStack());
        vars.put("headingCss", style.headingFont().cssStack());
        vars.put("codeCss", style.codeFont().cssStack());
        vars.put("bodyWord", style.bodyFont().displayName());
        vars.put("headingWord", style.headingFont().displayName());
        vars.put("codeWord", style.codeFont().displayName());
        // Word only
        vars.put("normalPPr", style.justify() ? "<w:pPr><w:jc w:val=\"both\"/></w:pPr>" : "");
        vars.put("h1Rule", style.headingRules()
                ? "<w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\" w:space=\"2\" w:color=\"" + p.border()
                + "\"/></w:pBdr>" : "");
        vars.put("h2Rule", style.headingRules()
                ? "<w:pBdr><w:bottom w:val=\"single\" w:sz=\"4\" w:space=\"2\" w:color=\"" + p.border()
                + "\"/></w:pBdr>" : "");
        return vars;
    }

    private static String number(double v) {
        String s = String.format(Locale.ROOT, "%.2f", v);
        return s.replaceFirst("\\.?0+$", "");
    }

    /** Rules depending on theme switches, appended after the stylesheet. */
    static String extraCss(DocumentStyle style, boolean pdf) {
        StringBuilder css = new StringBuilder();
        if (!style.headingRules()) {
            css.append("h1, h2 { border-bottom: none; padding-bottom: 0; }\n");
        }
        if (style.justify()) {
            css.append("p, li, dd, blockquote, .footnotes li { text-align: justify; }\n");
        }
        if (style.isDark()) {
            // diagrams are drawn for a light background
            css.append(pdf
                    ? "figure.diagram { background-color: #ffffff; padding: 6pt; }\n"
                    : "figure.diagram { background: #ffffff; padding: 12px; border-radius: 6px; }\n"
                    + ".diagram-error pre { background: #ffffff; }\n"
                    + "img { background: transparent; }\n");
        }
        return css.toString();
    }

    /** The style's own CSS, then the user's CSS file (if any and readable). */
    static String customCss(DocumentStyle style) {
        String own = style.styleCss().isBlank() ? ""
                : "\n/* style: " + style.definition().name().replace("*/", "") + " */\n" + style.styleCss();
        if (style.customCss() == null) {
            return own;
        }
        Path file = Path.of(style.customCss());
        try {
            return own + "\n/* " + file.getFileName() + " */\n" + Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            LOG.warning("Cannot read custom CSS " + file + ": " + e);
            return own;
        }
    }
}
