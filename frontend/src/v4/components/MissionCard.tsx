/**
 * MissionCard — the verdict-first run hero.
 *
 * Replaces the run detail's "Needs review" / sha / 24 changed files / tabs
 * stack with a single anchor block that leads with the verdict and
 * surfaces only two primary actions ("Open run" / "View report"). Used on
 * HomePage (last-run hero) and RunPage (the page's lead).
 */
import {Link} from 'react-router-dom';
import {ArrowUpRight, GitBranch} from 'lucide-react';
import {fmtCost} from '@/lib/utils';
import StatusDot, {type StatusTone} from './StatusDot';

export interface MissionRun {
  runId: string;
  runTitle?: string | null;
  status: string;
  verdict?: 'READY' | 'NEEDS_REVIEW' | 'FAILED' | 'BLOCKED' | string | null;
  verifiedScore?: number | null;
  estimatedScore?: number | null;
  startedAt?: string | null;
  completedAt?: string | null;
  durationMs?: number | null;
  actualCostUsd?: number | null;
  githubPrUrl?: string | null;
  buildOk?: boolean | null;
  reviewerVerdict?: string | null;
  testRatio?: string | null;
  branchName?: string | null;
  projectId?: string | null;
}

function deriveTone(run: MissionRun): StatusTone {
  if (run.status === 'COMPLETE' && run.verdict === 'READY') return 'success';
  if (run.status === 'COMPLETE' && run.verdict === 'NEEDS_REVIEW') return 'warn';
  if (run.status === 'PARTIAL') return 'warn';
  if (run.status === 'FAILED' || run.status === 'ABORTED' || run.verdict === 'FAILED')
    return 'fail';
  if (
    run.status === 'WORKING' ||
    run.status === 'PLANNING' ||
    run.status === 'REVIEWING' ||
    run.status === 'MERGING'
  )
    return 'progress';
  return 'queued';
}

const VERDICT_LABEL: Record<string, string> = {
  READY: 'Ready to ship',
  NEEDS_REVIEW: 'Needs review',
  FAILED: 'Failed',
  BLOCKED: 'Blocked',
};

function timeAgo(iso?: string | null): string {
  if (!iso) return '';
  const diff = Date.now() - new Date(iso).getTime();
  if (Number.isNaN(diff)) return '';
  if (diff < 60_000) return 'just now';
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}m ago`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}h ago`;
  return `${Math.floor(diff / 86_400_000)}d ago`;
}

export default function MissionCard({run}: {run: MissionRun}) {
  const tone = deriveTone(run);
  const score = run.verifiedScore ?? run.estimatedScore ?? null;
  const heroLabel =
    run.verdict && VERDICT_LABEL[run.verdict]
      ? VERDICT_LABEL[run.verdict]
      : run.status.replace(/_/g, ' ').toLowerCase();

  return (
    <article className='v4-card v4-reveal' style={{padding: '18px 18px 14px'}}>
      {/* Header — status dot + verdict label inline */}
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 10,
          marginBottom: 10,
        }}
      >
        <StatusDot tone={tone} size={10} />
        <span
          style={{
            fontSize: 'var(--v4-text-2xs)',
            fontWeight: 700,
            letterSpacing: '0.08em',
            textTransform: 'uppercase',
            color:
              tone === 'success'
                ? 'var(--v4-success)'
                : tone === 'warn'
                  ? 'var(--v4-warn)'
                  : tone === 'fail'
                    ? 'var(--v4-fail)'
                    : tone === 'progress'
                      ? 'var(--v4-progress)'
                      : 'var(--v4-text-muted)',
          }}
        >
          {heroLabel}
        </span>
        <span
          style={{
            marginLeft: 'auto',
            fontSize: 'var(--v4-text-xs)',
            color: 'var(--v4-text-subtle)',
          }}
        >
          {timeAgo(run.completedAt ?? run.startedAt)}
        </span>
      </div>

      {/* Title */}
      <h3
        style={{
          margin: 0,
          fontSize: 'var(--v4-text-lg)',
          fontWeight: 600,
          lineHeight: 1.3,
          color: 'var(--v4-text)',
          letterSpacing: '-0.01em',
        }}
      >
        {run.runTitle ?? 'Untitled run'}
      </h3>

      {/* Body — score + signals */}
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: score != null ? 'auto 1fr' : '1fr',
          gap: 16,
          marginTop: 14,
          alignItems: 'center',
        }}
      >
        {score != null && (
          <div
            style={{
              display: 'inline-flex',
              flexDirection: 'column',
              alignItems: 'center',
              padding: '6px 10px',
              background: 'var(--v4-surface-sunken)',
              border: '1px solid var(--v4-hairline)',
              borderRadius: 12,
              minWidth: 64,
            }}
          >
            <span
              style={{
                fontSize: 'var(--v4-text-2xl)',
                fontWeight: 700,
                lineHeight: 1,
                fontVariantNumeric: 'tabular-nums',
                color:
                  score >= 80
                    ? 'var(--v4-success)'
                    : score >= 60
                      ? 'var(--v4-warn)'
                      : 'var(--v4-fail)',
              }}
            >
              {Math.round(score)}
            </span>
            <span
              style={{
                fontSize: 9.5,
                fontWeight: 600,
                letterSpacing: '0.08em',
                textTransform: 'uppercase',
                color: 'var(--v4-text-subtle)',
                marginTop: 2,
              }}
            >
              {run.verifiedScore != null ? 'verified' : 'est. score'}
            </span>
          </div>
        )}
        <div
          style={{
            display: 'flex',
            flexWrap: 'wrap',
            gap: 8,
            fontSize: 'var(--v4-text-xs)',
            color: 'var(--v4-text-muted)',
          }}
        >
          {run.buildOk != null && (
            <Chip tone={run.buildOk ? 'success' : 'fail'}>build {run.buildOk ? '✓' : '✗'}</Chip>
          )}
          {run.reviewerVerdict && (
            <Chip tone='neutral'>reviewer {run.reviewerVerdict.toLowerCase()}</Chip>
          )}
          {run.testRatio && <Chip tone='neutral'>tests {run.testRatio}</Chip>}
          {run.actualCostUsd != null && run.actualCostUsd > 0 && (
            <Chip tone='neutral'>{fmtCost(run.actualCostUsd)}</Chip>
          )}
          {run.branchName && (
            <Chip tone='neutral'>
              <GitBranch size={10} />
              {run.branchName}
            </Chip>
          )}
        </div>
      </div>

      {/* Footer — primary actions only. Open run is the default; PR if
          available. View report kept on a separate row to avoid 3-action
          crowding on phone. */}
      <div
        style={{
          marginTop: 16,
          display: 'flex',
          gap: 8,
          flexWrap: 'wrap',
        }}
      >
        <Link
          to={
            run.projectId
              ? `/v4/projects/${run.projectId}/runs/${run.runId}`
              : `/v4/runs/${run.runId}`
          }
          className='v4-press'
          style={{
            display: 'inline-flex',
            alignItems: 'center',
            gap: 6,
            padding: '0 14px',
            height: 36,
            background: 'var(--v4-brand)',
            color: '#fff',
            borderRadius: 10,
            fontSize: 'var(--v4-text-sm)',
            fontWeight: 600,
            textDecoration: 'none',
            flex: '1 1 auto',
            justifyContent: 'center',
          }}
        >
          Open run
          <ArrowUpRight size={14} aria-hidden='true' />
        </Link>
        {run.githubPrUrl && (
          <a
            href={run.githubPrUrl}
            target='_blank'
            rel='noreferrer'
            className='v4-press'
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: 6,
              padding: '0 14px',
              height: 36,
              background: 'var(--v4-surface-up)',
              color: 'var(--v4-text)',
              border: '1px solid var(--v4-border)',
              borderRadius: 10,
              fontSize: 'var(--v4-text-sm)',
              fontWeight: 600,
              textDecoration: 'none',
              flex: '1 1 auto',
              justifyContent: 'center',
            }}
          >
            View PR
          </a>
        )}
      </div>
    </article>
  );
}

function Chip({
  tone,
  children,
}: {
  tone: 'success' | 'fail' | 'warn' | 'neutral';
  children: React.ReactNode;
}) {
  const colour =
    tone === 'success'
      ? 'var(--v4-success)'
      : tone === 'fail'
        ? 'var(--v4-fail)'
        : tone === 'warn'
          ? 'var(--v4-warn)'
          : 'var(--v4-text-muted)';
  return (
    <span
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 4,
        padding: '3px 8px',
        background: 'var(--v4-surface-sunken)',
        border: '1px solid var(--v4-hairline)',
        borderRadius: 9999,
        color: colour,
        fontSize: 11,
        fontWeight: 500,
        whiteSpace: 'nowrap',
      }}
    >
      {children}
    </span>
  );
}
