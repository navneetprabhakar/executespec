import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';

/**
 * R-181 — first-visit tour banner.
 *
 * Renders ONCE per browser, the first time a user lands on any v3
 * page. Stored in localStorage so dismissal persists across
 * sessions. Never blocks UI; ~32px tall, flex above the main
 * container, dismissable with X.
 */

const KEY = 'v3-tour-seen';

export default function TourBanner() {
  const [visible, setVisible] = useState(false);

  useEffect(() => {
    if (typeof window === 'undefined') return;
    try {
      if (!localStorage.getItem(KEY)) {
        // Defer one paint so the v3 shell renders cleanly first.
        const t = setTimeout(() => setVisible(true), 250);
        return () => clearTimeout(t);
      }
    } catch {
      /* ignore */
    }
  }, []);

  function dismiss() {
    setVisible(false);
    try {
      localStorage.setItem(KEY, '1');
    } catch {
      /* ignore */
    }
  }

  if (!visible) return null;

  return (
    <div
      role="status"
      aria-label="v3 tour"
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 10,
        padding: '8px 14px',
        background: 'linear-gradient(90deg, rgba(91,141,239,0.18), rgba(122,209,200,0.12))',
        borderBottom: '1px solid rgba(91,141,239,0.36)',
        fontSize: 12,
        color: 'var(--v3-text)',
      }}
    >
      <span style={{ fontWeight: 600 }}>New here?</span>
      <span style={{ flex: 1, color: 'var(--v3-text-muted)', minWidth: 0 }}>
        Welcome to v3 — chat-shaped, PR-shaped, honest. Take the
        2-min tour to see what changed.
      </span>
      <Link
        to="/v3/changelog"
        onClick={dismiss}
        style={{
          padding: '4px 10px',
          fontSize: 11.5,
          fontWeight: 600,
          background: 'var(--v3-brand)',
          color: '#0B0D10',
          border: 'none',
          borderRadius: 4,
          textDecoration: 'none',
          fontFamily: 'inherit',
        }}
      >
        See what's new
      </Link>
      <button
        type="button"
        onClick={dismiss}
        aria-label="Dismiss"
        style={{
          padding: '4px 6px',
          background: 'transparent',
          color: 'var(--v3-text-subtle)',
          border: 'none',
          cursor: 'pointer',
          fontSize: 14,
        }}
      >
        ×
      </button>
    </div>
  );
}
