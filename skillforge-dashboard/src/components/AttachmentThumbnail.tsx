import React, { useEffect, useState } from 'react';
import { Image as AntdImage, Tag, Tooltip } from 'antd';
import { FilePdfOutlined } from '@ant-design/icons';
import { getChatAttachmentBlob } from '../api';

/**
 * MULTIMODAL-MVP Phase 2: render an inline thumbnail (image) or chip (PDF)
 * for an `image_ref` / `pdf_ref` content block in a chat message bubble.
 *
 * <p>Images are fetched via axios (Bearer auth interceptor) and rendered as a
 * blob URL inside an AntD `Image` (click → full-screen preview). PDFs render a
 * compact chip with filename + page count; click opens the bytes in a new tab.
 * Blob URLs are revoked on unmount.</p>
 *
 * <p>Loading shows a small skeleton; fetch failure shows a fallback chip so
 * the user still knows what they uploaded even if the data endpoint is down.</p>
 */
interface AttachmentThumbnailProps {
  kind: 'image' | 'pdf';
  attachmentId: string;
  filename: string;
  userId: number;
  sessionId?: string;
  /** PDF only — page count surfaced as a chip badge. */
  pageCount?: number;
}

const AttachmentThumbnail: React.FC<AttachmentThumbnailProps> = ({
  kind,
  attachmentId,
  filename,
  userId,
  sessionId,
  pageCount,
}) => {
  const [blobUrl, setBlobUrl] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let cancelled = false;
    let createdUrl: string | null = null;
    setFailed(false);
    setBlobUrl(null);
    getChatAttachmentBlob(attachmentId, userId, sessionId)
      .then((res) => {
        if (cancelled) return;
        // axios with responseType:'blob' returns the Blob in res.data
        const url = URL.createObjectURL(res.data as unknown as Blob);
        createdUrl = url;
        setBlobUrl(url);
      })
      .catch(() => {
        if (cancelled) return;
        setFailed(true);
      });
    return () => {
      cancelled = true;
      if (createdUrl) {
        URL.revokeObjectURL(createdUrl);
      }
    };
  }, [attachmentId, userId, sessionId]);

  if (kind === 'pdf') {
    // PDFs don't get a visual thumbnail in Phase 2 (rendering PDF pages
    // requires pdfjs-dist or similar — deferred). Show a labelled chip with
    // page count; click to open the bytes in a new tab once fetched.
    const onClick = () => {
      if (blobUrl) {
        window.open(blobUrl, '_blank', 'noopener,noreferrer');
      }
    };
    return (
      <Tooltip title={blobUrl ? 'Click to open in a new tab' : 'Loading…'}>
        <Tag
          color="default"
          onClick={onClick}
          style={{
            cursor: blobUrl ? 'pointer' : 'wait',
            padding: '4px 10px',
            fontSize: 12,
            display: 'inline-flex',
            alignItems: 'center',
            gap: 6,
            borderRadius: 8,
          }}
          data-testid="attachment-pdf-chip"
        >
          <FilePdfOutlined style={{ fontSize: 14 }} />
          <span style={{ fontWeight: 500 }}>{filename}</span>
          {typeof pageCount === 'number' && pageCount > 0 && (
            <span style={{ color: 'var(--fg-4, #8a8a93)', fontSize: 11 }}>
              · {pageCount}p
            </span>
          )}
        </Tag>
      </Tooltip>
    );
  }

  // image kind — render inline blob URL with AntD Image preview behavior.
  if (failed) {
    return (
      <Tag color="error" style={{ padding: '4px 10px', borderRadius: 8 }}>
        🖼️ {filename} (load failed)
      </Tag>
    );
  }
  if (!blobUrl) {
    return (
      <div
        data-testid="attachment-image-skeleton"
        style={{
          width: 96,
          height: 96,
          borderRadius: 8,
          background: 'var(--bg-3, rgba(255,255,255,0.04))',
          animation: 'pulse 1.4s ease-in-out infinite',
        }}
      />
    );
  }
  return (
    <AntdImage
      src={blobUrl}
      alt={filename}
      // Inline preview — click opens AntD's full-screen lightbox. Bounded so
      // a 4K screenshot doesn't take over the chat bubble.
      style={{
        maxWidth: 200,
        maxHeight: 200,
        borderRadius: 8,
        objectFit: 'cover',
        display: 'block',
      }}
      preview={{ mask: <span style={{ fontSize: 12 }}>Preview</span> }}
      data-testid="attachment-image-thumb"
    />
  );
};

export default React.memo(AttachmentThumbnail);
