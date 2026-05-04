import {useEffect, useMemo, useRef, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {useUiStore} from '@/stores/useUiStore';
import {useAuthStore} from '@/stores/useAuthStore';
import {useRunStore} from '@/stores/useRunStore';
import {useV3Ui} from '../stores/useV3Ui';
import {GLOSSARY} from '../lib/glossary';

/**
 * R-103 — command palette skeleton.
 *
 * Cmd-K / Ctrl-K opens a Linear-style modal. Three modes:
 *   - default:  fuzzy-match recent runs / projects (Wave 2)
 *   - `>` :     action mode (this PR)
 *   - `?` :     glossary mode (Wave 2)
 *
 * Wave-1 ships only the action mode + the open/close plumbing.
 * Recent-runs/glossary land on R-153.
 *
 * Design: dark surface, 480px wide, 60vh max height, list of items
 * navigable by ↑↓ + Enter, Esc closes. Focus-trapped on the input.
 */

interface Action {
  id: string;
  label: string;
  /** Optional secondary description shown on the right of the row. */
  hint?: string;
  /** Required role for visibility. */
  requireRole?: 'ORG_ADMIN' | 'SUPER_ADMIN';
  run: () => void;
}

export default function CmdK() {
  const open = useV3Ui((s) => s.cmdKOpen);
  const openCmdK = useV3Ui((s) => s.openCmdK);
  const closeCmdK = useV3Ui((s) => s.closeCmdK);
  const toggleCmdK = useV3Ui((s) => s.toggleCmdK);
  const setOpen = (v: boolean) => (v ? openCmdK() : closeCmdK());
  const [query, setQuery] = useState('');
  const [cursor, setCursor] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);
  const navigate = useNavigate();
  const {theme, toggleTheme} = useUiStore();
  const user = useAuthStore((s) => s.user);
  const logout = useAuthStore((s) => s.logout);
  // R-343 (P0 from UX persona review) — wire the v2 useRunStore into
  // the v3 palette so power users can re-launch / jump to a recent run
  // without leaving the keyboard.  Mirrors the v2 CommandPalette
  // "Recent Runs" group.  No new fetch — uses whatever the run-list
  // store has already loaded; falls back gracefully when empty.
  const recentRuns = useRunStore((s) => s.runs);

  // Build the action list. v3 routes are placeholder destinations
  // until the matching R-IDs land; clicking them today routes to
  // /home so nothing breaks (real targets wired by R-104+).
  const actions = useMemo<Action[]>(() => {
    const all: Action[] = [
      {id: 'home', label: 'Go to home', hint: 'Home', run: () => navigate('/home')},
      {
        id: 'settings',
        label: 'Open settings',
        hint: 'Account · keys · theme',
        run: () => navigate('/settings'),
      },
      {
        id: 'billing',
        label: 'Open billing & plan',
        hint: 'Plan · usage · pricing',
        run: () => navigate('/billing'),
      },
      {
        id: 'team',
        label: 'Open team',
        hint: 'Members · seats · invites',
        run: () => navigate('/team'),
      },
      {
        id: 'integrations',
        label: 'Open integrations',
        hint: 'GitHub · Slack · Jira',
        run: () => navigate('/integrations'),
      },
      {
        id: 'pricing',
        label: 'View pricing',
        hint: 'Free · Pro · Team',
        run: () => navigate('/pricing'),
      },
      {
        id: 'trust',
        label: 'View Trust & Security',
        hint: 'SOC 2 · DPA',
        run: () => navigate('/trust'),
      },
      {id: 'help', label: 'Show keyboard shortcuts', hint: '?', run: () => navigate('/home')},
      {
        id: 'theme',
        label: `Switch theme (currently ${theme})`,
        hint: 'Light · Dark',
        run: () => toggleTheme(),
      },
      {
        id: 'logout',
        label: 'Sign out',
        run: () => {
          void logout();
        },
      },
      // Admin-only — [S-01] (audit Sprint 4, 2026-05-01) wired to real
      // routes. Was: all 4 navigated to /home (false advertising in the
      // most visible power-user surface). Routes are real now post-F-14
      // Placeholder retirement: /admin/tiers mounts TierLifecyclePage,
      // /admin/feature-flags mounts FeatureFlagsPage, /audit mounts
      // AuditPage. The "halt all runs" entry is removed because no
      // backing endpoint exists (POST /api/admin/runs/halt unimplemented).
      {
        id: 'admin-tiers',
        label: 'Admin · Tier lifecycle',
        requireRole: 'SUPER_ADMIN',
        run: () => navigate('/admin/tiers'),
      },
      {
        id: 'admin-flags',
        label: 'Admin · Feature flags',
        requireRole: 'SUPER_ADMIN',
        run: () => navigate('/admin/feature-flags'),
      },
      {
        id: 'admin-audit',
        label: 'Admin · Audit log',
        requireRole: 'SUPER_ADMIN',
        run: () => navigate('/audit'),
      },
    ];
    const role = user?.globalRole;
    const filteredByRole = all.filter((a) => {
      if (!a.requireRole) return true;
      if (a.requireRole === 'SUPER_ADMIN') return role === 'SUPER_ADMIN';
      if (a.requireRole === 'ORG_ADMIN')
        return role === 'SUPER_ADMIN' || user?.orgRole === 'ORG_ADMIN';
      return false;
    });
    // R-343 — append Recent runs as virtual actions.  Top 5 from the
    // shared run-store; each one navigates to the project's run-detail
    // surface (or /runs filtered when projectId is missing on legacy
    // anonymous-trial rows).  IDs prefixed with `run-` so they don't
    // collide with the static action ids above.
    const runActions: Action[] = (recentRuns ?? []).slice(0, 5).map((r) => {
      const preview = (r.userRequest ?? '').trim().slice(0, 50) || '(no spec text)';
      const target = r.projectId ? `/projects/${r.projectId}/runs/${r.runId}` : `/runs`;
      return {
        id: `run-${r.runId}`,
        label: `Recent · ${preview}${(r.userRequest?.length ?? 0) > 50 ? '…' : ''}`,
        hint: r.runId.slice(0, 8),
        run: () => navigate(target),
      };
    });
    return [...filteredByRole, ...runActions];
  }, [navigate, theme, toggleTheme, logout, user, recentRuns]);

  // R-153 — `?` mode renders glossary terms instead of actions.
  const isGlossaryMode = query.startsWith('?');

  // Filter actions by query (action-mode prefix `>` is optional —
  // strip it then fuzzy-substring match).
  const filtered = useMemo(() => {
    if (isGlossaryMode) return actions; // unused in glossary mode
    let q = query;
    if (q.startsWith('>')) q = q.slice(1).trim();
    const needle = q.trim().toLowerCase();
    if (!needle) return actions;
    return actions.filter((a) => a.label.toLowerCase().includes(needle));
  }, [query, actions, isGlossaryMode]);

  const glossaryEntries = useMemo(() => {
    if (!isGlossaryMode) return [];
    const needle = query.slice(1).trim().toLowerCase();
    const entries = Object.entries(GLOSSARY);
    if (!needle) return entries;
    return entries.filter(
      ([key, e]) =>
        key.toLowerCase().includes(needle) ||
        e.term.toLowerCase().includes(needle) ||
        e.definition.toLowerCase().includes(needle),
    );
  }, [query, isGlossaryMode]);

  // Global hotkey
  useEffect(() => {
    function handler(e: KeyboardEvent) {
      const isMod = e.metaKey || e.ctrlKey;
      if (isMod && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        toggleCmdK();
      }
      // R-153 — `?` opens the palette in glossary mode (when not
      // already focused inside an input).
      const target = e.target as HTMLElement | null;
      const inField =
        target &&
        (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable);
      if (e.key === '?' && !isMod && !inField && !open) {
        e.preventDefault();
        openCmdK();
        // Pre-fill `?` so the palette opens directly in glossary mode.
        setTimeout(() => setQuery('?'), 0);
      }
      if (e.key === 'Escape' && open) {
        e.preventDefault();
        closeCmdK();
      }
    }
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [open, toggleCmdK, closeCmdK, openCmdK]);

  useEffect(() => {
    if (open) {
      setQuery('');
      setCursor(0);
      // focus on next tick so the modal is mounted
      setTimeout(() => inputRef.current?.focus(), 0);
    }
  }, [open]);

  // Reset cursor when filter changes
  useEffect(() => {
    setCursor(0);
  }, [filtered.length]);

  function onKeyDown(e: React.KeyboardEvent<HTMLInputElement>) {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setCursor((c) => Math.min(c + 1, Math.max(filtered.length - 1, 0)));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setCursor((c) => Math.max(c - 1, 0));
    } else if (e.key === 'Enter') {
      e.preventDefault();
      const a = filtered[cursor];
      if (a) {
        a.run();
        setOpen(false);
      }
    }
  }

  if (!open) return null;

  return (
    <div
      role='dialog'
      aria-modal='true'
      aria-label='Command palette'
      onClick={(e) => {
        if (e.target === e.currentTarget) setOpen(false);
      }}
      style={{
        position: 'fixed',
        inset: 0,
        background: 'rgba(0,0,0,0.55)',
        zIndex: 100,
        display: 'flex',
        alignItems: 'flex-start',
        justifyContent: 'center',
        paddingTop: 'min(15vh, 120px)',
      }}
    >
      <div
        style={{
          width: 'min(560px, 92vw)',
          maxHeight: '60vh',
          background: 'var(--v3-surface)',
          border: '1px solid var(--v3-border-strong)',
          borderRadius: 10,
          boxShadow: '0 24px 64px rgba(0,0,0,0.55)',
          display: 'flex',
          flexDirection: 'column',
          overflow: 'hidden',
        }}
      >
        <div style={{padding: '12px 14px', borderBottom: '1px solid var(--v3-border)'}}>
          <input
            ref={inputRef}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={onKeyDown}
            placeholder='Type > for actions, ? for glossary, or search…'
            aria-label='Command palette input'
            style={{
              width: '100%',
              padding: '8px 10px',
              background: 'var(--v3-bg)',
              color: 'var(--v3-text)',
              border: '1px solid var(--v3-border)',
              borderRadius: 6,
              fontSize: 14,
              outline: 'none',
            }}
          />
        </div>
        {isGlossaryMode ? (
          <ul
            role='list'
            aria-label='Glossary'
            style={{
              flex: 1,
              margin: 0,
              padding: 6,
              overflowY: 'auto',
              listStyle: 'none',
            }}
          >
            {glossaryEntries.length === 0 && (
              <li
                style={{
                  padding: '16px 14px',
                  color: 'var(--v3-text-subtle)',
                  fontSize: 12,
                  textAlign: 'center',
                }}
              >
                No glossary entries match. Try clearing the search.
              </li>
            )}
            {glossaryEntries.map(([key, entry]) => (
              <li
                key={key}
                style={{
                  padding: '8px 12px',
                  borderRadius: 6,
                  borderBottom: '1px dashed var(--v3-border)',
                }}
              >
                <div
                  style={{
                    fontSize: 12.5,
                    fontWeight: 600,
                    color: 'var(--v3-text)',
                    marginBottom: 2,
                  }}
                >
                  {entry.term}
                </div>
                <div
                  style={{
                    fontSize: 11.5,
                    color: 'var(--v3-text-muted)',
                    lineHeight: 1.5,
                  }}
                >
                  {entry.definition}
                </div>
                {entry.href && (
                  <a
                    href={entry.href}
                    style={{
                      display: 'inline-block',
                      marginTop: 4,
                      fontSize: 11,
                      color: 'var(--v3-brand)',
                      textDecoration: 'none',
                    }}
                  >
                    Learn more →
                  </a>
                )}
              </li>
            ))}
          </ul>
        ) : (
          <ul
            role='listbox'
            aria-label='Command results'
            style={{
              flex: 1,
              margin: 0,
              padding: 6,
              overflowY: 'auto',
              listStyle: 'none',
            }}
          >
            {filtered.length === 0 && (
              <li
                style={{
                  padding: '16px 14px',
                  color: 'var(--v3-text-subtle)',
                  fontSize: 12,
                  textAlign: 'center',
                }}
              >
                No matches. Type <code>&gt;</code> to see all actions, <code>?</code> for glossary.
              </li>
            )}
            {filtered.map((a, idx) => (
              <li
                key={a.id}
                role='option'
                aria-selected={idx === cursor}
                onMouseEnter={() => setCursor(idx)}
                onClick={() => {
                  a.run();
                  setOpen(false);
                }}
                style={{
                  display: 'flex',
                  justifyContent: 'space-between',
                  alignItems: 'center',
                  padding: '8px 12px',
                  borderRadius: 6,
                  cursor: 'pointer',
                  background: idx === cursor ? 'var(--v3-surface-elevated)' : 'transparent',
                  color: 'var(--v3-text)',
                  fontSize: 13,
                }}
              >
                <span>{a.label}</span>
                {a.hint && (
                  <span
                    style={{
                      color: 'var(--v3-text-subtle)',
                      fontSize: 11,
                      fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
                    }}
                  >
                    {a.hint}
                  </span>
                )}
              </li>
            ))}
          </ul>
        )}
        <div
          style={{
            display: 'flex',
            justifyContent: 'space-between',
            padding: '8px 14px',
            borderTop: '1px solid var(--v3-border)',
            color: 'var(--v3-text-subtle)',
            fontSize: 11,
          }}
        >
          <span>
            {isGlossaryMode
              ? 'Esc close · clear ? to return to actions'
              : '↑↓ navigate · Enter select · Esc close · ? glossary'}
          </span>
          <span>⌘K</span>
        </div>
      </div>
    </div>
  );
}
