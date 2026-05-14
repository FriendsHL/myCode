package com.skillforge.server.service;

import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import com.skillforge.server.entity.ChatAttachmentEntity;
import com.skillforge.server.repository.ChatAttachmentRepository;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Wave 2 PDF-SCAN-FALLBACK — coverage for the scan-PDF page-image fallback path
 * in {@link ChatAttachmentService#materializeForProvider}. Pattern mirrors
 * {@link ChatAttachmentObservabilityColumnsTest} (Mockito repo + {@link TempDir}
 * storage).
 *
 * <p>Scenarios covered:</p>
 * <ul>
 *   <li><b>A</b> — PDF with substantial text (extracted_text_chars &gt;= 200):
 *       only text block emitted, no page-image fallback, no
 *       PDF_TEXT_EMPTY_NEEDS_VISION error code set.</li>
 *   <li><b>B</b> — PDF with near-empty text (extracted_text_chars &lt; 200):
 *       page-image blocks appended, processingMode flipped to
 *       PDF_PAGE_IMAGE, entity saved.</li>
 *   <li><b>C</b> — page-image renderer throws (corrupted / missing file):
 *       error_code = PDF_TEXT_EMPTY_NEEDS_VISION, error_message populated,
 *       text-only fallback still emitted.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ChatAttachmentServicePdfScanFallbackTest {

    @Mock
    private ChatAttachmentRepository attachmentRepository;

    @TempDir
    Path tempStorage;

    private ChatAttachmentService service;

    @BeforeEach
    void setUp() {
        service = new ChatAttachmentService(attachmentRepository, tempStorage.toString());
        lenient().when(attachmentRepository.save(any(ChatAttachmentEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    // ─── Scenario A: PDF with substantial text ───

    @Test
    @DisplayName("Scenario A: extracted text >= 200 chars → no page-image fallback, mode stays PDF_TEXT")
    void scenarioA_substantialText_noFallback() throws IOException {
        // Build a PDF whose pdfTextBlock() (via PDFTextStripper) yields well
        // beyond 200 chars. Repeating a sentence multiple times gives us a
        // predictable test fixture that survives PDFTextStripper word-spacing
        // heuristics. We use 3 pages of long lines.
        String paragraph = "This is a substantial paragraph of text designed to exceed "
                + "two hundred extracted characters when materialized for the LLM. "
                + "The PDF-SCAN-FALLBACK path should NOT engage when text extraction "
                + "succeeds; this fixture verifies that.";
        Path pdf = writeTextPdf(tempStorage.resolve("substantial.pdf"),
                List.of(paragraph, paragraph, paragraph));

        ChatAttachmentEntity pdfRow = newPdfRow("att-sub", pdf, "substantial.pdf");
        when(attachmentRepository.findById(eq("att-sub"))).thenReturn(Optional.of(pdfRow));

        Message msg = new Message();
        msg.setRole(Message.Role.USER);
        msg.setContent(List.of(ContentBlock.pdfRef("att-sub", "substantial.pdf", 3)));

        Message expanded = service.materializeForProvider("sess-1", msg);

        assertThat(expanded).isNotSameAs(msg);
        // No image blocks were appended; just the single text block from pdfTextBlock.
        @SuppressWarnings("unchecked")
        List<Object> blocks = (List<Object>) expanded.getContent();
        long imageBlocks = blocks.stream()
                .filter(b -> b instanceof ContentBlock cb && "image".equals(cb.getType()))
                .count();
        assertThat(imageBlocks).isZero();
        // processingMode stays at PDF_TEXT (text was extracted, not truncated).
        assertThat(pdfRow.getProcessingMode()).isEqualTo(ChatAttachmentService.MODE_PDF_TEXT);
        assertThat(pdfRow.getErrorCode()).isNull();
        assertThat(pdfRow.getExtractedTextChars()).isGreaterThanOrEqualTo(200);
    }

    // ─── Scenario B: PDF with near-empty text → page-image fallback engages ───

    @Test
    @DisplayName("Scenario B: extracted text < 200 chars → page-image fallback engages, mode = PDF_PAGE_IMAGE")
    void scenarioB_nearEmptyText_pageImageFallback() throws IOException {
        // A two-page PDF with a single short word per page: pdfTextBlock() will
        // extract well under 200 chars, triggering the fallback.
        Path pdf = writeTextPdf(tempStorage.resolve("scanlike.pdf"),
                List.of("Hi", "Yo"));

        ChatAttachmentEntity pdfRow = newPdfRow("att-scan", pdf, "scanlike.pdf");
        pdfRow.setPageCount(2);
        when(attachmentRepository.findById(eq("att-scan"))).thenReturn(Optional.of(pdfRow));

        Message msg = new Message();
        msg.setRole(Message.Role.USER);
        msg.setContent(List.of(ContentBlock.pdfRef("att-scan", "scanlike.pdf", 2)));

        Message expanded = service.materializeForProvider("sess-1", msg);

        assertThat(expanded).isNotSameAs(msg);
        @SuppressWarnings("unchecked")
        List<Object> blocks = (List<Object>) expanded.getContent();
        // 1 clarifying text block + 2 page-image blocks.
        long imageBlocks = blocks.stream()
                .filter(b -> b instanceof ContentBlock cb && "image".equals(cb.getType()))
                .count();
        assertThat(imageBlocks).isEqualTo(2);
        long textBlocks = blocks.stream()
                .filter(b -> b instanceof ContentBlock cb && "text".equals(cb.getType()))
                .count();
        assertThat(textBlocks).isEqualTo(1);
        // The text block should mention the rendered-pages clarification.
        String textBlockContent = blocks.stream()
                .filter(b -> b instanceof ContentBlock cb && "text".equals(cb.getType()))
                .map(b -> ((ContentBlock) b).getText())
                .findFirst().orElseThrow();
        assertThat(textBlockContent).contains("Rendered 2 page(s) as images");
        // Entity flipped to PDF_PAGE_IMAGE + persisted.
        assertThat(pdfRow.getProcessingMode()).isEqualTo(ChatAttachmentService.MODE_PDF_PAGE_IMAGE);
        assertThat(pdfRow.getErrorCode()).isNull();
        ArgumentCaptor<ChatAttachmentEntity> captor = ArgumentCaptor.forClass(ChatAttachmentEntity.class);
        verify(attachmentRepository, atLeastOnce()).save(captor.capture());
        assertThat(captor.getValue().getProcessingMode())
                .isEqualTo(ChatAttachmentService.MODE_PDF_PAGE_IMAGE);
    }

    // ─── Scenario C: renderer throws → error_code set, text-only fallback ───

    @Test
    @DisplayName("Scenario C: renderer fails (corrupted bytes) → error_code = PDF_TEXT_EMPTY_NEEDS_VISION, text-only fallback")
    void scenarioC_rendererFails_errorCodeSet() throws IOException {
        // A "PDF" that's actually garbage bytes: pdfTextBlock() catches IOException
        // and yields 0 extracted chars → fallback triggers → renderer also throws
        // (Loader.loadPDF rejects the bytes) → error path runs.
        Path pdf = tempStorage.resolve("broken.pdf");
        Files.writeString(pdf, "not really a pdf at all, just garbage");

        ChatAttachmentEntity pdfRow = newPdfRow("att-broken", pdf, "broken.pdf");
        when(attachmentRepository.findById(eq("att-broken"))).thenReturn(Optional.of(pdfRow));

        Message msg = new Message();
        msg.setRole(Message.Role.USER);
        msg.setContent(List.of(ContentBlock.pdfRef("att-broken", "broken.pdf", 1)));

        Message expanded = service.materializeForProvider("sess-1", msg);

        assertThat(expanded).isNotSameAs(msg);
        @SuppressWarnings("unchecked")
        List<Object> blocks = (List<Object>) expanded.getContent();
        // Text-only fallback: no image blocks, just the [PDF attachment: ...] placeholder.
        long imageBlocks = blocks.stream()
                .filter(b -> b instanceof ContentBlock cb && "image".equals(cb.getType()))
                .count();
        assertThat(imageBlocks).isZero();
        // Error fields populated for admin observability.
        assertThat(pdfRow.getErrorCode())
                .isEqualTo(ChatAttachmentService.PDF_TEXT_EMPTY_NEEDS_VISION);
        assertThat(pdfRow.getErrorMessage()).isNotBlank();
        // processingMode stays at PDF_TEXT_EMPTY (pdfTextBlock refined it before
        // the fallback attempt, and we did NOT overwrite to PDF_PAGE_IMAGE).
        assertThat(pdfRow.getProcessingMode())
                .isEqualTo(ChatAttachmentService.MODE_PDF_TEXT_EMPTY);
        // Entity persisted via the refined-save guard.
        verify(attachmentRepository, atLeastOnce()).save(any(ChatAttachmentEntity.class));
    }

    // ─── helpers ───

    private ChatAttachmentEntity newPdfRow(String id, Path pdfPath, String filename) {
        ChatAttachmentEntity e = new ChatAttachmentEntity();
        e.setId(id);
        e.setSessionId("sess-1");
        e.setUserId(42L);
        e.setKind("pdf");
        e.setMimeType("application/pdf");
        e.setFilename(filename);
        e.setSizeBytes(pdfPath.toFile().length());
        e.setStoragePath(pdfPath.toString());
        e.setStatus("uploaded");
        // upload-time default; materialize will refine.
        e.setProcessingMode(ChatAttachmentService.MODE_PDF_TEXT);
        return e;
    }

    private static Path writeTextPdf(Path target, List<String> pageTexts) throws IOException {
        PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        try (PDDocument doc = new PDDocument()) {
            for (String text : pageTexts) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(font, 12);
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
