import {useState} from 'react';
import {ANNOUNCEMENT} from './announcement';

const DISMISS_KEY = 'es:announcement:dismissed';

/**
 * Dismissible full-width announcement banner pinned to the top of the Console shell.
 *
 * Reads its content from the {@link ANNOUNCEMENT} config. The X persists the dismissal (keyed by
 * the announcement id) in localStorage, so it stays closed until a new announcement ships (the id
 * changes). RL-2026-06-051 — UI component only; publish/admin wiring is a later pass.
 */
export function AnnouncementBanner() {
  const a = ANNOUNCEMENT;
  // Read the dismissal synchronously so the banner never flashes in then out.
  const [dismissed, setDismissed] = useState<boolean>(() => {
    if (!a.enabled) return true;
    try {
      return localStorage.getItem(DISMISS_KEY) === a.id;
    } catch {
      return false;
    }
  });

  if (!a.enabled || dismissed) return null;

  const dismiss = () => {
    try {
      localStorage.setItem(DISMISS_KEY, a.id);
    } catch {
      // localStorage unavailable (e.g. private mode) — dismiss for this session only.
    }
    setDismissed(true);
  };

  return (
    <div className={`con-banner con-banner-${a.severity ?? 'info'}`} role='status'>
      <span className='con-banner-msg'>{a.message}</span>
      {a.link ? (
        <a className='con-banner-link' href={a.link.href}>
          {a.link.label}
        </a>
      ) : null}
      <button
        type='button'
        className='con-banner-x'
        onClick={dismiss}
        aria-label='Dismiss announcement'
        title='Dismiss'
      >
        ✕
      </button>
    </div>
  );
}
