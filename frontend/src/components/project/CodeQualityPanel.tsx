/**
 * Code Quality panel — surfaces the whole-repo Code Review Agent (v2.6.4) on the project
 * dashboard. PAID-tier feature; FREE shows a locked card.
 *
 * Layout per repo:
 *   ┌───────────────────────────────────────────────────────────────────┐
 *   │  acme/api-server  · COMPLETE · sha=abc1234 · 2026-04-15 · 2m18s  │
 *   │  ────────────────────────────────────────────────────────────────  │
 *   │  Verdict: 12 open · 1 CRITICAL · 3 MAJOR · 8 MINOR · 0 INFO        │
 *   │  [ Run new scan ] [ View report ] [ Download .md ]                 │
 *   └───────────────────────────────────────────────────────────────────┘
 *
 * On click "View report" the markdown is rendered in-place with `MarkdownContent`. The Run
 * button kicks off a new scan via `submitCodeReview` and starts polling the scan endpoint
 * every 2.5s for status updates.
 */
import {useCallback, useEffect, useRef, useState} from 'react';

import {
  type CodeReviewScanWithIssues,
  codeReviewReportDownloadUrl,
  getCodeReview,
  getLatestCodeReviewForRepo,
  submitCodeReview,
} from '@/lib/api';
import {Lock} from 'lucide-react';

import {useAuthStore} from '@/stores/useAuthStore';
import {MarkdownContent} from '@/components/shared/MarkdownContent';
import {cn} from '@/lib/utils';

interface RepoLite {
  id?: string;
  repoId?: string;
  githubRepoFullName?: string;
  repoName?: string;
}

interface CodeQualityPanelProps {
  projectId: string;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  repos: RepoLite[];
}

const PAID_TIERS = new Set(['PRO', 'TEAM', 'MAX', 'ENTERPRISE']);

export default function CodeQualityPanel({projectId, repos}: CodeQualityPanelProps) {
  const user = useAuthStore((s) => s.user);
  const isPaid = !!user && PAID_TIERS.has(user.tier);

  if (!isPaid) {
    return (
      <div className='border border-[var(--border-default)] rounded p-4 flex items-start gap-3'>
        <Lock className='h-4 w-4 text-accent-amber mt-0.5 flex-shrink-0' />
        <div className='space-y-1'>
          <div className='text-[12px] font-medium text-primary'>Code review is a paid feature</div>
          <div className='text-[11px] text-secondary leading-relaxed'>
            Upgrade to Pro or above to scan linked repositories with semgrep + the LLM augmentation
            agent (Sonnet 4.6 / GPT-5.4 / Gemini 3). PRO gets 4 scans/month; TEAM/MAX/ENTERPRISE are
            unlimited.
          </div>
        </div>
      </div>
    );
  }

  if (!repos || repos.length === 0) {
    return (
      <div className='text-xs text-muted p-3'>
        Link at least one repository to enable code review scans.
      </div>
    );
  }

  return (
    <div className='space-y-3'>
      {repos.map((r) => {
        const repoFullName = r.githubRepoFullName ?? r.repoName;
        if (!repoFullName) return null;
        return (
          <RepoCodeReviewCard
            key={r.id ?? r.repoId ?? repoFullName}
            projectId={projectId}
            repoId={r.id ?? r.repoId}
            repoFullName={repoFullName}
          />
        );
      })}
    </div>
  );
}

interface RepoCardProps {
  projectId: string;
  repoId?: string;
  repoFullName: string;
}

function RepoCodeReviewCard({projectId, repoId, repoFullName}: RepoCardProps) {
  const [latest, setLatest] = useState<CodeReviewScanWithIssues | null>(null);
  const [activeScanId, setActiveScanId] = useState<string | null>(null);
  const [activeStatus, setActiveStatus] = useState<string | null>(null);
  const [showReport, setShowReport] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const pollTimer = useRef<number | null>(null);

  const refreshLatest = useCallback(async () => {
    try {
      const data = await getLatestCodeReviewForRepo(projectId, repoFullName);
      setLatest(data);
    } catch {
      // 204 / no-content returns null; nothing to do.
    }
  }, [projectId, repoFullName]);

  useEffect(() => {
    refreshLatest();
  }, [refreshLatest]);

  // Poll the active scan every 2.5s until it terminates.
  useEffect(() => {
    if (!activeScanId) return undefined;
    if (activeStatus === 'COMPLETE' || activeStatus === 'FAILED') return undefined;

    const tick = async () => {
      try {
        const detail = await getCodeReview(activeScanId);
        setActiveStatus(detail.scan.status);
        if (detail.scan.status === 'COMPLETE' || detail.scan.status === 'FAILED') {
          setLatest(detail);
          setActiveScanId(null);
          // Refetch the latest so we pick up the canonical row even if the user scrolls away.
          await refreshLatest();
        }
      } catch (e) {
        setError((e as Error).message);
      }
    };
    pollTimer.current = window.setInterval(tick, 2500);
    return () => {
      if (pollTimer.current) {
        window.clearInterval(pollTimer.current);
        pollTimer.current = null;
      }
    };
  }, [activeScanId, activeStatus, refreshLatest]);

  const handleRun = async () => {
    setSubmitting(true);
    setError(null);
    try {
      const scan = await submitCodeReview({projectId, repoId, repoFullName});
      setActiveScanId(scan.id);
      setActiveStatus(scan.status);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setSubmitting(false);
    }
  };

  const verdict = latest?.scan;
  const issues = latest?.issues ?? [];
  const counts = countBySeverity(issues);
  const reportUrl = verdict ? codeReviewReportDownloadUrl(verdict.id) : null;

  return (
    <div className='border border-[var(--border-default)] rounded p-3 space-y-2'>
      <div className='flex items-baseline justify-between gap-2 flex-wrap'>
        <div className='font-mono text-[11px] text-secondary'>{repoFullName}</div>
        {verdict && (
          <div className='text-[10px] text-muted'>
            {verdict.status} · sha={shortSha(verdict.scannedSha)} · {fmtRel(verdict.startedAt)}
            {verdict.durationMs ? ` · ${fmtDur(verdict.durationMs)}` : ''}
          </div>
        )}
        {!verdict && <div className='text-[10px] text-muted'>No scan yet</div>}
      </div>

      {verdict && (
        <div className='flex flex-wrap gap-1.5 text-[11px]'>
          <SeverityChip label={`${counts.open} open`} variant='neutral' />
          <SeverityChip label={`${counts.critical} CRITICAL`} variant='critical' />
          <SeverityChip label={`${counts.major} MAJOR`} variant='major' />
          <SeverityChip label={`${counts.minor} MINOR`} variant='minor' />
          <SeverityChip label={`${counts.info} INFO`} variant='info' />
        </div>
      )}

      {activeScanId && activeStatus && (
        <div className='text-[11px] text-secondary'>
          {progressLabel(activeStatus)} (scan {activeScanId.slice(0, 8)}…)
        </div>
      )}

      {error && <div className='text-[11px] text-status-error'>{error}</div>}

      <div className='flex gap-2 flex-wrap'>
        <button
          onClick={handleRun}
          disabled={submitting || !!activeScanId}
          className={cn(
            'text-[11px] px-2.5 py-1 rounded border',
            submitting || !!activeScanId
              ? 'border-[var(--border-default)] text-muted cursor-not-allowed'
              : 'border-accent-blue/40 text-accent-blue hover:bg-accent-blue/10',
          )}
        >
          {activeScanId ? 'Scanning…' : verdict ? 'Run new scan' : 'Run first scan'}
        </button>
        {verdict && (
          <button
            onClick={() => setShowReport((v) => !v)}
            className='text-[11px] px-2.5 py-1 rounded border border-[var(--border-default)] text-secondary hover:text-primary'
          >
            {showReport ? 'Hide report' : 'View report'}
          </button>
        )}
        {reportUrl && (
          <a
            href={reportUrl}
            download
            className='text-[11px] px-2.5 py-1 rounded border border-[var(--border-default)] text-secondary hover:text-primary'
          >
            Download .md
          </a>
        )}
      </div>

      {showReport && verdict && latest?.reportMarkdown && (
        <div className='border border-[var(--border-default)] rounded p-3 max-h-[600px] overflow-y-auto bg-bg-elevated'>
          <MarkdownContent content={latest.reportMarkdown} />
        </div>
      )}
    </div>
  );
}

interface SeverityChipProps {
  label: string;
  variant: 'critical' | 'major' | 'minor' | 'info' | 'neutral';
}

function SeverityChip({label, variant}: SeverityChipProps) {
  const cls =
    variant === 'critical'
      ? 'border-status-error/40 text-status-error bg-status-error/10'
      : variant === 'major'
        ? 'border-status-partial/40 text-status-partial bg-status-partial/10'
        : variant === 'minor'
          ? 'border-status-executing/30 text-status-executing bg-status-executing/10'
          : variant === 'info'
            ? 'border-[var(--border-default)] text-muted'
            : 'border-[var(--border-default)] text-secondary';
  return <span className={cn('px-1.5 py-0.5 rounded border', cls)}>{label}</span>;
}

interface SeverityCounts {
  open: number;
  critical: number;
  major: number;
  minor: number;
  info: number;
}

function countBySeverity(issues: {severity: string; status: string}[]): SeverityCounts {
  let open = 0;
  let critical = 0;
  let major = 0;
  let minor = 0;
  let info = 0;
  for (const i of issues) {
    if (i.status !== 'OPEN') continue;
    open++;
    if (i.severity === 'CRITICAL') critical++;
    else if (i.severity === 'MAJOR') major++;
    else if (i.severity === 'MINOR') minor++;
    else if (i.severity === 'INFO') info++;
  }
  return {open, critical, major, minor, info};
}

function progressLabel(status: string): string {
  switch (status) {
    case 'QUEUED':
      return 'Queued…';
    case 'CLONING':
      return 'Cloning repo…';
    case 'STATIC_RUNNING':
      return 'Running semgrep static scan…';
    case 'LLM_RUNNING':
      return 'LLM augmentation pass — typically 30–90s…';
    case 'COMPLETE':
      return 'Done';
    case 'FAILED':
      return 'Failed';
    default:
      return status;
  }
}

function shortSha(sha: string | null | undefined) {
  if (!sha) return '(none)';
  return sha.length <= 7 ? sha : sha.slice(0, 7);
}

function fmtRel(iso: string) {
  try {
    const t = new Date(iso).getTime();
    const diff = Date.now() - t;
    if (diff < 60_000) return 'just now';
    if (diff < 3600_000) return `${Math.floor(diff / 60_000)}m ago`;
    if (diff < 86400_000) return `${Math.floor(diff / 3600_000)}h ago`;
    return `${Math.floor(diff / 86400_000)}d ago`;
  } catch {
    return '';
  }
}

function fmtDur(ms: number) {
  if (ms < 60_000) return `${Math.round(ms / 1000)}s`;
  return `${Math.floor(ms / 60_000)}m${Math.round((ms % 60_000) / 1000)}s`;
}
