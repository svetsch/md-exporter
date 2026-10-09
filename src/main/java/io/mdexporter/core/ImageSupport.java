package io.mdexporter.core;

import org.apache.batik.transcoder.TranscoderException;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.PNGTranscoder;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Set;

/** Converts arbitrary images into raster formats understood by Word and the PDF renderer. */
public final class ImageSupport {

    private static final Set<String> NATIVE = Set.of("image/png", "image/jpeg", "image/gif", "image/bmp");

    /**
     * A raster image.
     *
     * @param data        encoded bytes
     * @param mimeType    one of png / jpeg / gif / bmp
     * @param pixelWidth  width of the bitmap in pixels
     * @param pixelHeight height of the bitmap in pixels
     * @param scale       pixel density: logical size = pixel size / scale
     */
    public record Raster(byte[] data, String mimeType, int pixelWidth, int pixelHeight, double scale) {
        public double logicalWidth() {
            return pixelWidth / scale;
        }

        public double logicalHeight() {
            return pixelHeight / scale;
        }
    }

    private ImageSupport() {
    }

    /**
     * Returns a raster version of the resource. SVG is rasterised with Batik at {@code svgScale}, exotic formats are
     * re-encoded as PNG.
     */
    public static Raster toRaster(ResourceLoader.Resource resource, double svgScale) throws IOException {
        if (resource.isSvg()) {
            return rasterizeSvg(resource.data(), svgScale);
        }
        if (NATIVE.contains(resource.mimeType())) {
            int[] size = readSize(resource.data());
            return new Raster(resource.data(), resource.mimeType(), size[0], size[1], 1);
        }
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(resource.data()));
        if (image == null) {
            throw new IOException("Unsupported image format: " + resource.mimeType());
        }
        return new Raster(encodePng(image), "image/png", image.getWidth(), image.getHeight(), 1);
    }

    public static Raster fromPng(byte[] png, double scale) throws IOException {
        int[] size = readSize(png);
        return new Raster(png, "image/png", size[0], size[1], scale);
    }

    public static Raster rasterizeSvg(byte[] svg, double scale) throws IOException {
        try {
            byte[] natural = transcode(svg, null);
            int[] size = readSize(natural);
            if (scale <= 1.01) {
                return new Raster(natural, "image/png", size[0], size[1], 1);
            }
            byte[] hiRes = transcode(svg, (float) (size[0] * scale));
            int[] hiSize = readSize(hiRes);
            return new Raster(hiRes, "image/png", hiSize[0], hiSize[1], hiSize[0] / (double) size[0]);
        } catch (TranscoderException e) {
            throw new IOException("Cannot rasterize SVG: " + e.getMessage(), e);
        }
    }

    private static byte[] transcode(byte[] svg, Float width) throws TranscoderException {
        PNGTranscoder transcoder = new PNGTranscoder();
        if (width != null) {
            transcoder.addTranscodingHint(PNGTranscoder.KEY_WIDTH, width);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        transcoder.transcode(new TranscoderInput(new ByteArrayInputStream(svg)), new TranscoderOutput(out));
        return out.toByteArray();
    }

    /** Reads the pixel dimensions without decoding the whole image. */
    public static int[] readSize(byte[] data) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (readers.hasNext()) {
                ImageReader reader = readers.next();
                try {
                    reader.setInput(in);
                    return new int[]{reader.getWidth(0), reader.getHeight(0)};
                } finally {
                    reader.dispose();
                }
            }
        }
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(data));
        if (image == null) {
            throw new IOException("Unreadable image");
        }
        return new int[]{image.getWidth(), image.getHeight()};
    }

    public static byte[] encodePng(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
