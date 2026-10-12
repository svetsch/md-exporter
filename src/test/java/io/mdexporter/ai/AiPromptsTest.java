package io.mdexporter.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiPromptsTest {

    @Test
    void removesWrappingFences() {
        assertEquals("# Title\n\nText", AiPrompts.clean("```markdown\n# Title\n\nText\n```", "# T"));
        assertEquals("Text\n", AiPrompts.clean("Text", "Old\n"));
        String code = "```java\nint x;\n```";
        assertEquals(code, AiPrompts.clean(code, "```java\nint y;\n```"));
        assertEquals(code, AiPrompts.clean(code, "text"), "a code answer that is not a Markdown wrapper stays");
    }

    @Test
    void buildsSelectionPrompts() {
        String prompt = AiPrompts.user("doc", "sel", true, "Fix it", false);
        assertTrue(prompt.contains("<document>\ndoc\n</document>"));
        assertTrue(prompt.contains("<selection>\nsel\n</selection>"));
        assertTrue(prompt.endsWith("Answer with the replacement for the selection only."));
        assertFalse(AiPrompts.user("doc", "sel", false, "Fix it", false).contains("<document>"));
        assertTrue(AiPrompts.user("doc", null, true, "Fix it", false).endsWith("complete revised document."));
    }
}
