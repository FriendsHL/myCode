package com.skillforge.server.service.document;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wave 2 PDF-SCAN-FALLBACK — unit tests for {@link PdfPageImageRenderer}. PDFs
 * are built programmatically with PDFBox so no binary fixtures need to live in
 * the repo. Each test writes its PDF into a {@link TempDir}, renders, and
 * asserts on the returned PNG bytes.
 */
class PdfPageImageRendererTest {

    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    @TempDir
    Path tempDir;

    // ─── happy path ──────────────────────────────────────────────────────

    @Test
    @DisplayName("renderFirstPages: 3-page PDF returns 3 PNG byte arrays, each with PNG magic header")
    void renderFirstPages_threePagePdf_returnsThreePngs() throws IOException {
        Path pdf = writeTextPdf(tempDir.resolve("three.pdf"),
                List.of("Page One", "Page Two", "Page Three"));

        List<byte[]> imgs = PdfPageImageRenderer.renderFirstPages(pdf, 10, 150f);

        assertThat(imgs).hasSize(3);
        for (byte[] png : imgs) {
            assertThat(png).isNotNull();
            assertThat(png.length).isGreaterThan(0);
            assertThat(png.length).isGreaterThanOrEqualTo(PNG_MAGIC.length);
            for (int i = 0; i < PNG_MAGIC.length; i++) {
                assertThat(png[i]).as("PNG magic byte %d", i).isEqualTo(PNG_MAGIC[i]);
            }
        }
    }

    @Test
    @DisplayName("renderFirstPages: maxPages caps the result count (3-page doc, maxPages=2 → 2 entries)")
    void renderFirstPages_maxPagesCap() throws IOException {
        Path pdf = writeTextPdf(tempDir.resolve("cap.pdf"),
                List.of("Alpha", "Beta", "Gamma"));

        List<byte[]> imgs = PdfPageImageRenderer.renderFirstPages(pdf, 2, 150f);

        assertThat(imgs).hasSize(2);
    }

    @Test
    @DisplayName("renderFirstPages: maxPages > total pages still returns just total")
    void renderFirstPages_fewerPagesThanRequested() throws IOException {
        Path pdf = writeTextPdf(tempDir.resolve("short.pdf"), List.of("Only One"));

        List<byte[]> imgs = PdfPageImageRenderer.renderFirstPages(pdf, 5, 150f);

        assertThat(imgs).hasSize(1);
    }

    // ─── failure paths ───────────────────────────────────────────────────

    @Test
    @DisplayName("renderFirstPages: encrypted PDF → IOException")
    void renderFirstPages_encryptedPdf_throws() throws IOException {
        Path pdf = tempDir.resolve("encrypted.pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            // owner / user password — non-empty user password marks isEncrypted() true on load.
            AccessPermission ap = new AccessPermission();
            StandardProtectionPolicy spp = new StandardProtectionPolicy("ownerpw", "userpw", ap);
            spp.setEncryptionKeyLength(128);
            doc.protect(spp);
            doc.save(pdf.toFile());
        }

        assertThatThrownBy(() -> PdfPageImageRenderer.renderFirstPages(pdf, 5, 150f))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("encrypted");
    }

    @Test
    @DisplayName("renderFirstPages: corrupted bytes → IOException")
    void renderFirstPages_corruptedBytes_throws() throws IOException {
        Path pdf = tempDir.resolve("garbage.pdf");
        Files.writeString(pdf, "this is not a PDF, just random text masquerading as one");

        assertThatThrownBy(() -> PdfPageImageRenderer.renderFirstPages(pdf, 5, 150f))
                .isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("renderFirstPages: missing file → IOException")
    void renderFirstPages_missingFile_throws() {
        Path pdf = tempDir.resolve("does-not-exist.pdf");
        assertThatThrownBy(() -> PdfPageImageRenderer.renderFirstPages(pdf, 5, 150f))
                .isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("renderFirstPages: maxPages <= 0 throws IllegalArgumentException")
    void renderFirstPages_invalidMaxPages_throws() throws IOException {
        Path pdf = writeTextPdf(tempDir.resolve("one.pdf"), List.of("text"));
        assertThatThrownBy(() -> PdfPageImageRenderer.renderFirstPages(pdf, 0, 150f))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("renderFirstPages: dpi <= 0 throws IllegalArgumentException")
    void renderFirstPages_invalidDpi_throws() throws IOException {
        Path pdf = writeTextPdf(tempDir.resolve("one2.pdf"), List.of("text"));
        assertThatThrownBy(() -> PdfPageImageRenderer.renderFirstPages(pdf, 1, 0f))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    /**
     * Build a multi-page PDF where each entry in {@code pageTexts} becomes one
     * page rendered with the standard 14 Helvetica font. Saves and returns
     * {@code target}.
     */
    private static Path writeTextPdf(Path target, List<String> pageTexts) throws IOException {
        PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        try (PDDocument doc = new PDDocument()) {
            for (String text : pageTexts) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(font, 14);
                    cs.newLineAtOffset(72, 720);
                    cs.showText(text);
                    cs.endText();
                }
            }
            doc.save(target.toFile());
        }
        return target;
    }
}
