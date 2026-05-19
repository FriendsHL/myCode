/**
 * FLYWHEEL-VISUAL-STATUS Phase 2 (1B URL routing) — SkillList.tsx must
 * consume `?skillId=N&panel=evolution|abtest|canary` deep links and auto-
 * open the SkillDrawer on the matching sub-tab.
 *
 * Two cases:
 *   1. `?panel=drafts` → drafts tab is the initially-active tab
 *   2. `?skillId=42&panel=evolution` → SkillDrawer opens on the
 *      `version-tree` tab once `getSkills()` resolves
 */
import React from 'react';
import { act, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

class ResizeObserverPolyfill {
  observe() {}
  unobserve() {}
  disconnect() {}
}
(globalThis as unknown as { ResizeObserver: typeof ResizeObserverPolyfill }).ResizeObserver =
  ResizeObserverPolyfill;
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

class FakeWs {
  onmessage: ((ev: { data: string }) => void) | null = null;
  close() {}
}
(globalThis as unknown as { WebSocket: typeof FakeWs }).WebSocket = FakeWs;

// SkillDrafts is rendered when activeTab='drafts' — heavy, mock it out.
vi.mock('../SkillDrafts', () => ({
  default: () => <div data-testid="skill-drafts-mock">Drafts mock</div>,
}));

// SkillDrawer is what we assert against for the evolution / ab-test deep
// link case. Surface the `tab` prop as a data-attr so the test can read
// which sub-tab the drawer opened on without poking internal state.
vi.mock('../../components/skills/SkillDrawer', () => ({
  SkillDrawer: (props: { skill: { id: number | string }; tab: string }) => (
    <div
      data-testid="skill-drawer-mock"
      data-skill-id={String(props.skill.id)}
      data-tab={props.tab}
    />
  ),
}));

// Other heavy skills/* components — provide no-op shells.
vi.mock('../../components/skills/NewSkillModal', () => ({
  NewSkillModal: () => null,
}));

const getSkillsMock = vi.fn(() =>
  Promise.resolve({
    data: [
      {
        id: 42,
        name: 'demo-skill',
        description: 'A demo skill for URL-routing tests.',
        enabled: true,
        source: 'custom',
        tags: [],
      },
    ],
  }),
);

vi.mock('../../api', () => ({
  getSkills: (...args: unknown[]) => getSkillsMock(...(args as [])),
  uploadSkill: vi.fn(() => Promise.resolve({ data: {} })),
  deleteSkill: vi.fn(() => Promise.resolve({ data: {} })),
  toggleSkill: vi.fn(() => Promise.resolve({ data: {} })),
  getSkillDrafts: vi.fn(() => Promise.resolve({ data: [] })),
  triggerSkillExtraction: vi.fn(() => Promise.resolve({ data: { status: 'started' } })),
  rescanSkills: vi.fn(() =>
    Promise.resolve({
      data: { created: 0, updated: 0, missing: 0, invalid: 0, shadowed: 0, disabledDuplicates: 0 },
    }),
  ),
  getAgents: vi.fn(() => Promise.resolve({ data: [{ id: 7, name: 'My Agent' }] })),
  getSkillEvalHistory: vi.fn(() => Promise.resolve({ data: [] })),
  extractList: <T,>(res: { data: T[] | { items?: T[] } }): T[] => {
    if (Array.isArray(res.data)) return res.data;
    const items = (res.data as { items?: T[] }).items;
    return Array.isArray(items) ? items : [];
  },
}));

vi.mock('../../contexts/AuthContext', () => ({
  useAuth: () => ({ token: null, userId: 1, login: vi.fn(), logout: vi.fn() }),
}));

vi.mock('../../contexts/TaskTrackerContext', () => ({
  useTaskTracker: () => ({
    addTask: vi.fn(),
    updateTask: vi.fn(),
  }),
  newTaskId: () => 'task-1',
}));

import SkillList from '../SkillList';

function renderRoute(path: string) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: Infinity } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <SkillList />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  getSkillsMock.mockClear();
  window.localStorage.clear();
});

describe('SkillList — URL deep links (1B)', () => {
  it('opens the Drafts tab when `?panel=drafts` is in the URL', async () => {
    renderRoute('/skills?panel=drafts');
    expect(await screen.findByTestId('skill-drafts-mock')).toBeInTheDocument();
  });

  it('opens SkillDrawer on the `version-tree` sub-tab for `?skillId=42&panel=evolution`', async () => {
    renderRoute('/skills?skillId=42&panel=evolution');
    // Drawer appears once the skills query resolves + the URL effect fires.
    const drawer = await waitFor(() => screen.getByTestId('skill-drawer-mock'));
    expect(drawer.getAttribute('data-skill-id')).toBe('42');
    expect(drawer.getAttribute('data-tab')).toBe('version-tree');
  });
});
