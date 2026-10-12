package io.mdexporter.settings;

import io.mdexporter.ai.AiAgent;
import io.mdexporter.ai.AiProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsStoreTest {

    @TempDir
    Path dir;

    @Test
    void fileStoreRoundTripsSpecialCharacters() {
        Path file = dir.resolve("sub/settings.properties");
        SettingsStore store = new FileStore(SettingsLocation.USER_FILE, file);
        store.put("recent", "C:\\docs\\a b.md\nC:\\x=y:z#.md");
        store.put("name", " äöü 日本 ");
        store.putDouble("winW", 1280.5);
        store.putBoolean("wrap", false);
        assertTrue(Files.isRegularFile(file));

        SettingsStore reloaded = new FileStore(SettingsLocation.USER_FILE, file);
        assertEquals("C:\\docs\\a b.md\nC:\\x=y:z#.md", reloaded.get("recent", null));
        assertEquals(" äöü 日本 ", reloaded.get("name", null));
        assertEquals(1280.5, reloaded.getDouble("winW", 0));
        assertFalse(reloaded.getBoolean("wrap", true));
        reloaded.remove("name");
        assertEquals("-", new FileStore(SettingsLocation.USER_FILE, file).get("name", "-"));
    }

    @Test
    void copiesAndClearsStores() {
        SettingsStore a = new FileStore(SettingsLocation.USER_FILE, dir.resolve("a.properties"));
        SettingsStore b = new FileStore(SettingsLocation.PORTABLE_FILE, dir.resolve("b.properties"));
        a.put("k1", "v1");
        a.put("k2", "v2");
        a.copyTo(b);
        a.clear();
        assertFalse(Files.exists(dir.resolve("a.properties")));
        assertEquals("v2", new FileStore(SettingsLocation.PORTABLE_FILE, dir.resolve("b.properties")).get("k2", null));
    }

    @Test
    void persistsAiAgents() {
        SettingsStore store = new FileStore(SettingsLocation.USER_FILE, dir.resolve("ai.properties"));
        AiAgent claude = AiAgent.defaults(AiProvider.ANTHROPIC);
        AiAgent local = new AiAgent(null, "Local", AiProvider.OPENAI_COMPATIBLE, "http://localhost:1234/v1", "qwen",
                "", 4096, "", false);
        AiAgent.save(store, List.of(claude, local));
        List<AiAgent> loaded = AiAgent.load(store);
        assertEquals(List.of(claude, local), loaded);
        assertEquals("claude-opus-5-5", loaded.get(0).model());
        AiAgent.save(store, List.of(local));
        assertEquals(List.of(local), AiAgent.load(store));
        assertFalse(store.keys().contains("ai.agent.1.name"));
    }
}
