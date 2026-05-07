/**
 * P10 — CommandResultModal: markdown rendering + close button.
 */
import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import CommandResultModal from '../CommandResultModal';

if (!window.matchMedia) {
  window.matchMedia = (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  });
}

describe('CommandResultModal', () => {
  it('renders a markdown body when open=true', () => {
    render(
      <CommandResultModal
        open
        title="/help"
        markdownBody={'# Title\n\nSome **bold** content.'}
        onClose={() => {}}
      />,
    );
    // The MarkdownRenderer turns `# Title` into an h1
    expect(screen.getByRole('heading', { level: 1, name: 'Title' })).toBeInTheDocument();
    // Bold text ends up as <strong>
    expect(screen.getByText('bold')).toBeInTheDocument();
  });

  it('uses the title prop in the modal header', () => {
    render(
      <CommandResultModal
        open
        title="/models"
        markdownBody="content"
        onClose={() => {}}
      />,
    );
    // Antd renders the title in an .ant-modal-title element
    expect(screen.getByText('/models')).toBeInTheDocument();
  });

  it('falls back to a default title when none is passed', () => {
    render(
      <CommandResultModal
        open
        markdownBody="content"
        onClose={() => {}}
      />,
    );
    expect(screen.getByText('Command result')).toBeInTheDocument();
  });

  it('does NOT render anything in the body when open=false', () => {
    render(
      <CommandResultModal
        open={false}
        title="/help"
        markdownBody="should-not-appear"
        onClose={() => {}}
      />,
    );
    expect(screen.queryByText('should-not-appear')).not.toBeInTheDocument();
  });

  it('renders a placeholder when markdownBody is empty', () => {
    render(
      <CommandResultModal open title="/help" markdownBody="" onClose={() => {}} />,
    );
    expect(screen.getByTestId('command-result-empty')).toBeInTheDocument();
  });

  it('calls onClose when the close button (X) is clicked', () => {
    const onClose = vi.fn();
    render(
      <CommandResultModal
        open
        title="/help"
        markdownBody="hello"
        onClose={onClose}
      />,
    );
    // Antd's close button has aria-label="Close"
    const closeBtn = screen.getByRole('button', { name: /close/i });
    fireEvent.click(closeBtn);
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
