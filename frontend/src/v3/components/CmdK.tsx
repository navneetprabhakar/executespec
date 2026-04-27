import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useUiStore } from '@/stores/useUiStore';
import { useAuthStore } from '@/stores/useAuthStore';
import { useV3Ui } from '../stores/useV3Ui';

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
  const { theme, toggleTheme } = useUiStore();
  const user = useAuthStore((s) => s.user);
  const logout = useAuthStore((s) => s.logout);

  // Build the action list. v3 routes are placeholder destinations
  // until the matching R-IDs land; clicking them today routes to
  // /v3/home so nothing breaks (real targets wired by R-104+).
  const actions = useMemo<Action[]>(() => {
    const all: Action[] = [
      { id: 'home', label: 'Go to home', hint: 'Home', run: () => navigate('/v3/home') },
      { id: 'settings', label: 'Open settings', hint: 'Account · keys · theme', run: () => navigate('/v3/settings') },
      { id: 'billing', label: 'Open billing & plan', hint: 'Plan · usage · pricing', run: () => navigate('/v3/billing') },
      { id: 'team', label: 'Open team', hint: 'Coming soon', run: () => navigate('/v3/home') },
      { id: 'integrations', label: 'Open integrations', hint: 'Coming soon', run: () => navigate('/v3/home') },
      { id: 'pricing', label: 'View pricing', hint: 'Free · Pro · Team', run: () => navigate('/v3/pricing') },
      { id: 'trust', label: 'View Trust & Security', hint: 'SOC 2 · DPA', run: () => navigate('/v3/home') },
      { id: 'help', label: 'Show keyboard shortcuts', hint: '?', run: () => navigate('/v3/home') },
      { id: 'theme', label: `Switch theme (currently ${theme})`, hint: 'Light · Dark', run: () => toggleTheme() },
      { id: 'logout', label: 'Sign out', run: () => { void logout(); } },
      // Admin-only
      { id: 'admin-tiers', label: 'Admin · Tier lifecycle', requireRole: 'SUPER_ADMIN', run: () => navigate('/v3/home') },
      { id: 'admin-flags', label: 'Admin · Feature flags', requireRole: 'SUPER_ADMIN', run: () => navigate('/v3/home') },
      { id: 'admin-audit', label: 'Admin · Audit log', requireRole: 'SUPER_ADMIN', run: () => navigate('/v3/home') },
      { id: 'admin-halt', label: 'Admin · Halt all runs (confirm required)', requireRole: 'SUPER_ADMIN', run: () => navigate('/v3/home') },
    ];
    const role = user?.globalRole;
    return all.filter((a) => {
      if (!a.requireRole) return true;
      if (a.requireRole === 'SUPER_ADMIN') return role === 'SUPER_ADMIN';
      if (a.requireRole === 'ORG_ADMIN') return role === 'SUPER_ADMIN' || user?.orgRole === 'ORG_ADMIN';
      return false;
    });
  }, [navigate, theme, toggleTheme, logout, user]);

  // Filter actions by query (action-mode prefix `>` is optional —
  // strip it then fuzzy-substring match).
  const filtered = useMemo(() => {
    let q = query;
    if (q.startsWith('>')) q = q.slice(1).trim();
    const needle = q.trim().toLowerCase();
    if (!needle) return actions;
    return actions.filter((a) => a.label.toLowerCase().includes(needle));
  }, [query, actions]);

  // Global hotkey
  useEffect(() => {
    function handler(e: KeyboardEvent) {
      const isMod = e.metaKey || e.ctrlKey;
      if (isMod && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        toggleCmdK();
      }
      if (e.key === 'Escape' && open) {
        e.preventDefault();
        closeCmdK();
      }
    }
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [open, toggleCmdK, closeCmdK]);

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
      role="dialog"
      aria-modal="true"
      aria-label="Command palette"
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
        <div style={{ padding: '12px 14px', borderBottom: '1px solid var(--v3-border)' }}>
          <input
            ref={inputRef}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={onKeyDown}
            placeholder="Type > for actions, ? for glossary, or search…"
            aria-label="Command palette input"
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
        <ul
          role="listbox"
          aria-label="Command results"
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
              No matches. Type <code>&gt;</code> to see all actions.
            </li>
          )}
          {filtered.map((a, idx) => (
            <li
              key={a.id}
              role="option"
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
          <span>↑↓ navigate · Enter select · Esc close</span>
          <span>⌘K</span>
        </div>
      </div>
    </div>
  );
}
