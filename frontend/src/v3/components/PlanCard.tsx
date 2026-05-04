import {useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {approveRun, getRun, type RunDetail} from '@/lib/api';
import Pill from './Pill';
import {
  PLAN_GATE_APPROVE_CTA,
  PLAN_GATE_ADJUST_CTA,
  PLAN_GATE_CANCEL_CTA,
  PLAN_GATE_HEADER,
  COST_PREFIX,
} from '../lib/terminology';

/**
 * R-121 — inline plan-approval card.
 *
 * Replaces the v2 modal blocking gate. Renders inside the conversation
 * thread when the run hits AWAITING_APPROVAL. The user reads the plan
 * + cost preview + per-task list right where they're already looking,
 * then clicks Approve & ship without losing context.
 *
 * On mount, lazy-loads the full RunDetail via /api/runs/{id} so we
 * have the planned task list and cost estimate. On approve, calls
 * /api/runs/{id}/approve and morphs the card into "Building…".
 */
interface PlanCardProps {
  runId: string;
  /** Called once approval succeeds so the parent thread can update
   *  the bubble's status (or no-op if SSE handles it). */
  onApproved?: () => void;
}

export default function PlanCard({runId, onApproved}: PlanCardProps) {
  const [detail, setDetail] = useState<RunDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const navigate = useNavigate();

  // Lazy-load on mount
  if (loading && !detail && !error) {
    getRun(runId)
      .then((d) => {
        setDetail(d);
        setLoading(false);
      })
      .catch((e) => {
        setError((e as Error)?.message ?? 'Failed to load plan');
        setLoading(false);
      });
  }

  async function onApprove() {
    if (!detail) return;
    setSubmitting(true);
    setError(null);
    try {
      await approveRun(runId, true, detail.creditsCharged ?? undefined);
      if (onApproved) onApproved();
    } catch (e) {
      setError((e as Error)?.message ?? 'Approval failed');
    } finally {
      setSubmitting(false);
    }
  }

  async function onCancel() {
    if (!confirm('Cancel this run? You can re-submit any time.')) return;
    setSubmitting(true);
    try {
      await approveRun(runId, false);
      if (onApproved) onApproved();
    } catch (e) {
      setError((e as Error)?.message ?? 'Cancel failed');
    } finally {
      setSubmitting(false);
    }
  }

  const costEst =
    detail?.costEstimateLow != null && detail?.costEstimateHigh != null
      ? `${COST_PREFIX} $${detail.costEstimateLow.toFixed(2)}–$${detail.costEstimateHigh.toFixed(2)}`
      : detail?.creditsCharged
        ? `${COST_PREFIX} ${detail.creditsCharged} credits`
        : '';

  return (
    <div
      style={{
        border: '1px solid var(--v3-border-strong)',
        background: 'var(--v3-surface)',
        borderRadius: 8,
        padding: 14,
        maxWidth: 640,
      }}
    >
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          marginBottom: 8,
        }}
      >
        <Pill tone='info'>{PLAN_GATE_HEADER}</Pill>
        {costEst && <span style={{fontSize: 11.5, color: 'var(--v3-text-muted)'}}>{costEst}</span>}
      </div>

      {loading && <div style={{fontSize: 12, color: 'var(--v3-text-subtle)'}}>Loading plan…</div>}

      {error && (
        <div
          role='alert'
          style={{
            padding: '6px 10px',
            background: 'rgba(239,68,68,0.12)',
            border: '1px solid rgba(239,68,68,0.36)',
            color: '#FCA5A5',
            borderRadius: 4,
            fontSize: 12,
          }}
        >
          {error}
        </div>
      )}

      {detail && !loading && (
        <>
          {detail.runTitle && (
            <div
              style={{
                fontSize: 14,
                fontWeight: 600,
                color: 'var(--v3-text)',
                marginBottom: 4,
              }}
            >
              {detail.runTitle}
            </div>
          )}
          {detail.runSummary && (
            <div
              style={{
                fontSize: 12,
                color: 'var(--v3-text-muted)',
                marginBottom: 8,
                lineHeight: 1.5,
              }}
            >
              {detail.runSummary}
            </div>
          )}

          {detail.tasks && detail.tasks.length > 0 && (
            <ul
              style={{
                margin: '8px 0 12px',
                padding: '8px 12px',
                listStyle: 'none',
                background: 'var(--v3-bg)',
                border: '1px solid var(--v3-border)',
                borderRadius: 6,
                fontSize: 12,
              }}
            >
              {detail.tasks.slice(0, 6).map((t, i) => (
                <li
                  key={t.taskId}
                  style={{
                    padding: '3px 0',
                    color: 'var(--v3-text-muted)',
                  }}
                >
                  <span style={{color: 'var(--v3-text-subtle)', marginRight: 8}}>
                    {String(i + 1).padStart(2, '0')}.
                  </span>
                  {t.title}
                </li>
              ))}
              {detail.tasks.length > 6 && (
                <li style={{padding: '3px 0', color: 'var(--v3-text-subtle)', fontSize: 11}}>
                  + {detail.tasks.length - 6} more steps
                </li>
              )}
            </ul>
          )}

          <div style={{display: 'flex', gap: 6, flexWrap: 'wrap', marginTop: 8}}>
            <button
              type='button'
              onClick={onApprove}
              disabled={submitting}
              style={{
                padding: '6px 14px',
                fontSize: 12.5,
                fontWeight: 600,
                background: 'var(--v3-brand)',
                color: 'var(--v3-text-on-brand)',
                border: 'none',
                borderRadius: 6,
                cursor: submitting ? 'not-allowed' : 'pointer',
                fontFamily: 'inherit',
              }}
            >
              {submitting ? 'Submitting…' : PLAN_GATE_APPROVE_CTA}
            </button>
            <button
              type='button'
              onClick={() => navigate(`/projects/${detail.projectId ?? ''}/r/${runId}`)}
              style={{
                padding: '6px 14px',
                fontSize: 12.5,
                fontWeight: 500,
                background: 'transparent',
                color: 'var(--v3-text-muted)',
                border: '1px solid var(--v3-border)',
                borderRadius: 6,
                cursor: 'pointer',
                fontFamily: 'inherit',
              }}
            >
              {PLAN_GATE_ADJUST_CTA}
            </button>
            <button
              type='button'
              onClick={onCancel}
              disabled={submitting}
              style={{
                padding: '6px 14px',
                fontSize: 12.5,
                fontWeight: 500,
                background: 'transparent',
                color: 'var(--v3-text-subtle)',
                border: '1px solid var(--v3-border)',
                borderRadius: 6,
                cursor: submitting ? 'not-allowed' : 'pointer',
                fontFamily: 'inherit',
              }}
            >
              {PLAN_GATE_CANCEL_CTA}
            </button>
          </div>
        </>
      )}
    </div>
  );
}
