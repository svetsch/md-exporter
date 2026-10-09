package io.mdexporter.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceLoaderTest {

    @TempDir
    Path dir;

    @Test
    void detectsMimeTypesFromContent() {
        assertEquals("image/png", ResourceLoader.detectMime(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0, 0, 0, 0}, "x", null));
        assertEquals("image/jpeg", ResourceLoader.detectMime(new byte[]{(byte) 0xFF, (byte) 0xD8, 0}, "x", null));
        assertEquals("image/svg+xml", ResourceLoader.detectMime(
                "<?xml version=\"1.0\"?><svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.UTF_8),
                "x", null));
    }

    @Test
    void loadsRelativeAndEncodedPaths() throws Exception {
        Files.createDirectories(dir.resolve("my images"));
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\"/>".getBytes(StandardCharsets.UTF_8);
        Files.write(dir.resolve("my images/a.svg"), svg);
        ResourceLoader loader = new ResourceLoader(dir);
        assertArrayEquals(svg, loader.load("my images/a.svg").orElseThrow().data());
        assertArrayEquals(svg, loader.load("my%20images/a.svg").orElseThrow().data());
        assertTrue(loader.load("missing.png").isEmpty());
    }

    @Test
    void decodesDataUris() {
        ResourceLoader loader = new ResourceLoader(dir);
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"/>";
        String uri = "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(svg.getBytes(StandardCharsets.UTF_8));
        ResourceLoader.Resource r = loader.load(uri).orElseThrow();
        assertEquals("image/svg+xml", r.mimeType());
        assertEquals(svg, new String(r.data(), StandardCharsets.UTF_8));
    }

    @Test
    void rasterizesSvg() throws Exception {
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"40\" height=\"20\"><rect width=\"40\" height=\"20\" fill=\"red\"/></svg>"
                .getBytes(StandardCharsets.UTF_8);
        ImageSupport.Raster raster = ImageSupport.rasterizeSvg(svg, 2);
        assertEquals(80, raster.pixelWidth());
        assertEquals(40, raster.logicalWidth(), 0.5);
    }
}
