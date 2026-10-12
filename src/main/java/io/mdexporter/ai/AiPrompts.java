package io.mdexporter.ai;

import java.util.List;

/** Instructions offered in the UI and the prompts sent to the model. */
public final class AiPrompts {

    /**
     * A ready-made instruction.
     *
     * @param label       menu text
     * @param instruction what the model is asked to do
     * @param generates   true when the answer is new text to insert (summary, continuation) rather than a rewrite
     */
    public record Preset(String label, String instruction, boolean generates) {
        @Override
        public String toString() {
            return label;
        }
    }

    public static final List<Preset> PRESETS = List.of(
            new Preset("Improve Writing", "Improve the writing: clarity, flow and word choice. Keep the meaning, "
                    + "the tone and the Markdown structure.", false),
            new Preset("Fix Spelling & Grammar", "Fix spelling, grammar and punctuation mistakes only. Do not "
                    + "rephrase anything else.", false),
            new Preset("Make Shorter", "Make the text more concise (about half the length) without losing "
                    + "important information.", false),
            new Preset("Make Longer", "Expand the text with more detail and explanation, in the same style.", false),
            new Preset("Simplify", "Rewrite the text in simpler words and shorter sentences.", false),
            new Preset("More Formal", "Rewrite the text in a more formal, professional tone.", false),
            new Preset("More Casual", "Rewrite the text in a more casual, friendly tone.", false),
            new Preset("Translate to English", "Translate the text to English. Keep the Markdown formatting, "
                    + "code and URLs unchanged.", false),
            new Preset("Translate to German", "Translate the text to German. Keep the Markdown formatting, "
                    + "code and URLs unchanged.", false),
            new Preset("Translate to French", "Translate the text to French. Keep the Markdown formatting, "
                    + "code and URLs unchanged.", false),
            new Preset("Format as Table", "Restructure the information as a Markdown table where it makes "
                    + "sense.", false),
            new Preset("Add Mermaid Diagram", "Write a Mermaid diagram (in a ```mermaid code block) illustrating "
                    + "the text.", true),
            new Preset("Summarize", "Write a short summary of the text.", true),
            new Preset("Continue Writing", "Continue the text with one or two paragraphs in the same style.", true));

    private static final String SYSTEM = """
            You are a writing assistant built into a Markdown editor. You change or extend Markdown documents as \
            instructed.
            Answer with the resulting Markdown text only: no introduction, no explanation, no comment about \
            what you changed, and do not wrap the answer in a code fence.
            Keep the Markdown structure (headings, lists, tables, links, images, footnotes, code blocks, Mermaid \
            and PlantUML diagrams, YAML front matter) unless the instruction asks to change it.
            Keep the language of the original text unless the instruction asks for a translation.""";

    private AiPrompts() {
    }

    public static String system() {
        return SYSTEM;
    }

    /**
     * Builds the user message.
     *
     * @param document    the whole document
     * @param selection   the selected part, or {@code null} to work on the whole document
     * @param withContext send the whole document as context when working on a selection
     * @param instruction what to do
     * @param generates   the answer is new text to insert
     */
    public static String user(String document, String selection, boolean withContext, String instruction,
                              boolean generates) {
        StringBuilder b = new StringBuilder();
        if (selection == null) {
            b.append("<document>\n").append(document).append("\n</document>\n\n");
            b.append("Instruction: ").append(instruction.strip()).append("\n\n");
            b.append(generates
                    ? "Answer with the new Markdown text only."
                    : "Answer with the complete revised document.");
            return b.toString();
        }
        if (withContext) {
            b.append("The whole document, for context:\n<document>\n").append(document).append("\n</document>\n\n");
        }
        b.append("The part of the document to work on:\n<selection>\n").append(selection).append("\n</selection>\n\n");
        b.append("Instruction: ").append(instruction.strip()).append("\n\n");
        b.append(generates
                ? "Answer with the new Markdown text only (it will be inserted after the selection)."
                : "Answer with the replacement for the selection only.");
        return b.toString();
    }

    private static final String STYLE_SYSTEM = """
            You design document styles for a Markdown exporter that produces Word, HTML and PDF files. A style is \
            a JSON object with exactly this shape:
            {
              "name": "short name",
              "description": "one sentence",
              "colors": { "text": "#RRGGBB", "muted": "#RRGGBB", "border": "#RRGGBB", "subtle": "#RRGGBB",
                          "background": "#RRGGBB", "accent": "#RRGGBB", "heading": "#RRGGBB",
                          "codeBackground": "#RRGGBB", "tableHeader": "#RRGGBB" },
              "fonts": { "text": FONT, "headings": FONT, "code": CODE_FONT },
              "headingRules": true or false,
              "coloredHeadings": true or false,
              "dark": true or false,
              "css": "optional extra CSS, or an empty string"
            }
            Colours: text = body text; muted = quotes, captions, footnotes; border = rules and table borders; \
            subtle = code block and table stripe background; background = page; accent = links; heading = \
            headings; codeBackground = inline code; tableHeader = table header cells. Keep a readable contrast \
            (at least 4.5:1 for text on background). "dark": true only for a dark background (Word documents \
            then keep a white page).
            FONT is one of: %s. CODE_FONT is one of: %s.
            headingRules draws lines under level 1 and 2 headings; coloredHeadings makes headings use the accent \
            colour.
            The optional css is added after the generated stylesheet, for HTML, PDF and the preview. Only use it \
            for what the fields cannot express (e.g. letter-spacing, uppercase headings, heading sizes, borders, \
            blockquote decoration). It must work in plain CSS 2.1 because the PDF renderer does not support CSS \
            variables, flexbox, grid or web fonts. Never use url(), @import or external resources. The document \
            HTML uses standard elements (h1-h6, p, a, ul, ol, li, blockquote, table, th, td, pre, code, hr, img) \
            plus the classes doc-header, doc-title, doc-subtitle, doc-meta, markdown-alert, footnotes and \
            figure.diagram.
            Answer with the JSON object only, no explanation and no code fence.""";

    /** System prompt of the style generator, listing the available fonts. */
    public static String styleSystem(List<String> textFonts, List<String> codeFonts) {
        return STYLE_SYSTEM.formatted(String.join(", ", textFonts), String.join(", ", codeFonts));
    }

    /**
     * @param request      what the user wants
     * @param currentStyle the style being edited (JSON), used as starting point; {@code null} to start afresh
     */
    public static String styleUser(String request, String currentStyle) {
        StringBuilder b = new StringBuilder();
        if (currentStyle != null) {
            b.append("The current style, to be modified:\n").append(currentStyle).append("\n\n");
            b.append("Change it as follows (keep everything else): ");
        } else {
            b.append("Create a style: ");
        }
        return b.append(request.strip()).toString();
    }

    /** Removes a code fence wrapping the whole answer (some models add one despite the instructions). */
    public static String clean(String answer, String original) {
        String a = answer.strip();
        boolean originalFenced = original != null && original.strip().startsWith("```");
        if (!originalFenced && a.startsWith("```") && a.endsWith("```") && a.length() > 6) {
            int firstLine = a.indexOf('\n');
            String info = firstLine < 0 ? "" : a.substring(3, firstLine).trim().toLowerCase();
            if (firstLine > 0 && (info.isEmpty() || info.equals("markdown") || info.equals("md"))
                    && a.indexOf("\n```", firstLine) == a.length() - 4) {
                a = a.substring(firstLine + 1, a.length() - 3).stripTrailing();
            }
        }
        // keep the trailing line break of the original selection
        if (original != null && original.endsWith("\n") && !a.endsWith("\n")) {
            a += "\n";
        }
        return a;
    }
}
