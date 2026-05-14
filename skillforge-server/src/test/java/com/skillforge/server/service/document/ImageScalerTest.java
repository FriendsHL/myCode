package com.skillforge.server.service.document;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MULTIMODAL Wave 2-D / IMAGE-COMPRESSION — unit tests for the stateless
 * {@link ImageScaler#maybeCompress} helper. We build PNG fixtures
 * programmatically with {@link ImageIO#write} so no binary blobs need to live
 * in the repo.
 */
class ImageScalerTest {

    @Test
    @DisplayName("tiny image (800x600 solid color PNG under 1MB) → returns null, caller uses original")
    void tinyImage_returnsNull() throws IOException {
        byte[] bytes = solidPng(800, 600, Color.WHITE);
        // Sanity guard: this fixture must actually be under the byte threshold,
        // otherwise we'd be testing the "compressed" path here by accident.
        assertThat(bytes.length).isLessThan((int) ImageScaler.COMPRESSION_THRESHOLD_BYTES);

        ImageScaler.CompressedImage result = ImageScaler.maybeCompress(bytes, "image/png");

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("oversized dimension (4096x3000 PNG) → long edge scaled to 2048, output is JPEG")
    void oversizedDimension_returnsCompressed() throws IOException {
        byte[] bytes = solidPng(4096, 3000, Color.WHITE);

        ImageScaler.CompressedImage result = ImageScaler.maybeCompress(bytes, "image/png");

        assertThat(result).isNotNull();
        assertThat(result.mimeType()).isEqualTo("image/jpeg");
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(result.bytes()));
        assertThat(decoded).as("compressed JPEG must be re-decodable").isNotNull();
        assertThat(Math.max(decoded.getWidth(), decoded.getHeight()))
                .isEqualTo(ImageScaler.MAX_LONG_EDGE_PX);
        // Aspect ratio preserved (4096:3000 ≈ 1.365 → 2048 long edge / 1500 short).
        assertThat(decoded.getHeight()).isEqualTo(1500);
    }

    @Test
    @DisplayName("oversized bytes (1024x1024 noisy PNG > 1MB) → compressed even though dims fit budget")
    void oversizedBytes_returnsCompressed() throws IOException {
        byte[] bytes = noisyPng(1024, 1024);
        // A 1024x1024 random-noise PNG can't compress well; should comfortably exceed 1MB.
        assertThat(bytes.length).isGreaterThan((int) ImageScaler.COMPRESSION_THRESHOLD_BYTES);

        ImageScaler.CompressedImage result = ImageScaler.maybeCompress(bytes, "image/png");

        assertThat(result).isNotNull();
        assertThat(result.mimeType()).isEqualTo("image/jpeg");
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(result.bytes()));
        assertThat(decoded).isNotNull();
        // Dimensions already fit under the long-edge target, so no scaling — the
        // compression is purely the JPEG re-encode (with quality 0.85).
        assertThat(decoded.getWidth()).isEqualTo(1024);
        assertThat(decoded.getHeight()).isEqualTo(1024);
        assertThat(result.bytes().length).isLessThan(bytes.length);
    }

    @Test
    @DisplayName("small image not upscaled (400x300 → returns null, never grows to 2048)")
    void noUpscale() throws IOException {
        byte[] bytes = solidPng(400, 300, Color.RED);

        ImageScaler.CompressedImage result = ImageScaler.maybeCompress(bytes, "image/png");

        // Confirms the Math.min(1.0, scale) clamp keeps us from upscaling small images.
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("unsupported / corrupted bytes → throws IOException so caller can fall back")
    void unsupportedBytes_throwsIOException() {
        byte[] random = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14};

        assertThatThrownBy(() -> ImageScaler.maybeCompress(random, "image/png"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("unsupported or corrupted");
    }

    @Test
    @DisplayName(">50MB input → refused before ImageIO.read (OOM defense)")
    void over50mb_throwsIOException() {
        byte[] huge = new byte[51 * 1024 * 1024];
        // No need to put real magic bytes — the guard fires on size, BEFORE
        // ImageIO.read could even try to decode (which would itself OOM).

        assertThatThrownBy(() -> ImageScaler.maybeCompress(huge, "image/png"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("50MB");
    }

    @Test
    @DisplayName("null / empty bytes → throws IOException with a stable diagnostic message")
    void emptyBytes_throwsIOException() {
        assertThatThrownBy(() -> ImageScaler.maybeCompress(null, "image/png"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("null or empty");
        assertThatThrownBy(() -> ImageScaler.maybeCompress(new byte[0], "image/png"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("null or empty");
    }

    // ─── helpers ───

    /** A small PNG of solid colour. Encodes to a few hundred bytes — comfortably under all thresholds. */
    private static byte[] solidPng(int w, int h, Color fill) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(fill);
            g.fillRect(0, 0, w, h);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    /**
     * A PNG with random per-pixel colors. Random data is essentially
     * incompressible so a 1024x1024 image lands around 3MB raw — guarantees
     * we exceed the 1MB byte threshold and exercise the "oversized bytes
     * even though dims fit" code path.
     */
    private static byte[] noisyPng(int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Random rnd = new Random(42);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, rnd.nextInt(0xFFFFFF));
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
