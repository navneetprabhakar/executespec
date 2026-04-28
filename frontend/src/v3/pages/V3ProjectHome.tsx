import { useEffect, useMemo, useRef } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useV3ProjectStore, type Bubble } from '../stores/useV3ProjectStore';
import Composer from '../components/Composer';
import PlanCard from '../components/PlanCard';
import ResultCard from '../components/ResultCard';
import Pill from '../components/Pill';
import {
  PROJECT_PLURAL,
  PROVIDER_DISPLAY,
  STATUS_LABELS,
} from '../lib/terminology';

/**
 * R-120 — the dominant screen.
 *
 * Slack/Linear chat shape. The project's runs accumulate as bubbles in
 * a scrolling thread; the composer pins to the bottom. Empty projects
 * surface the "What do you want to build?" hero so the first action
 * is always submit-a-run.
 *
 * Renders:
 *   - One-row pulse summary across the top (status + provider + last run)
 *   - Conversation thread: user spec bubbles + assistant run bubbles
 *   - Plan cards inline on AWAITING_APPROVAL bubbles (R-121)
 *   - Result cards inline on COMPLETE/PARTIAL/FAILED bubbles (R-122)
 *   - Composer pinned to the bottom (R-123)
 *
 * SSE → bubble morph wiring lands with R-130; for now bubbles update
 * on next page load (the optimistic Composer push gives near-immediate
 * feedback even without SSE).
 */
export default function V3ProjectHome() {
  const { slug } = useParams<{ slug: string }>();
  const navigate = useNavigate();
  const { loaded, loading, project, bubbles, error, load, reset } = useV3ProjectStore();
  const threadRef = useRef<HTMLDivElement>(null);

  // Load on mount + on slug change
  useEffect(() => {
    if (slug) void load(slug);
    return () => {
      // Don't reset on unmount — switching projects re-loads, and
      // navigating to /v3/p/{slug}/r/{runId} should keep the thread
      // hot for fast back-navigation.
    };
  }, [slug, load]);

  // Auto-scroll to bottom when new bubbles arrive
  useEffect(() => {
    if (threadRef.current) {
      threadRef.current.scrollTop = threadRef.current.scrollHeight;
    }
  }, [bubbles.length]);

  const isEmpty = bubbles.length === 0 && !loading && loaded === slug;

  if (error && !loading) {
    return (
      <div className="v3-placeholder" style={{ borderColor: 'var(--v3-error)', color: 'var(--v3-error)' }}>
        <h1>Couldn't load project</h1>
        <p>{error}</p>
        <button
          type="button"
          onClick={() => slug && load(slug)}
          style={{
            marginTop: 16,
            padding: '6px 14px',
            background: 'var(--v3-brand)',
            color: '#0B0D10',
            border: 'none',
            borderRadius: 6,
            cursor: 'pointer',
            fontSize: 12.5,
            fontWeight: 600,
          }}
        >
          Retry
        </button>
      </div>
    );
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', minHeight: 'calc(100vh - 56px - 48px)' }}>
      {/* Pulse summary — single row */}
      {project && (
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 10,
            padding: '10px 14px',
            background: 'var(--v3-surface)',
            border: '1px solid var(--v3-border)',
            borderRadius: 8,
            marginBottom: 16,
            flexWrap: 'wrap',
            fontSize: 12,
          }}
        >
          <span style={{ color: 'var(--v3-text)', fontWeight: 600 }}>{project.name}</span>
          <Pill tone="brand" size="sm">
            {PROVIDER_DISPLAY[project.aiProvider] ?? project.aiProvider}
          </Pill>
          <Pill
            tone={project.status === 'ACTIVE' ? 'ok' : 'neutral'}
            size="sm"
          >
            {project.status?.toLowerCase()}
          </Pill>
          {bubbles.length > 0 && (
            <span style={{ color: 'var(--v3-text-subtle)' }}>
              · {Math.floor(bubbles.length / 2)} runs in thread
            </span>
          )}
        </div>
      )}

      {/* Thread */}
      <div
        ref={threadRef}
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
          <div style={{ color: 'var(--v3-text-subtle)', fontSize: 12, textAlign: 'center', padding: 32 }}>
            Loading conversation…
          </div>
        )}
        {bubbles.map((b) => (
          <BubbleView key={b.id} bubble={b} />
        ))}
      </div>

      {/* Composer */}
      <Composer
        projectId={slug ?? ''}
        isEmpty={isEmpty}
        defaultProvider={project?.aiProvider}
      />
    </div>
  );
}

// ─── Bubble renderer ─────────────────────────────────────────────────────────

function BubbleView({ bubble }: { bubble: Bubble }) {
  const isUser = bubble.role === 'user';

  if (bubble.kind === 'run' && bubble.runId) {
    const isAwaitingApproval = bubble.status === 'AWAITING_APPROVAL';
    return (
      <div
        style={{
          display: 'flex',
          justifyContent: 'flex-start',
          paddingLeft: 8,
        }}
      >
        {isAwaitingApproval ? (
          <PlanCard runId={bubble.runId} />
        ) : (
          <ResultCard runId={bubble.runId} initialStatus={bubble.status} />
        )}
      </div>
    );
  }

  // text or status bubble
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
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
            <Pill tone="info" dot size="sm">
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
