/**
 * RunStamp — 1-line shareable summary of a completed run.
 *
 * Per UX & Wireframe Report §6.3 (suggestion #3): every COMPLETE run
 * deserves a 1-line shareable stamp e.g. "Built in 11m50s · Verified 85
 * · 6/6 tasks · Anthropic". Surfaced at the bottom of the run report with
 * a copy-to-clipboard button + a "share" CTA that copies a public report
 * link (uses the existing /api/runs/{id}/report/share endpoint).
 *
 * Also exposes an embed-ready HTML snippet for blogs / Slack, hidden
 * behind a "Get embed" disclosure.
 */
import { useState } from 'react';
import { Check, Copy, Share2, Code2 } from 'lucide-react';
import { cn } from '@/lib/utils';
import * as api from '@/lib/api';
import VerdictPill from '@/components/runs/VerdictPill';

interface RunStampProps {
  runId: string;
  durationMs: number | null;
  verifiedScore: number | null;
  tasksTotal: number | null;
  tasksComplete: number | null;
  provider: string | null;
}

export default function RunStamp({
  runId,
  durationMs,
  verifiedScore,
  tasksTotal,
  tasksComplete,
  provider,
}: RunStampProps) {
  const [copied, setCopied] = useState<'text' | 'link' | 'embed' | null>(null);
  const [shareUrl, setShareUrl] = useState<string | null>(null);
  const [showEmbed, setShowEmbed] = useState(false);
  const [creatingShare, setCreatingShare] = useState(false);

  const stamp = formatStamp({ durationMs, verifiedScore, tasksTotal, tasksComplete, provider });

  const copy = async (kind: 'text' | 'link' | 'embed', value: string) => {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(kind);
      setTimeout(() => setCopied(null), 1500);
    } catch {
      // ignored — surface failure as non-feedback per the existing copy-button pattern
    }
  };

  const ensureShare = async (): Promise<string | null> => {
    if (shareUrl) return shareUrl;
    setCreatingShare(true);
    try {
      const r = await api.shareValidationReport(runId);
      // Backend returns either { shareUrl } (preferred) or { shareToken } —
      // fall back to constructing the URL from the token if the absolute
      // URL isn't included.
      const url = r.shareUrl || `${window.location.origin}/reports/shared/${r.shareToken}`;
      setShareUrl(url);
      return url;
    } catch {
      return null;
    } finally {
      setCreatingShare(false);
    }
  };

  const onShareClick = async () => {
    const url = await ensureShare();
    if (url) await copy('link', url);
  };

  const embedHtml = shareUrl
    ? `<a href="${shareUrl}" target="_blank" rel="noreferrer">${escapeHtml(stamp)}</a>`
    : '';

  return (
    <div className="rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] p-3 space-y-2">
      <div className="flex items-center gap-2 flex-wrap">
        {/* Plan 1 (this-week #3): verdict leads the share row. The numeric
            "Verified N" stays inside the stamp text so the copyable string
            is still informative on Slack / blogs. */}
        <VerdictPill size="sm" verifiedScore={verifiedScore} />
        <span className="text-[11px] font-mono text-secondary truncate flex-1">{stamp}</span>
        <button
          onClick={() => copy('text', stamp)}
          className="text-[11px] font-medium px-2 py-0.5 rounded-sm border border-[var(--border-default)] text-secondary hover:text-primary inline-flex items-center gap-1"
          title="Copy stamp text"
        >
          {copied === 'text' ? <Check className="h-3 w-3 text-status-success" /> : <Copy className="h-3 w-3" />}
          {copied === 'text' ? 'Copied' : 'Copy'}
        </button>
        <button
          onClick={onShareClick}
          disabled={creatingShare}
          className="text-[11px] font-medium px-2 py-0.5 rounded-sm border border-accent-blue/40 bg-accent-blue/10 text-accent-blue hover:bg-accent-blue/20 disabled:opacity-50 inline-flex items-center gap-1"
          title="Create a public share link and copy"
        >
          {copied === 'link' ? <Check className="h-3 w-3" /> : <Share2 className="h-3 w-3" />}
          {creatingShare ? '…' : copied === 'link' ? 'Link copied' : 'Share'}
        </button>
        <button
          onClick={async () => {
            await ensureShare();
            setShowEmbed((s) => !s);
          }}
          className="text-[11px] font-medium px-2 py-0.5 rounded-sm border border-[var(--border-default)] text-secondary hover:text-primary inline-flex items-center gap-1"
          title="Get embed HTML"
        >
          <Code2 className="h-3 w-3" />
          Embed
        </button>
      </div>

      {showEmbed && shareUrl && (
        <div className="space-y-1">
          <label className="text-[10px] font-mono uppercase tracking-wider text-muted">Embed HTML</label>
          <textarea
            readOnly
            value={embedHtml}
            onClick={(e) => (e.target as HTMLTextAreaElement).select()}
            className={cn(
              'w-full text-[10px] font-mono px-2 py-1 rounded-sm border border-[var(--border-default)]',
              'bg-[var(--bg-primary)] text-primary resize-none h-12',
            )}
          />
          <button
            onClick={() => copy('embed', embedHtml)}
            className="text-[10px] text-accent-blue hover:underline"
          >
            {copied === 'embed' ? 'Copied' : 'Copy embed HTML'}
          </button>
        </div>
      )}
    </div>
  );
}

function formatStamp({
  durationMs,
  verifiedScore,
  tasksTotal,
  tasksComplete,
  provider,
}: Omit<RunStampProps, 'runId'>): string {
  const parts: string[] = ['Built'];
  if (durationMs != null && durationMs > 0) {
    parts.push(`in ${formatDuration(durationMs)}`);
  }
  if (verifiedScore != null) {
    parts.push(`· Verified ${Math.round(verifiedScore)}`);
  }
  if (tasksTotal != null && tasksComplete != null) {
    parts.push(`· ${tasksComplete}/${tasksTotal} tasks`);
  }
  if (provider) {
    parts.push(`· ${provider.charAt(0).toUpperCase()}${provider.slice(1).toLowerCase()}`);
  }
  return parts.join(' ');
}

function formatDuration(ms: number): string {
  const s = Math.round(ms / 1000);
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60);
  const rs = s % 60;
  return `${m}m${rs.toString().padStart(2, '0')}s`;
}

function escapeHtml(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;');
}
