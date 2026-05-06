import {useEffect, useMemo, useState, type ReactNode} from 'react';
import {
  type RunDetail,
  type TaskSummary,
  type LocalPreviewStatus,
  retryTask,
  startPreview,
  stopPreview,
  getPreviewStatus,
} from '@/lib/api';
import {useSse} from '@/hooks/useSse';
import {useSseStore} from '@/stores/useSseStore';
import PlanCard from './PlanCard';
import Pill from './Pill';

/**
 * Right-side panel on RunDetailPage — surfaces the operator views that
 * got cut when the v2 RunDetailTabs (Overview / Plan / Timeline / Live
 * Log / Code) was retired alongside the v2 chrome.
 *
 * Three tabs:
 *   - Plan   — task list with live status; embeds PlanCard inline when
 *              the run is AWAITING_APPROVAL so users hitting the URL
 *              directly can approve without bouncing back to Compose.
 *   - Log    — SSE event stream (tool calls, phase transitions, retry
 *              triggers), tail-follow.
 *   - Preview — auto-preview iframe; falls back to a "Start preview"
 *              CTA when the deploy/preview-engine hasn't kicked in yet.
 *
 * No new backend. Reuses /runs/{id}/events SSE channel + /runs/{id}/preview/*
 * + /runs/{id}/tasks/{taskId}/retry — all of which the backend kept
 * shipping when v2 RunDetailTabs was deleted.
 */
export default function RunSidePanel({
  runId,
  detail,
  onRefetch,
}: {
  runId: string;
  detail: RunDetail | null;
  onRefetch?: () => void;
}) {
  const status = detail?.status ?? '';
  const isInFlight =
    !['COMPLETE', 'PARTIAL', 'FAILED', 'CANCELLED', 'ABORTED'].includes(status);
  const isAwaitingApproval = status === 'AWAITING_APPROVAL';

  // Default tab: gate first if AWAITING_APPROVAL; preview last if terminal-ok; else Plan.
  const [tab, setTab] = useState<'plan' | 'log' | 'preview'>('plan');
  useEffect(() => {
    if (isAwaitingApproval) setTab('plan');
    else if (status === 'COMPLETE') setTab('preview');
  }, [isAwaitingApproval, status]);

  return (
    <aside
      style={{
        background: 'var(--v3-surface)',
        border: '1px solid var(--v3-border)',
        borderRadius: 8,
        display: 'flex',
        flexDirection: 'column',
        overflow: 'hidden',
        minHeight: 480,
      }}
      aria-label='Run side panel'
    >
      <TabBar
        tab={tab}
        onChange={setTab}
        showInFlightDot={isInFlight}
        showApprovalBadge={isAwaitingApproval}
      />
      <div style={{flex: 1, overflow: 'auto'}}>
        {tab === 'plan' && (
          <PlanPane
            runId={runId}
            detail={detail}
            isAwaitingApproval={isAwaitingApproval}
            onApproved={onRefetch}
          />
        )}
        {tab === 'log' && <LogPane runId={runId} live={isInFlight} />}
        {tab === 'preview' && <PreviewPane runId={runId} runStatus={status} />}
      </div>
    </aside>
  );
}

// ── Tab bar ─────────────────────────────────────────────────────────────

function TabBar({
  tab,
  onChange,
  showInFlightDot,
  showApprovalBadge,
}: {
  tab: 'plan' | 'log' | 'preview';
  onChange: (t: 'plan' | 'log' | 'preview') => void;
  showInFlightDot: boolean;
  showApprovalBadge: boolean;
}) {
  const items: {key: 'plan' | 'log' | 'preview'; label: string; badge?: ReactNode}[] = [
    {
      key: 'plan',
      label: 'Plan',
      badge: showApprovalBadge ? (
        <span
          style={{
            marginLeft: 6,
            fontSize: 9,
            padding: '1px 6px',
            borderRadius: 999,
            background: 'rgba(245,158,11,0.18)',
            color: '#FBBF24',
            border: '1px solid rgba(245,158,11,0.40)',
            fontWeight: 600,
          }}
        >
          Action
        </span>
      ) : null,
    },
    {
      key: 'log',
      label: 'Log',
      badge: showInFlightDot ? (
        <span
          aria-label='live'
          style={{
            marginLeft: 6,
            width: 6,
            height: 6,
            borderRadius: 999,
            background: 'var(--v3-brand)',
            display: 'inline-block',
            animation: 'v3PulseDot 1.4s ease-in-out infinite',
          }}
        />
      ) : null,
    },
    {key: 'preview', label: 'Preview'},
  ];

  return (
    <div
      role='tablist'
      style={{
        display: 'flex',
        borderBottom: '1px solid var(--v3-border)',
        background: 'var(--v3-bg)',
      }}
    >
      {items.map((it) => {
        const isActive = tab === it.key;
        return (
          <button
            key={it.key}
            role='tab'
            type='button'
            aria-selected={isActive}
            onClick={() => onChange(it.key)}
            style={{
              flex: 1,
              padding: '10px 12px',
              fontSize: 12,
              fontWeight: isActive ? 600 : 500,
              color: isActive ? 'var(--v3-text)' : 'var(--v3-text-muted)',
              background: 'transparent',
              border: 'none',
              borderBottom: `2px solid ${isActive ? 'var(--v3-brand)' : 'transparent'}`,
              cursor: 'pointer',
              fontFamily: 'inherit',
              display: 'inline-flex',
              alignItems: 'center',
              justifyContent: 'center',
              transition: 'color 0.12s, border-color 0.12s',
            }}
          >
            {it.label}
            {it.badge}
          </button>
        );
      })}
      <style>{`@keyframes v3PulseDot { 0%,100% {opacity: 0.45} 50% {opacity: 1} }`}</style>
    </div>
  );
}

// ── Plan pane ───────────────────────────────────────────────────────────

function PlanPane({
  runId,
  detail,
  isAwaitingApproval,
  onApproved,
}: {
  runId: string;
  detail: RunDetail | null;
  isAwaitingApproval: boolean;
  onApproved?: () => void;
}) {
  if (!detail) {
    return (
      <div style={{padding: 16, fontSize: 12, color: 'var(--v3-text-subtle)'}}>Loading plan…</div>
    );
  }

  return (
    <div style={{padding: 12, display: 'flex', flexDirection: 'column', gap: 12}}>
      {isAwaitingApproval && (
        <div>
          <PlanCard runId={runId} onApproved={onApproved} />
        </div>
      )}

      {detail.runSummary && !isAwaitingApproval && (
        <div
          style={{
            fontSize: 12,
            color: 'var(--v3-text-muted)',
            lineHeight: 1.55,
            padding: '8px 10px',
            background: 'var(--v3-bg)',
            border: '1px solid var(--v3-border)',
            borderRadius: 6,
          }}
        >
          {detail.runSummary}
        </div>
      )}

      {detail.tasks && detail.tasks.length > 0 && (
        <div>
          <div
            style={{
              fontSize: 11,
              fontWeight: 600,
              color: 'var(--v3-text-subtle)',
              letterSpacing: 0.5,
              textTransform: 'uppercase',
              padding: '0 2px 6px',
            }}
          >
            Tasks ({detail.tasks.length})
          </div>
          <ul style={{margin: 0, padding: 0, listStyle: 'none', display: 'flex', flexDirection: 'column', gap: 6}}>
            {detail.tasks.map((t, i) => (
              <TaskRow key={t.taskId} index={i} task={t} runId={runId} canRetry={!isAwaitingApproval} />
            ))}
          </ul>
        </div>
      )}

      {(!detail.tasks || detail.tasks.length === 0) && !isAwaitingApproval && (
        <div style={{padding: 12, fontSize: 12, color: 'var(--v3-text-subtle)'}}>
          The planner hasn't emitted tasks yet. They'll appear here once the plan is ready.
        </div>
      )}
    </div>
  );
}

const TASK_STATUS_TONE: Record<string, 'ok' | 'err' | 'warn' | 'info' | 'neutral'> = {
  COMPLETE: 'ok',
  COMPLETED: 'ok',
  DONE: 'ok',
  WORKING: 'info',
  PLANNING: 'info',
  RUNNING: 'info',
  EXECUTING: 'info',
  REVIEWING: 'info',
  PARTIAL: 'warn',
  PARTIAL_WITH_WARNINGS: 'warn',
  PENDING: 'neutral',
  QUEUED: 'neutral',
  WAITING: 'neutral',
  FAILED: 'err',
  ERROR: 'err',
  CANCELLED: 'neutral',
};

function TaskRow({
  index,
  task,
  runId,
  canRetry,
}: {
  index: number;
  task: TaskSummary;
  runId: string;
  canRetry: boolean;
}) {
  const [retrying, setRetrying] = useState(false);
  const tone = TASK_STATUS_TONE[task.status?.toUpperCase()] ?? 'neutral';
  const tokens = (task.inputTokens ?? 0) + (task.outputTokens ?? 0);
  const isFailed = ['FAILED', 'ERROR', 'PARTIAL'].includes(task.status?.toUpperCase() ?? '');
  const isRunning = ['WORKING', 'PLANNING', 'RUNNING', 'EXECUTING', 'REVIEWING'].includes(
    task.status?.toUpperCase() ?? '',
  );

  return (
    <li
      style={{
        background: 'var(--v3-bg)',
        border: '1px solid var(--v3-border)',
        borderRadius: 6,
        padding: '8px 10px',
      }}
    >
      <div style={{display: 'flex', alignItems: 'flex-start', gap: 8}}>
        <span
          style={{
            fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
            fontSize: 10,
            color: 'var(--v3-text-subtle)',
            flexShrink: 0,
            minWidth: 18,
          }}
        >
          {String(index + 1).padStart(2, '0')}
        </span>
        <div style={{flex: 1, minWidth: 0}}>
          <div
            style={{
              fontSize: 12,
              color: 'var(--v3-text)',
              fontWeight: 500,
              lineHeight: 1.4,
              wordBreak: 'break-word',
            }}
          >
            {task.title}
          </div>
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 6,
              marginTop: 4,
              flexWrap: 'wrap',
            }}
          >
            <Pill tone={tone} size='sm' dot={isRunning}>
              {(task.status ?? 'pending').toLowerCase().replace(/_/g, ' ')}
            </Pill>
            {tokens > 0 && (
              <span
                style={{
                  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
                  fontSize: 10,
                  color: 'var(--v3-text-subtle)',
                }}
              >
                {tokens.toLocaleString()} tok
              </span>
            )}
            {task.durationMs > 0 && (
              <span
                style={{
                  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
                  fontSize: 10,
                  color: 'var(--v3-text-subtle)',
                }}
              >
                {(task.durationMs / 1000).toFixed(1)}s
              </span>
            )}
            {isFailed && canRetry && (
              <button
                type='button'
                disabled={retrying}
                onClick={async () => {
                  setRetrying(true);
                  try {
                    await retryTask(runId, task.taskId);
                  } catch (e) {
                    alert((e as Error)?.message ?? 'Retry failed');
                  } finally {
                    setRetrying(false);
                  }
                }}
                style={{
                  marginLeft: 'auto',
                  padding: '2px 8px',
                  fontSize: 10.5,
                  fontWeight: 500,
                  background: 'transparent',
                  color: 'var(--v3-brand-hover, var(--v3-brand))',
                  border: '1px solid rgba(91,141,239,0.40)',
                  borderRadius: 4,
                  cursor: retrying ? 'wait' : 'pointer',
                  fontFamily: 'inherit',
                }}
              >
                {retrying ? '…' : 'Retry'}
              </button>
            )}
          </div>
          {task.failureReason && (
            <div
              style={{
                marginTop: 6,
                padding: '4px 8px',
                background: 'rgba(239,68,68,0.10)',
                border: '1px solid rgba(239,68,68,0.30)',
                borderRadius: 4,
                fontSize: 11,
                color: '#FCA5A5',
                lineHeight: 1.4,
              }}
            >
              {task.failureReason}
            </div>
          )}
        </div>
      </div>
    </li>
  );
}

// ── Log pane ────────────────────────────────────────────────────────────

function LogPane({runId, live}: {runId: string; live: boolean}) {
  // Hook the SSE stream into the global store while this pane is mounted.
  // The hook handles connect/reconnect/scoped-token; we just consume entries.
  useSse(live ? runId : null);
  const entries = useSseStore((s) => s.entries);
  const sseStatus = useSseStore((s) => s.status);

  const filtered = useMemo(() => {
    // Last 200 visible — keep DOM bounded.
    return entries.slice(-200).reverse();
  }, [entries]);

  return (
    <div style={{display: 'flex', flexDirection: 'column', height: '100%'}}>
      <div
        style={{
          padding: '6px 12px',
          borderBottom: '1px solid var(--v3-border)',
          fontSize: 11,
          fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
          color: 'var(--v3-text-subtle)',
          background: 'var(--v3-bg)',
          display: 'flex',
          justifyContent: 'space-between',
        }}
      >
        <span>{live ? 'Streaming live · newest first' : 'Final log · newest first'}</span>
        <span style={{textTransform: 'uppercase'}}>{sseStatus}</span>
      </div>
      <div
        style={{
          flex: 1,
          overflow: 'auto',
          padding: 8,
          fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
          fontSize: 11,
          lineHeight: 1.6,
        }}
      >
        {filtered.length === 0 && (
          <div style={{padding: 8, color: 'var(--v3-text-subtle)'}}>
            {live ? 'Waiting for events…' : 'No log captured for this run.'}
          </div>
        )}
        {filtered.map((e) => (
          <div
            key={e.id}
            style={{
              borderBottom: '1px solid var(--v3-border)',
              padding: '4px 4px 6px',
              wordBreak: 'break-word',
            }}
          >
            <span style={{color: 'var(--v3-text-subtle)'}}>
              {new Date(e.timestamp).toLocaleTimeString()}
            </span>{' '}
            <span style={{color: 'var(--v3-brand-hover, var(--v3-brand))', fontWeight: 600}}>
              {e.type}
            </span>
            {Object.keys(e.data ?? {}).length > 0 && (
              <pre
                style={{
                  margin: '2px 0 0',
                  fontSize: 10.5,
                  color: 'var(--v3-text-muted)',
                  whiteSpace: 'pre-wrap',
                }}
              >
                {compactJson(e.data)}
              </pre>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}

function compactJson(d: Record<string, unknown>): string {
  try {
    const keys = Object.keys(d);
    if (keys.length === 0) return '';
    if (keys.length <= 4) {
      return keys
        .map((k) => `${k}=${shortValue(d[k])}`)
        .join(' · ');
    }
    return JSON.stringify(d).slice(0, 240);
  } catch {
    return '';
  }
}
function shortValue(v: unknown): string {
  if (v == null) return 'null';
  if (typeof v === 'string') return v.length > 80 ? v.slice(0, 80) + '…' : v;
  if (typeof v === 'number' || typeof v === 'boolean') return String(v);
  try {
    const s = JSON.stringify(v);
    return s.length > 80 ? s.slice(0, 80) + '…' : s;
  } catch {
    return '<obj>';
  }
}

// ── Preview pane ────────────────────────────────────────────────────────

function PreviewPane({runId, runStatus}: {runId: string; runStatus: string}) {
  const [pv, setPv] = useState<LocalPreviewStatus | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState<'start' | 'stop' | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const isTerminalOk = ['COMPLETE', 'PARTIAL'].includes(runStatus);

  useEffect(() => {
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | null = null;
    const tick = () => {
      getPreviewStatus(runId)
        .then((s) => {
          if (cancelled) return;
          setPv(s);
          setLoading(false);
          // Re-poll while transitioning
          if (
            s.status === 'BUILDING' ||
            s.status === 'PUSHING' ||
            s.status === 'DEPLOYING' ||
            s.status === 'STARTING' ||
            s.status === 'ANALYZING'
          ) {
            timer = setTimeout(tick, 3000);
          }
        })
        .catch(() => {
          if (cancelled) return;
          setLoading(false);
        });
    };
    tick();
    return () => {
      cancelled = true;
      if (timer) clearTimeout(timer);
    };
  }, [runId]);

  async function onStart() {
    setBusy('start');
    setErr(null);
    try {
      const s = await startPreview(runId);
      setPv(s);
    } catch (e) {
      setErr((e as Error)?.message ?? 'Failed to start preview');
    } finally {
      setBusy(null);
    }
  }

  async function onStop() {
    setBusy('stop');
    setErr(null);
    try {
      await stopPreview(runId);
      const s = await getPreviewStatus(runId).catch(() => null);
      setPv(s);
    } catch (e) {
      setErr((e as Error)?.message ?? 'Failed to stop preview');
    } finally {
      setBusy(null);
    }
  }

  if (loading) {
    return (
      <div style={{padding: 16, fontSize: 12, color: 'var(--v3-text-subtle)'}}>
        Loading preview…
      </div>
    );
  }

  if (!isTerminalOk && (!pv || pv.status === 'IDLE')) {
    return (
      <div style={{padding: 16, fontSize: 12, color: 'var(--v3-text-muted)', lineHeight: 1.6}}>
        The preview spins up automatically once the run finishes successfully.
        It exposes a 5-minute live-app URL you can click through to verify behavior.
      </div>
    );
  }

  const url = pv?.url ?? pv?.frontendUrl ?? pv?.backendUrl ?? null;
  const isRunning = pv?.status === 'RUNNING';

  return (
    <div style={{display: 'flex', flexDirection: 'column', height: '100%'}}>
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          padding: '8px 12px',
          borderBottom: '1px solid var(--v3-border)',
          background: 'var(--v3-bg)',
          flexWrap: 'wrap',
        }}
      >
        <Pill tone={isRunning ? 'ok' : pv?.status === 'FAILED' ? 'err' : 'info'} size='sm' dot={!isRunning && pv?.status !== 'FAILED'}>
          {(pv?.status ?? 'idle').toLowerCase().replace(/_/g, ' ')}
        </Pill>
        {pv?.ttlRemainingSeconds != null && pv.ttlRemainingSeconds > 0 && (
          <span
            style={{
              fontSize: 11,
              fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
              color: 'var(--v3-text-subtle)',
            }}
          >
            ttl {Math.round(pv.ttlRemainingSeconds / 60)}m
          </span>
        )}
        <div style={{marginLeft: 'auto', display: 'flex', gap: 6}}>
          {!isRunning && isTerminalOk && (
            <button
              type='button'
              disabled={busy === 'start'}
              onClick={onStart}
              style={btnPrimary(busy === 'start')}
            >
              {busy === 'start' ? 'Starting…' : 'Start preview'}
            </button>
          )}
          {isRunning && url && (
            <a href={url} target='_blank' rel='noopener noreferrer' style={btnGhost()}>
              Open ↗
            </a>
          )}
          {isRunning && (
            <button
              type='button'
              disabled={busy === 'stop'}
              onClick={onStop}
              style={btnGhost(busy === 'stop')}
            >
              {busy === 'stop' ? 'Stopping…' : 'Stop'}
            </button>
          )}
        </div>
      </div>

      {err && (
        <div
          role='alert'
          style={{
            padding: '6px 12px',
            margin: 8,
            background: 'rgba(239,68,68,0.10)',
            border: '1px solid rgba(239,68,68,0.30)',
            color: '#FCA5A5',
            fontSize: 11.5,
            borderRadius: 4,
          }}
        >
          {err}
        </div>
      )}

      {url && isRunning ? (
        <iframe
          title='Run preview'
          src={url}
          style={{
            flex: 1,
            border: 'none',
            background: '#fff',
            minHeight: 320,
          }}
          sandbox='allow-forms allow-scripts allow-same-origin allow-popups'
        />
      ) : (
        <div
          style={{
            flex: 1,
            padding: 16,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            color: 'var(--v3-text-subtle)',
            fontSize: 12,
            textAlign: 'center',
          }}
        >
          {pv?.status === 'FAILED'
            ? pv.errorMessage || 'Preview failed to start.'
            : 'Preview will appear here once it boots.'}
        </div>
      )}
    </div>
  );
}

function btnPrimary(disabled: boolean): React.CSSProperties {
  return {
    padding: '4px 12px',
    fontSize: 11.5,
    fontWeight: 600,
    background: 'var(--v3-brand)',
    color: '#fff',
    border: 'none',
    borderRadius: 4,
    cursor: disabled ? 'wait' : 'pointer',
    fontFamily: 'inherit',
  };
}
function btnGhost(disabled: boolean = false): React.CSSProperties {
  return {
    padding: '4px 12px',
    fontSize: 11.5,
    fontWeight: 500,
    background: 'transparent',
    color: 'var(--v3-text-muted)',
    border: '1px solid var(--v3-border)',
    borderRadius: 4,
    cursor: disabled ? 'wait' : 'pointer',
    fontFamily: 'inherit',
    textDecoration: 'none',
    display: 'inline-flex',
    alignItems: 'center',
  };
}
