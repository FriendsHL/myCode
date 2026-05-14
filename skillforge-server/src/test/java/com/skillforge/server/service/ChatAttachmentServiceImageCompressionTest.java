package com.skillforge.server.service;

import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import com.skillforge.server.entity.ChatAttachmentEntity;
import com.skillforge.server.repository.ChatAttachmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MULTIMODAL Wave 2-D / IMAGE-COMPRESSION — service-level test for the
 * {@code image_ref} branch in {@link ChatAttachmentService#materializeForProvider}.
 *
 * <p>Three core cases (mirror the brief's contract):</p>
 * <ul>
 *   <li>Small image → expanded as-is, mime stays {@code image/png}, processingMode stays
 *       {@code IMAGE_BLOCK_INLINE}, no entity save.</li>
 *   <li>4096px image → expanded as compressed JPEG, processingMode flipped to
 *       {@code IMAGE_BLOCK_COMPRESSED}, repository.save called once.</li>
 *   <li>Corrupted bytes (not a decodable image) → fallback to original bytes, errorCode
 *       set to {@code IMAGE_COMPRESSION_FAILED}, repository.save called once. The LLM
 *       call still proceeds with the original payload (no exception bubbles out).</li>
 * </ul>
 *
 * <p>Pattern mirrors {@link ChatAttachmentObservabilityColumnsTest}: Mockito repo +
 * {@link TempDir} storage. Fixtures are PNG byte arrays produced by
 * {@link ImageIO#write} so nothing binary lives in the repo.</p>
 */
@ExtendWith(MockitoExtension.class)
class ChatAttachmentServiceImageCompressionTest {

    @Mock
    private ChatAttachmentRepository attachmentRepository;

    @TempDir
    Path tempStorage;

    private ChatAttachmentService service;

    @BeforeEach
    void setUp() {
        service = new ChatAttachmentService(attachmentRepository, tempStorage.toString());
    }

    // ─── 1. small image → no compression, no save ───

    @Test
    @DisplayName("small PNG (16x16) → no compression, mime unchanged, no entity save")
    void smallImage_noCompression() throws Exception {
        ChatAttachmentEntity row = imageRow("att-small", solidPng(16, 16, Color.RED), "image/png");
        when(attachmentRepository.findById(eq("att-small"))).thenReturn(Optional.of(row));

        Message msg = imageRefMessage("att-small", "image/png", "small.png");
        Message expanded = service.materializeForProvider("sess-1", msg);

        assertThat(expanded).isNotSameAs(msg);
        ContentBlock outBlock = firstImageBlock(expanded);
        // Under both thresholds → original bytes + mime travel through unchanged.
        assertThat(outBlock.getMimeType()).isEqualTo("image/png");
        assertThat(outBlock.getDataBase64()).isNotBlank();

        // Entity state preserved.
        assertThat(row.getProcessingMode()).isEqualTo("IMAGE_BLOCK_INLINE");
        assertThat(row.getErrorCode()).isNull();
        verify(attachmentRepository, never()).save(any(ChatAttachmentEntity.class));
    }

    // ─── 2. oversized image → compressed, entity flipped, save called ───

    @Test
    @DisplayName("4096x3000 PNG → compressed to JPEG; processingMode=IMAGE_BLOCK_COMPRESSED; save called once")
    void oversizedImage_compressed_savesEntity() throws Exception {
        ChatAttachmentEntity row = imageRow("att-big", solidPng(4096, 3000, Color.BLUE), "image/png");
        when(attachmentRepository.findById(eq("att-big"))).thenReturn(Optional.of(row));
        when(attachmentRepository.save(any(ChatAttachmentEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Message msg = imageRefMessage("att-big", "image/png", "huge.png");
        Message expanded = service.materializeForProvider("sess-1", msg);

        ContentBlock outBlock = firstImageBlock(expanded);
        assertThat(outBlock.getMimeType()).isEqualTo("image/jpeg");
        assertThat(outBlock.getDataBase64()).isNotBlank();

        // Entity mutated + persisted exactly once.
        assertThat(row.getProcessingMode()).isEqualTo("IMAGE_BLOCK_COMPRESSED");
        assertThat(row.getErrorCode()).isNull();
        verify(attachmentRepository, times(1)).save(any(ChatAttachmentEntity.class));
    }

    @Test
    @DisplayName("compression mode already IMAGE_BLOCK_COMPRESSED → no second save (idempotency guard)")
    void alreadyCompressed_noSecondSave() throws Exception {
        ChatAttachmentEntity row = imageRow("att-big2", solidPng(4096, 3000, Color.GREEN), "image/png");
        row.setProcessingMode("IMAGE_BLOCK_COMPRESSED");  // pretend we already flipped on an earlier turn
        when(attachmentRepository.findById(eq("att-big2"))).thenReturn(Optional.of(row));

        Message msg = imageRefMessage("att-big2", "image/png", "big.png");
        service.materializeForProvider("sess-1", msg);

        assertThat(row.getProcessingMode()).isEqualTo("IMAGE_BLOCK_COMPRESSED");
        // Objects.equals guard kicks in — no unnecessary write.
        verify(attachmentRepository, never()).save(any(ChatAttachmentEntity.class));
    }

    // ─── 3. corrupted bytes → fallback, errorCode set, save called ───

    @Test
    @DisplayName("corrupted bytes → IOException caught; fallback to original; errorCode=IMAGE_COMPRESSION_FAILED; save once")
    void corruptedImage_fallback_setsErrorCode() throws Exception {
        // Write a payload that cannot be decoded by ImageIO. We can't go through
        // service.upload() (that path runs magic-byte validation and would reject
        // random bytes upfront), so we hand-craft the entity here. In production
        // this represents either (a) a file the upload path's magic-byte check
        // passed but ImageIO's decoder can't parse — usually a malformed JPEG
        // stream missing essential markers — or (b) disk corruption since upload.
        byte[] corrupt = "this is not actually a valid image file".getBytes();
        ChatAttachmentEntity row = imageRow("att-bad", corrupt, "image/png");
        when(attachmentRepository.findById(eq("att-bad"))).thenReturn(Optional.of(row));
        when(attachmentRepository.save(any(ChatAttachmentEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Message msg = imageRefMessage("att-bad", "image/png", "bad.png");
        Message expanded = service.materializeForProvider("sess-1", msg);

        ContentBlock outBlock = firstImageBlock(expanded);
        // Fallback: original mime + original payload travel through.
        assertThat(outBlock.getMimeType()).isEqualTo("image/png");
        assertThat(outBlock.getDataBase64()).isNotBlank();

        // Entity carries the diagnostic.
        assertThat(row.getProcessingMode()).isEqualTo("IMAGE_BLOCK_INLINE");
        assertThat(row.getErrorCode()).isEqualTo("IMAGE_COMPRESSION_FAILED");
        assertThat(row.getErrorMessage()).isNotBlank();
        verify(attachmentRepository, times(1)).save(any(ChatAttachmentEntity.class));
    }

    // ─── 4. oversized bytes (1024x1024 noisy PNG > 1MB) ───

    @Test
    @DisplayName("1024x1024 noisy PNG > 1MB → compressed even though dims fit; mime flips to image/jpeg")
    void oversizedBytes_compressedDespiteDimsInBudget() throws Exception {
        ChatAttachmentEntity row = imageRow("att-noisy", noisyPng(1024, 1024), "image/png");
        when(attachmentRepository.findById(eq("att-noisy"))).thenReturn(Optional.of(row));
        when(attachmentRepository.save(any(ChatAttachmentEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Message msg = imageRefMessage("att-noisy", "image/png", "noisy.png");
        Message expanded = service.materializeForProvider("sess-1", msg);

        ContentBlock outBlock = firstImageBlock(expanded);
        assertThat(outBlock.getMimeType()).isEqualTo("image/jpeg");
        assertThat(row.getProcessingMode()).isEqualTo("IMAGE_BLOCK_COMPRESSED");
        verify(attachmentRepository, times(1)).save(any(ChatAttachmentEntity.class));
    }

    // ─── helpers ───

    private ChatAttachmentEntity imageRow(String id, byte[] bytes, String mime) throws Exception {
        ChatAttachmentEntity row = new ChatAttachmentEntity();
        row.setId(id);
        row.setSessionId("sess-1");
        row.setUserId(42L);
        row.setKind("image");
        row.setMimeType(mime);
        row.setFilename(id + ".png");
        row.setSizeBytes(bytes.length);
        Path file = tempStorage.resolve(id + ".png");
        Files.write(file, bytes);
        row.setStoragePath(file.toString());
        row.setStatus("uploaded");
        row.setProcessingMode("IMAGE_BLOCK_INLINE");
        return row;
    }

    private static Message imageRefMessage(String attachmentId, String mime, String filename) {
        Message msg = new Message();
        msg.setRole(Message.Role.USER);
        msg.setContent(List.of(ContentBlock.imageRef(attachmentId, mime, filename)));
        return msg;
    }

    private static ContentBlock firstImageBlock(Message m) {
        Object content = m.getContent();
        assertThat(content).isInstanceOf(List.class);
        List<?> blocks = (List<?>) content;
        for (Object b : blocks) {
            if (b instanceof ContentBlock cb && "image".equals(cb.getType())) {
                return cb;
            }
        }
        throw new AssertionError("No expanded image block in: " + blocks);
    }

    private static byte[] solidPng(int w, int h, Color fill) throws Exception {
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

    private static byte[] noisyPng(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Random rnd = new Random(11);
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
