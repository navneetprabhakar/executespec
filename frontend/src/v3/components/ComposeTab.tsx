import {useEffect, useRef} from 'react';
import {useV3ProjectStore, type Bubble} from '../stores/useV3ProjectStore';
import Composer from './Composer';
import PlanCard from './PlanCard';
import ResultCard from './ResultCard';
import Pill from './Pill';
import RepoContextPanel from './RepoContextPanel';
import CodeReviewPanel from './CodeReviewPanel';
import {STATUS_LABELS} from '../lib/terminology';
import {useLiveRunStatus} from '../hooks/useLiveRunStatus';

/**
 * Single-surface retirement (2026-05-06) — extracted from the now-deleted
 * ProjectHomePage. Renders the chat-thread + composer + repo/code review
 * panels in the canonical project page's "Compose" tab.
 *
 * The component owns its own data fetch (project + runs → bubbles via
 * useV3ProjectStore) so the parent ProjectDashboardPage can mount it
 * unchanged. PlanCard, ResultCard, Composer, RepoContextPanel,
 * CodeReviewPanel, and the run-status polling hook are all reused.
 */
interface Props {
  projectId: string;
  /** From the parent's project fetch — used by the Composer for default
   *  AI provider selection. */
  defaultProvider?: string;
}

export default function ComposeTab({projectId, defaultProvider}: Props) {
  const {loaded, loading, bubbles, load} = useV3ProjectStore();
  const threadRef = useRef<HTMLDivElement>(null);

  // Load on mount + on projectId change. The store dedupes by `loaded`
  // so navigating away and back doesn't re-fetch.
  useEffect(() => {
    if (projectId && loaded !== projectId) void load(projectId);
  }, [projectId, loaded, load]);

  // Auto-scroll to bottom when new bubbles arrive — chat-thread UX.
  useEffect(() => {
    if (threadRef.current) {
      threadRef.current.scrollTop = threadRef.current.scrollHeight;
    }
  }, [bubbles.length]);

  const isEmpty = !loading && bubbles.length === 0;

  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        height: '100%',
        minHeight: 0,
      }}
    >
      <div
        ref={threadRef}
        role='log'
        aria-live='polite'
        aria-relevant='additions'
        aria-busy={loading || undefined}
        aria-label='Run conversation thread'
        style={{
          flex: 1,
          display: 'flex',
          flexDirection: 'column',
          gap: 14,
          padding: '4px 0 16px',
          overflowY: 'auto',
        }}
      >
        {loading && (
          <div
            style={{
              color: 'var(--v3-text-subtle)',
              fontSize: 12,
              textAlign: 'center',
              padding: 32,
            }}
          >
            Loading conversation…
          </div>
        )}
        {/* Audit r5 (#4, 2026-05-06) — repo context preview. */}
        {!loading && projectId && <RepoContextPanel projectId={projectId} />}
        {/* Audit r5 (#8, 2026-05-06) — Code Review (whole-repo scan) UI. */}
        {!loading && projectId && <CodeReviewPanel projectId={projectId} />}
        {bubbles.map((b) => (
          <BubbleView key={b.id} bubble={b} />
        ))}
      </div>
      <Composer projectId={projectId} isEmpty={isEmpty} defaultProvider={defaultProvider} />
    </div>
  );
}

function BubbleView({bubble}: {bubble: Bubble}) {
  const isUser = bubble.role === 'user';

  if (bubble.kind === 'run' && bubble.runId) {
    return (
      <div style={{display: 'flex', justifyContent: 'flex-start', paddingLeft: 8}}>
        <RunBubbleCard runId={bubble.runId} initialStatus={bubble.status} />
      </div>
    );
  }

  return (
    <div
      style={{
        display: 'flex',
        justifyContent: isUser ? 'flex-end' : 'flex-start',
        paddingLeft: isUser ? 0 : 8,
        paddingRight: isUser ? 0 : 8,
      }}
    >
      <div
        style={{
          maxWidth: 640,
          padding: '8px 12px',
          background: isUser ? 'rgba(91,141,239,0.10)' : 'var(--v3-surface)',
          border: isUser ? '1px solid rgba(91,141,239,0.36)' : '1px solid var(--v3-border)',
          borderRadius: isUser ? '8px 8px 0 8px' : '8px 8px 8px 0',
          color: 'var(--v3-text)',
          fontSize: 13,
          lineHeight: 1.55,
          whiteSpace: 'pre-wrap',
        }}
      >
        {bubble.kind === 'status' && bubble.status === 'planning' ? (
          <span style={{display: 'inline-flex', alignItems: 'center', gap: 6}}>
            <Pill tone='info' dot size='sm'>
              {STATUS_LABELS.PLANNING}…
            </Pill>
          </span>
        ) : (
          bubble.text
        )}
      </div>
    </div>
  );
}

/**
 * Polls /api/runs/{runId} via useLiveRunStatus so the bubble renders the
 * correct card for the LIVE status, not the frozen-at-submit-time
 * bubble.status from the Zustand store. AWAITING_APPROVAL → PlanCard;
 * everything else → ResultCard.
 */
function RunBubbleCard({runId, initialStatus}: {runId: string; initialStatus?: string}) {
  const {status, refresh} = useLiveRunStatus(runId, initialStatus);
  const effective = status ?? initialStatus ?? null;
  if (effective === 'AWAITING_APPROVAL') {
    return <PlanCard runId={runId} onApproved={refresh} />;
  }
  return <ResultCard runId={runId} initialStatus={effective ?? undefined} />;
}
