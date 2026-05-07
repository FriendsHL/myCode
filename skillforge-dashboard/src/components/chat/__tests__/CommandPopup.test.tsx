/**
 * P10 — CommandPopup + filterCommands tests.
 *
 * Covers:
 *   - 8-command catalog & fuzzy (prefix) match
 *   - INV-15: `/model` and `/models` are filtered as TWO distinct entries
 *     (no collapsing) and `/models` exact match returns ONLY `/models`.
 *   - Visible selection highlight + click selection
 */
import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import CommandPopup from '../CommandPopup';
import {
  COMMANDS,
  COMMAND_NAME_REGEX,
  filterCommands,
} from '../../../types/command';

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

describe('filterCommands — fuzzy/prefix match', () => {
  it('returns all 8 commands when input is just "/"', () => {
    const result = filterCommands('/');
    expect(result).toHaveLength(8);
    expect(result.map((c) => c.name)).toEqual([
      '/new',
      '/compact',
      '/model',
      '/models',
      '/skill',
      '/tool',
      '/context',
      '/help',
    ]);
  });

  it('"/n" matches only /new', () => {
    const result = filterCommands('/n');
    expect(result.map((c) => c.name)).toEqual(['/new']);
  });

  it('"/c" matches both /compact and /context', () => {
    const result = filterCommands('/c');
    expect(result.map((c) => c.name)).toEqual(['/compact', '/context']);
  });

  it('"/h" matches only /help', () => {
    const result = filterCommands('/h');
    expect(result.map((c) => c.name)).toEqual(['/help']);
  });

  it('"/s" matches only /skill', () => {
    const result = filterCommands('/s');
    expect(result.map((c) => c.name)).toEqual(['/skill']);
  });

  it('"/t" matches only /tool', () => {
    const result = filterCommands('/t');
    expect(result.map((c) => c.name)).toEqual(['/tool']);
  });

  it('INV-15: "/m" matches both /model AND /models (prefix)', () => {
    const result = filterCommands('/m');
    expect(result.map((c) => c.name)).toEqual(['/model', '/models']);
  });

  it('INV-15: "/model" matches both /model and /models (prefix)', () => {
    const result = filterCommands('/model');
    expect(result.map((c) => c.name)).toEqual(['/model', '/models']);
  });

  it('INV-15: "/models" matches ONLY /models — never collapses to /model', () => {
    const result = filterCommands('/models');
    expect(result).toHaveLength(1);
    expect(result[0].name).toBe('/models');
  });

  it('case-insensitive prefix match', () => {
    expect(filterCommands('/N').map((c) => c.name)).toEqual(['/new']);
    expect(filterCommands('/M').map((c) => c.name)).toEqual(['/model', '/models']);
  });

  it('returns empty array when no command matches', () => {
    expect(filterCommands('/zzz')).toEqual([]);
  });

  it('returns empty array when input does not start with /', () => {
    expect(filterCommands('hello')).toEqual([]);
    expect(filterCommands('')).toEqual([]);
  });
});

describe('COMMAND_NAME_REGEX — popup eligibility (INV-Q5 (a))', () => {
  it('matches just `/`', () => {
    expect(COMMAND_NAME_REGEX.test('/')).toBe(true);
  });

  it('matches `/<letters>` only — first character `/`, no whitespace', () => {
    expect(COMMAND_NAME_REGEX.test('/n')).toBe(true);
    expect(COMMAND_NAME_REGEX.test('/model')).toBe(true);
    expect(COMMAND_NAME_REGEX.test('/models')).toBe(true);
  });

  it('does NOT match once a space is typed (popup must hide for args)', () => {
    expect(COMMAND_NAME_REGEX.test('/model ')).toBe(false);
    expect(COMMAND_NAME_REGEX.test('/model gpt-4')).toBe(false);
    expect(COMMAND_NAME_REGEX.test('/new agentName')).toBe(false);
  });

  it('does NOT match plain text', () => {
    expect(COMMAND_NAME_REGEX.test('hello')).toBe(false);
    expect(COMMAND_NAME_REGEX.test('foo /bar')).toBe(false);
    expect(COMMAND_NAME_REGEX.test('')).toBe(false);
  });

  it('does NOT match digits or special chars in command name', () => {
    expect(COMMAND_NAME_REGEX.test('/123')).toBe(false);
    expect(COMMAND_NAME_REGEX.test('/foo-bar')).toBe(false);
  });
});

describe('CommandPopup — rendering', () => {
  it('renders all 8 command rows when given the full catalog', () => {
    render(
      <CommandPopup
        commands={[...COMMANDS]}
        selectedIndex={0}
        onSelect={() => {}}
      />,
    );
    for (const cmd of COMMANDS) {
      expect(screen.getByTestId(`command-option-${cmd.name.slice(1)}`)).toBeInTheDocument();
    }
  });

  it('INV-15: renders BOTH /model and /models as distinct rows when both pass the filter', () => {
    render(
      <CommandPopup
        commands={filterCommands('/m')}
        selectedIndex={0}
        onSelect={() => {}}
      />,
    );
    expect(screen.getByTestId('command-option-model')).toBeInTheDocument();
    expect(screen.getByTestId('command-option-models')).toBeInTheDocument();
  });

  it('marks the selectedIndex row as aria-selected=true and others as false', () => {
    render(
      <CommandPopup
        commands={[...COMMANDS]}
        selectedIndex={2}
        onSelect={() => {}}
      />,
    );
    // /model is index 2 in catalog
    const modelRow = screen.getByTestId('command-option-model');
    expect(modelRow.getAttribute('aria-selected')).toBe('true');
    const newRow = screen.getByTestId('command-option-new');
    expect(newRow.getAttribute('aria-selected')).toBe('false');
  });

  it('calls onSelect with the correct index on mousedown', () => {
    const onSelect = vi.fn();
    render(
      <CommandPopup
        commands={[...COMMANDS]}
        selectedIndex={0}
        onSelect={onSelect}
      />,
    );
    fireEvent.mouseDown(screen.getByTestId('command-option-help'));
    expect(onSelect).toHaveBeenCalledWith(7); // /help is last
  });

  it('calls onHover when a row is hovered', () => {
    const onHover = vi.fn();
    render(
      <CommandPopup
        commands={[...COMMANDS]}
        selectedIndex={0}
        onSelect={() => {}}
        onHover={onHover}
      />,
    );
    fireEvent.mouseEnter(screen.getByTestId('command-option-skill'));
    expect(onHover).toHaveBeenCalledWith(4); // /skill is index 4
  });

  it('renders an empty-state message when no commands match', () => {
    render(
      <CommandPopup commands={[]} selectedIndex={0} onSelect={() => {}} />,
    );
    expect(screen.getByText(/No matching commands/i)).toBeInTheDocument();
  });

  it('shows the command description and usage hint for each row', () => {
    render(
      <CommandPopup
        commands={[...COMMANDS]}
        selectedIndex={0}
        onSelect={() => {}}
      />,
    );
    expect(screen.getByText('/new [agentName?]')).toBeInTheDocument();
    expect(screen.getByText('/model <modelId>')).toBeInTheDocument();
    expect(screen.getByText(/List available models/i)).toBeInTheDocument();
  });
});
