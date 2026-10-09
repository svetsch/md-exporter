package io.mdexporter.core;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loads images referenced by a document: relative/absolute paths, {@code file:}, {@code http(s):} and {@code data:} URIs.
 */
public final class ResourceLoader {

    private static final Logger LOG = Logger.getLogger(ResourceLoader.class.getName());
    private static final long MAX_DOWNLOAD = 50L * 1024 * 1024;

    /** A loaded binary resource. */
    public record Resource(byte[] data, String mimeType, String fileName) {
        public boolean isSvg() {
            return "image/svg+xml".equals(mimeType);
        }

        public String extension() {
            return switch (mimeType) {
                case "image/png" -> "png";
                case "image/jpeg" -> "jpg";
                case "image/gif" -> "gif";
                case "image/bmp" -> "bmp";
                case "image/webp" -> "webp";
                case "image/svg+xml" -> "svg";
                default -> "bin";
            };
        }

        public String toDataUri() {
            return "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(data);
        }
    }

    private final Path baseDir;
    private final Map<String, Optional<Resource>> cache = new ConcurrentHashMap<>();
    private volatile HttpClient http;

    public ResourceLoader(Path baseDir) {
        this.baseDir = baseDir;
    }

    public Path baseDir() {
        return baseDir;
    }

    /** Loads a resource, returning empty (and logging) if it cannot be read. */
    public Optional<Resource> load(String src) {
        if (src == null || src.isBlank()) {
            return Optional.empty();
        }
        return cache.computeIfAbsent(src.trim(), this::doLoad);
    }

    private Optional<Resource> doLoad(String src) {
        try {
            if (src.regionMatches(true, 0, "data:", 0, 5)) {
                return Optional.of(decodeDataUri(src));
            }
            if (isRemote(src)) {
                return Optional.of(download(src));
            }
            Path path = resolveLocal(src);
            if (path == null || !Files.isRegularFile(path)) {
                LOG.warning("Image not found: " + src);
                return Optional.empty();
            }
            byte[] data = Files.readAllBytes(path);
            String name = path.getFileName().toString();
            return Optional.of(new Resource(data, detectMime(data, name, null), name));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Cannot load image " + abbreviate(src) + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Resolves a reference to an absolute URI usable by a browser (for the live preview).
     * Remote and data URIs are returned unchanged.
     */
    public String toBrowserUri(String src) {
        if (src == null || src.isBlank() || isRemote(src) || src.regionMatches(true, 0, "data:", 0, 5)) {
            return src;
        }
        Path path = resolveLocal(src);
        return path != null ? path.toUri().toString() : src;
    }

    public static boolean isRemote(String src) {
        String s = src.toLowerCase(Locale.ROOT);
        return s.startsWith("http://") || s.startsWith("https://");
    }

    /** Resolves a local reference (relative path, absolute path or file: URI). */
    public Path resolveLocal(String src) {
        try {
            if (src.regionMatches(true, 0, "file:", 0, 5)) {
                return Path.of(URI.create(src));
            }
            String clean = stripQueryAndFragment(src);
            Path candidate = resolveAgainstBase(clean);
            if (candidate != null && Files.exists(candidate)) {
                return candidate;
            }
            String decoded = URLDecoder.decode(clean.replace("+", "%2B"), StandardCharsets.UTF_8);
            Path decodedPath = resolveAgainstBase(decoded);
            return decodedPath != null && Files.exists(decodedPath) ? decodedPath : candidate;
        } catch (IllegalArgumentException e) { // includes InvalidPathException
            return null;
        }
    }

    private Path resolveAgainstBase(String value) {
        try {
            Path p = Path.of(value);
            return p.isAbsolute() ? p.normalize() : baseDir.resolve(p).normalize();
        } catch (InvalidPathException e) {
            return null;
        }
    }

    private static String stripQueryAndFragment(String src) {
        int cut = src.length();
        int q = src.indexOf('?');
        int h = src.indexOf('#');
        if (q >= 0) {
            cut = q;
        }
        if (h >= 0 && h < cut) {
            cut = h;
        }
        return src.substring(0, cut);
    }

    private Resource download(String url) throws IOException, InterruptedException {
        HttpClient client = http;
        if (client == null) {
            synchronized (this) {
                if (http == null) {
                    http = HttpClient.newBuilder()
                            .followRedirects(HttpClient.Redirect.NORMAL)
                            .connectTimeout(Duration.ofSeconds(10))
                            .build();
                }
                client = http;
            }
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "md-exporter/1.0")
                .GET().build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + response.statusCode());
        }
        byte[] body = response.body();
        if (body.length > MAX_DOWNLOAD) {
            throw new IOException("image too large");
        }
        String name = url.substring(url.lastIndexOf('/') + 1);
        String contentType = response.headers().firstValue("Content-Type").orElse(null);
        return new Resource(body, detectMime(body, stripQueryAndFragment(name), contentType), name);
    }

    private static Resource decodeDataUri(String uri) {
        int comma = uri.indexOf(',');
        if (comma < 0) {
            throw new IllegalArgumentException("malformed data URI");
        }
        String meta = uri.substring(5, comma);
        String payload = uri.substring(comma + 1);
        boolean base64 = meta.toLowerCase(Locale.ROOT).endsWith(";base64");
        byte[] data = base64
                ? Base64.getMimeDecoder().decode(payload)
                : URLDecoder.decode(payload, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8);
        String declared = meta.split(";")[0];
        return new Resource(data, detectMime(data, "", declared.isEmpty() ? null : declared), "embedded");
    }

    /** Detects an image MIME type from magic bytes, falling back to the extension / declared type. */
    public static String detectMime(byte[] d, String fileName, String declared) {
        if (d.length >= 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') {
            return "image/png";
        }
        if (d.length >= 3 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8) {
            return "image/jpeg";
        }
        if (d.length >= 4 && d[0] == 'G' && d[1] == 'I' && d[2] == 'F' && d[3] == '8') {
            return "image/gif";
        }
        if (d.length >= 2 && d[0] == 'B' && d[1] == 'M') {
            return "image/bmp";
        }
        if (d.length >= 12 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F'
                && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P') {
            return "image/webp";
        }
        String head = new String(d, 0, Math.min(d.length, 2048), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        if (head.contains("<svg")) {
            return "image/svg+xml";
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (declared != null && !declared.isBlank()) {
            return declared.split(";")[0].trim().toLowerCase(Locale.ROOT);
        }
        return "application/octet-stream";
    }

    private static String abbreviate(String s) {
        return s.length() > 80 ? s.substring(0, 77) + "..." : s;
    }
}
