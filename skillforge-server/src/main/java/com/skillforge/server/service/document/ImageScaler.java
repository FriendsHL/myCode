package com.skillforge.server.service.document;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * MULTIMODAL Wave 2-D / IMAGE-COMPRESSION: down-scale + re-encode large image
 * payloads before they're shipped to a vision LLM. Pure stdlib —
 * {@code javax.imageio} ships with the JDK, so no new Maven dependency.
 *
 * <p>Trigger criteria — caller invokes {@link #maybeCompress(byte[], String)};
 * the helper returns {@code null} when the input is already small enough
 * (long edge ≤ {@link #MAX_LONG_EDGE_PX} AND raw bytes ≤
 * {@link #COMPRESSION_THRESHOLD_BYTES}). Caller uses the original bytes in
 * that case and tags the attachment as {@code IMAGE_BLOCK_INLINE}.</p>
 *
 * <p>When compression runs, the output is always JPEG at quality
 * {@link #JPEG_QUALITY}. JPEG is the lowest-common-denominator format every
 * vision provider (Claude / GPT-4o / Gemini / Qwen) accepts, so we don't
 * need provider-specific branching here. The original file on disk is
 * <b>never modified</b> — only the in-memory provider request payload sees
 * the compressed form.</p>
 *
 * <p><b>Alpha channel:</b> JPEG doesn't carry transparency. We paint the
 * source onto a {@link BufferedImage#TYPE_INT_RGB} canvas with white
 * background fill before encoding, so a transparent PNG comes out with a
 * white backdrop rather than the default black that Java2D would otherwise
 * produce. Acceptable trade-off for vision input — transparency carries no
 * semantic value for the LLM here.</p>
 *
 * <p><b>Interpolation:</b> Bilinear (not Lanczos — unavailable in stdlib).
 * Quality is sufficient for chat thumbnails and vision input; we don't
 * pull in a third-party library for marginal sharpness gains on a hot
 * path.</p>
 *
 * <p><b>OOM defense:</b> hard refuses inputs over
 * {@link #MAX_INPUT_BYTES_SAFETY} (50 MB) before calling
 * {@link ImageIO#read} so a malicious / corrupted upload can't blow up the
 * JVM heap by claiming to be a huge image. Upstream {@code MAX_IMAGE_BYTES}
 * in {@code ChatAttachmentService} already caps uploads at 10 MB; the 50 MB
 * cap here is a cheap defense-in-depth.</p>
 */
public final class ImageScaler {

    /** Output long-edge target in pixels. Source images larger than this get scaled down. */
    public static final int MAX_LONG_EDGE_PX = 2048;

    /** Source byte size at or below which we skip compression entirely (when dims also fit). */
    public static final long COMPRESSION_THRESHOLD_BYTES = 1024L * 1024L;

    /** JPEG quality factor (0.0 worst – 1.0 best). 0.85 is the standard "good-looking" sweet spot. */
    public static final float JPEG_QUALITY = 0.85f;

    /** Hard cap on input size we'll even try to decode. Defense vs OOM on malicious uploads. */
    public static final int MAX_INPUT_BYTES_SAFETY = 50 * 1024 * 1024;

    /** Result envelope. {@code mimeType} is always {@code image/jpeg} on the compressed path. */
    public record CompressedImage(byte[] bytes, String mimeType) {}

    private ImageScaler() {
        // Static utility — no instances.
    }

    /**
     * Down-scale + JPEG re-encode the source bytes when they exceed either
     * {@link #MAX_LONG_EDGE_PX} on the long edge or
     * {@link #COMPRESSION_THRESHOLD_BYTES} raw size.
     *
     * @param bytes    source image bytes (PNG / JPEG / WebP — anything
     *                 {@link ImageIO#read} can decode).
     * @param mimeType source MIME hint, recorded into the IOException message
     *                 on failure to aid diagnostics. Detection of the actual
     *                 format is performed by {@link ImageIO#read} from the
     *                 bytes themselves; the hint is not authoritative.
     * @return a {@link CompressedImage} carrying the compressed JPEG bytes
     *         plus the {@code image/jpeg} MIME, or {@code null} when the
     *         input is already small enough and the caller should use the
     *         original bytes.
     * @throws IOException when the bytes can't be decoded, exceed the
     *         safety cap, or the JVM has no JPEG writer registered.
     */
    public static CompressedImage maybeCompress(byte[] bytes, String mimeType) throws IOException {
        if (bytes == null || bytes.length == 0) {
            throw new IOException("ImageScaler: input bytes are null or empty (mime=" + mimeType + ")");
        }
        if (bytes.length > MAX_INPUT_BYTES_SAFETY) {
            throw new IOException(
                    "ImageScaler: input bytes exceed 50MB safety cap (size=" + bytes.length
                            + ", mime=" + mimeType + ")");
        }

        BufferedImage src = ImageIO.read(new ByteArrayInputStream(bytes));
        if (src == null) {
            // ImageIO.read returns null for unsupported / corrupted formats — never throws.
            // Caller maps this to MODE / errorCode = IMAGE_COMPRESSION_FAILED and falls back.
            throw new IOException(
                    "ImageScaler: unsupported or corrupted image bytes (mime=" + mimeType + ")");
        }

        int w = src.getWidth();
        int h = src.getHeight();
        int longEdge = Math.max(w, h);

        // Short-circuit: if input already fits both budgets, skip the work entirely.
        if (longEdge <= MAX_LONG_EDGE_PX && bytes.length <= COMPRESSION_THRESHOLD_BYTES) {
            return null;
        }

        double scale = Math.min(1.0, (double) MAX_LONG_EDGE_PX / (double) longEdge);
        int newW = Math.max(1, (int) Math.round(w * scale));
        int newH = Math.max(1, (int) Math.round(h * scale));

        BufferedImage scaled = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            // White background BEFORE drawing — drops alpha channel safely.
            // Without this, transparent PNG → black background when drawn onto
            // TYPE_INT_RGB. White matches what every chat UI also shows.
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, newW, newH);
            g.drawImage(src, 0, 0, newW, newH, null);
        } finally {
            g.dispose();
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(
                Math.min(bytes.length, 1 << 20));
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByMIMEType("image/jpeg");
        if (!writers.hasNext()) {
            throw new IOException(
                    "ImageScaler: no JPEG writer registered in this JVM (mime=" + mimeType + ")");
        }
        ImageWriter writer = writers.next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(out)) {
                writer.setOutput(stream);
                writer.write(null, new IIOImage(scaled, null, null), param);
            }
        } finally {
            // Releases native resources held by the writer — must run on the
            // success path AND the exception path.
            writer.dispose();
        }
        return new CompressedImage(out.toByteArray(), "image/jpeg");
    }
}
