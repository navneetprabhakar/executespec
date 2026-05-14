/**
 * 2026-05-14 (mobile-first redesign, P8) — relative-time primitive.
 *
 * Verbose timestamps like `13/5/2026, 8:28:52 PM` eat a horizontal row
 * the title needs. Best-in-class apps (GitHub, Linear, Slack) render a
 * short relative ("17h ago", "3 days ago") on phone with the full ISO
 * timestamp in a tooltip / long-press for the audit trail.
 *
 * This primitive does the same:
 *   - Renders relative ("just now", "5m ago", "2h ago", "3 days ago",
 *     "May 14") on every viewport.
 *   - Renders full ISO timestamp on hover (desktop) + long-press (phone)
 *     via the `title` attribute.
 *   - Auto-updates every minute while < 1 hour old, every 15 minutes while
 *     < 1 day, then static after that.
 *
 * Usage:
 *   <RelativeTime value={run.createdAt} />            // "5m ago"
 *   <RelativeTime value={run.createdAt} verbose />    // "5 minutes ago"
 *
 * The full ISO is always in the title attribute. Don't add an extra
 * tooltip wrapper — keep the markup light.
 */
import {useEffect, useState} from 'react';

interface RelativeTimeProps {
  /** Anything `new Date(value)` accepts: ISO string, epoch ms, or Date. */
  value: string | number | Date | null | undefined;
  /** Long-form ("5 minutes ago") vs short ("5m ago"). Default: short. */
  verbose?: boolean;
  /** Render absolute date as a fallback when value is missing. */
  fallback?: string;
  /** Optional className for the <time> element. */
  className?: string;
}

function format(date: Date, verbose: boolean): string {
  const now = Date.now();
  const ms = now - date.getTime();
  const sec = Math.round(ms / 1000);
  if (sec < 5) return 'just now';
  if (sec < 60) return verbose ? `${sec} seconds ago` : `${sec}s ago`;
  const min = Math.round(sec / 60);
  if (min < 60) return verbose ? `${min} minute${min === 1 ? '' : 's'} ago` : `${min}m ago`;
  const hr = Math.round(min / 60);
  if (hr < 24) return verbose ? `${hr} hour${hr === 1 ? '' : 's'} ago` : `${hr}h ago`;
  const day = Math.round(hr / 24);
  if (day < 7) return verbose ? `${day} day${day === 1 ? '' : 's'} ago` : `${day}d ago`;
  // ≥ 1 week — show absolute "May 14" / "May 14, 2025" if past year boundary
  const sameYear = date.getFullYear() === new Date().getFullYear();
  return date.toLocaleDateString('en-US', {
    month: 'short',
    day: 'numeric',
    year: sameYear ? undefined : 'numeric',
  });
}

export default function RelativeTime({
  value,
  verbose = false,
  fallback,
  className,
}: RelativeTimeProps) {
  // Normalise to a Date once on each render; bail early on missing/invalid
  const date = (() => {
    if (value == null) return null;
    const d = value instanceof Date ? value : new Date(value);
    return isNaN(d.getTime()) ? null : d;
  })();

  // Force re-render every minute (under 1 hour) / 15 minutes (under 1 day)
  // so the rendered text stays fresh without prop changes.
  const [, tick] = useState(0);
  useEffect(() => {
    if (!date) return;
    const ms = Date.now() - date.getTime();
    const interval = ms < 60 * 60 * 1000 ? 60_000 : 15 * 60 * 1000;
    const id = setInterval(() => tick((n) => n + 1), interval);
    return () => clearInterval(id);
  }, [date?.getTime()]);

  if (!date) {
    return fallback ? <span className={className}>{fallback}</span> : null;
  }
  return (
    <time dateTime={date.toISOString()} title={date.toLocaleString()} className={className}>
      {format(date, verbose)}
    </time>
  );
}
