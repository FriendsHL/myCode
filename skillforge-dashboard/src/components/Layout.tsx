import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { Badge } from 'antd';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useTheme } from '../contexts/ThemeContext';
import { useAuth } from '../contexts/AuthContext';
import { getSkillDrafts } from '../api';
import CmdKPalette, { type PaletteItem } from './CmdKPalette';
import TweaksPanel from './chat/TweaksPanel';
import { IconChat, IconSun, IconMoon, IconSettings, IconSparkle } from './chat/ChatIcons';

type NavItem = {
  key: string;
  path: string;
  label: string;
};

const primaryNav: NavItem[] = [
  { key: 'chat', path: '/chat', label: 'Chat' },
  { key: 'agents', path: '/agents', label: 'Agents' },
  { key: 'skills', path: '/skills', label: 'Skills' },
  // SKILL-DASHBOARD-POLISH §E — Drafts top-level entry. Sits next to Skills
  // since the draft → skill approve flow is mostly a Skills-page sibling.
  { key: 'skill-drafts', path: '/skill-drafts', label: 'Drafts' },
  { key: 'tools', path: '/tools', label: 'Tools' },
  { key: 'teams', path: '/teams', label: 'Teams' },
  { key: 'sessions', path: '/sessions', label: 'Sessions' },
  { key: 'hooks', path: '/hooks', label: 'Hooks' },
  { key: 'evals', path: '/eval', label: 'Evals' },
  { key: 'memories', path: '/memories', label: 'Memory' },
  { key: 'usage', path: '/usage', label: 'Usage' },
  { key: 'traces', path: '/traces', label: 'Traces' },
  { key: 'channels', path: '/channels', label: 'Channels' },
  { key: 'schedules', path: '/schedules', label: 'Schedules' },
  { key: 'mcp-servers', path: '/mcp-servers', label: 'MCP' },
];

const paletteItems: PaletteItem[] = primaryNav.map((i) => ({
  path: i.path,
  label: i.label,
  group: 'Navigate',
}));

const navItemActive = (item: NavItem, pathname: string): boolean => {
  if (item.path === '/') return pathname === '/';
  const rootPath = '/' + item.path.split('/').filter(Boolean)[0];
  return pathname === rootPath || pathname.startsWith(rootPath + '/');
};

const AppLayout: React.FC = () => {
  const navigate = useNavigate();
  const location = useLocation();
  const { theme, toggleTheme } = useTheme();
  const { userId } = useAuth();
  const queryClient = useQueryClient();
  const [paletteOpen, setPaletteOpen] = useState(false);
  const [tweaksOpen, setTweaksOpen] = useState(false);

  // SKILL-DASHBOARD-POLISH §E — pending draft count for the sidebar Badge.
  // Shares the cache key with the SkillDrafts page so navigating between
  // them is free; WS pushes invalidate the same key (self-check #3).
  const { data: draftsData } = useQuery({
    queryKey: ['skill-drafts', userId],
    queryFn: () => getSkillDrafts(userId).then(r => r.data),
    enabled: !!userId,
    // Don't hammer the BE — drafts only change via cron / explicit extract +
    // the WS subscription below already invalidates on push.
    staleTime: 30_000,
  });
  const pendingDraftCount = useMemo(
    () => (draftsData ?? []).filter(d => d.status === 'draft').length,
    [draftsData],
  );

  // Layout-level WS subscription for `skill_draft_extracted` so the badge
  // refreshes even when the user is not on the Skills / Drafts page.
  // Cleanup must close the socket (frontend.md footgun #2).
  useEffect(() => {
    if (!userId) return;
    const proto = window.location.protocol === 'https:' ? 'wss' : 'ws';
    const token = localStorage.getItem('sf_token') ?? '';
    const ws = new WebSocket(
      `${proto}://${window.location.host}/ws/users/${userId}?token=${encodeURIComponent(token)}`,
    );
    ws.onmessage = (ev) => {
      try {
        const msg = JSON.parse(ev.data) as { type?: string };
        if (msg.type === 'skill_draft_extracted') {
          queryClient.invalidateQueries({ queryKey: ['skill-drafts'] });
        }
      } catch { /* ignore */ }
    };
    return () => { try { ws.close(); } catch { /* ignore */ } };
  }, [userId, queryClient]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const mac = e.metaKey;
      if ((mac || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        setPaletteOpen((v) => !v);
        return;
      }
      const target = e.target as HTMLElement | null;
      const typing =
        !!target &&
        (target.tagName === 'INPUT' ||
          target.tagName === 'TEXTAREA' ||
          target.isContentEditable);
      if (!typing && !e.metaKey && !e.ctrlKey && !e.altKey && e.key.toLowerCase() === 't') {
        setTweaksOpen((v) => !v);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  const handlePaletteNavigate = useCallback(
    (path: string) => {
      navigate(path);
    },
    [navigate],
  );

  const isChatRoute = location.pathname.startsWith('/chat');
  const userInitial = useMemo(() => String(userId).slice(0, 2).toUpperCase(), [userId]);

  return (
    <div className="sf-shell sf-shell--topbar" data-theme={theme}>
      <header className="topbar" role="banner">
        <Link to="/" className="brand" aria-label="SkillForge home">
          <span className="brand-mark">SF</span>
          <span>
            Skill<em>Forge</em>
          </span>
        </Link>

        <nav className="topbar-nav" aria-label="Primary">
          {primaryNav.map((item) => {
            const active = navItemActive(item, location.pathname);
            const showBadge = item.key === 'skill-drafts' && pendingDraftCount > 0;
            return (
              <Link
                key={item.key}
                to={item.path}
                className={active ? 'active' : ''}
                aria-current={active ? 'page' : undefined}
              >
                {item.key === 'chat' && <IconChat />}
                {showBadge ? (
                  <Badge
                    count={pendingDraftCount}
                    size="small"
                    offset={[8, -2]}
                    overflowCount={99}
                    data-testid="drafts-badge"
                  >
                    <span style={{ paddingRight: 2 }}>{item.label}</span>
                  </Badge>
                ) : (
                  item.label
                )}
              </Link>
            );
          })}
        </nav>

        <div className="topbar-tools">
          <button
            type="button"
            className="cmdk"
            onClick={() => setPaletteOpen(true)}
            aria-label="Open command palette"
          >
            <IconSparkle />
            <span>Jump to…</span>
            <span className="kbd">⌘K</span>
          </button>
          <button
            type="button"
            className="icon-btn"
            onClick={toggleTheme}
            aria-label={theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'}
            title="Toggle theme"
          >
            {theme === 'dark' ? <IconSun /> : <IconMoon />}
          </button>
          <button
            type="button"
            className="icon-btn"
            onClick={() => setTweaksOpen((v) => !v)}
            aria-label="Appearance tweaks"
            title="Appearance (t)"
          >
            <IconSettings />
          </button>
          <div className="avatar" aria-label={`User ${userId}`}>
            {userInitial}
          </div>
        </div>
      </header>

      <div className="sf-main">
        <main className={`sf-content${isChatRoute ? ' sf-content--chat' : ''}`}>
          <Outlet />
        </main>
      </div>

      {paletteOpen && (
        <CmdKPalette
          items={paletteItems}
          onClose={() => setPaletteOpen(false)}
          onNavigate={handlePaletteNavigate}
        />
      )}

      <TweaksPanel open={tweaksOpen} onClose={() => setTweaksOpen(false)} />
    </div>
  );
};

export default AppLayout;
