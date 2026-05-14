package com.skillforge.server.service.document;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Wave 2 PDF-SCAN-FALLBACK utility — render the leading pages of a PDF document
 * to PNG byte arrays for vision-model consumption when text extraction yielded
 * insufficient content (typical for scanned PDFs).
 *
 * <p>Stateless / static API on purpose: no Spring deps, no caching. Each call
 * opens the document, renders, closes. PDFRenderer is memory-hungry; callers
 * MUST bound {@code maxPages} (we recommend ≤5) and {@code dpi} (≤150) to keep
 * RAM bounded — see {@link com.skillforge.server.service.ChatAttachmentService}
 * constants {@code MAX_PDF_PAGE_IMAGES} and {@code PDF_RENDER_DPI}.</p>
 *
 * <p><b>Per-page size cap</b>: the first encode runs at the requested DPI. If
 * the resulting PNG exceeds 1MB, the page is re-rendered once at the cheaper
 * fallback DPI ({@value #FALLBACK_DPI}) and re-encoded. We deliberately do NOT
 * loop a retry ladder — one fallback is enough for the worst common cases (A4
 * 300dpi → 1600px long-edge cap) and the simpler code path is easier to reason
 * about than complex retry / progressive degradation.</p>
 *
 * <p><b>Encrypted PDFs throw</b>: {@link PDDocument#isEncrypted()} → IOException.
 * Vision providers can't read encrypted bytes either, so we fail loudly here
 * rather than silently emit garbage.</p>
 */
public final class PdfPageImageRenderer {

    private static final Logger log = LoggerFactory.getLogger(PdfPageImageRenderer.class);

    /**
     * Single-shot fallback DPI when the primary render at the requested DPI
     * encodes to a PNG larger than {@link #MAX_BYTES_PER_PAGE}. Picked at 100
     * because the typical caller passes 150 → 100 ≈ −56% pixels which is the
     * cheapest knob to keep most renders under 1MB without further retries.
     */
    private static final float FALLBACK_DPI = 100f;

    /** Per-page hard ceiling after PNG encoding. */
    private static final int MAX_BYTES_PER_PAGE = 1024 * 1024;

    /**
     * When the fallback-DPI re-render is still oversized, additionally scale
     * the BufferedImage so its long edge is at most this many pixels before
     * the final encode. Belt-and-suspenders for huge page geometries.
     */
    private static final int MAX_LONG_EDGE_PX = 1600;

    private PdfPageImageRenderer() {
        // util — no instances
    }

    /**
     * Render the first {@code maxPages} pages of {@code path} to PNG byte arrays
     * at {@code dpi}. Returns at most {@code maxPages} entries; never null entries.
     * If the document has fewer pages, returns one entry per page.
     *
     * @throws IOException on parse / render failure, or when the PDF is encrypted
     */
    public static List<byte[]> renderFirstPages(Path path, int maxPages, float dpi) throws IOException {
        if (path == null) throw new IllegalArgumentException("path must not be null");
        if (maxPages <= 0) throw new IllegalArgumentException("maxPages must be > 0");
        if (dpi <= 0f) throw new IllegalArgumentException("dpi must be > 0");

        PDDocument doc;
        try {
            // Two-step open: PDFs with a non-empty user password throw
            // InvalidPasswordException out of Loader.loadPDF; PDFs with only an
            // owner password load fine but expose isEncrypted()=true. We treat
            // both as the same "vision providers can't help here either" case.
            doc = Loader.loadPDF(path.toFile());
        } catch (InvalidPasswordException e) {
            throw new IOException("encrypted PDF (password required)", e);
        }
        try (doc) {
            if (doc.isEncrypted()) {
                throw new IOException("encrypted PDF");
            }
            int total = doc.getNumberOfPages();
            int budget = Math.min(maxPages, total);
            if (budget <= 0) {
                return List.of();
            }
            PDFRenderer renderer = new PDFRenderer(doc);
            List<byte[]> out = new ArrayList<>(budget);
            for (int i = 0; i < budget; i++) {
                byte[] png = renderOnePage(renderer, i, dpi);
                out.add(png);
            }
            return out;
        }
    }

    /**
     * Render one page and encode as PNG. If the encoded size exceeds
     * {@link #MAX_BYTES_PER_PAGE}, re-render once at {@link #FALLBACK_DPI} and
     * (if still oversized) scale the BufferedImage so its long edge is at most
     * {@link #MAX_LONG_EDGE_PX} pixels before the final encode.
     */
    private static byte[] renderOnePage(PDFRenderer renderer, int pageIndex, float dpi) throws IOException {
        BufferedImage img = renderer.renderImageWithDPI(pageIndex, dpi, ImageType.RGB);
        byte[] png = encodePng(img);
        if (png.length <= MAX_BYTES_PER_PAGE) {
            return png;
        }
        // First encode oversized → re-render at the fallback DPI.
        log.debug("PDF page {} encoded to {} bytes at dpi={}, retrying at fallback dpi={}",
                pageIndex, png.length, dpi, FALLBACK_DPI);
        BufferedImage retry = renderer.renderImageWithDPI(pageIndex, FALLBACK_DPI, ImageType.RGB);
        png = encodePng(retry);
        if (png.length <= MAX_BYTES_PER_PAGE) {
            return png;
        }
        // Still oversized — scale long edge to MAX_LONG_EDGE_PX. Final attempt.
        BufferedImage scaled = scaleLongEdge(retry, MAX_LONG_EDGE_PX);
        png = encodePng(scaled);
        // Even if this final attempt is still > MAX_BYTES_PER_PAGE we return it —
        // we've exhausted the cheap knobs and emitting an oversized page is
        // preferable to dropping the page entirely. The vision provider may
        // still reject huge inline blocks; that surfaces via the existing
        // chat-stream error path. Logged for visibility.
        if (png.length > MAX_BYTES_PER_PAGE) {
            log.warn("PDF page {} still {} bytes after fallback dpi + long-edge scale; "
                    + "emitting oversized image (provider may reject).", pageIndex, png.length);
        }
        return png;
    }

    private static byte[] encodePng(BufferedImage img) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(64 * 1024);
        if (!ImageIO.write(img, "png", baos)) {
            throw new IOException("PNG writer not available for ImageIO");
        }
        return baos.toByteArray();
    }

    private static BufferedImage scaleLongEdge(BufferedImage src, int maxLongEdge) {
        int w = src.getWidth();
        int h = src.getHeight();
        int longEdge = Math.max(w, h);
        if (longEdge <= maxLongEdge) {
            return src;
        }
        double scale = (double) maxLongEdge / longEdge;
        int newW = Math.max(1, (int) Math.round(w * scale));
        int newH = Math.max(1, (int) Math.round(h * scale));
        BufferedImage scaled = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, newW, newH, null);
        } finally {
            g.dispose();
        }
        return scaled;
    }
}
