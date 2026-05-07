import {useEffect, useMemo, useRef, useState, type ReactNode} from 'react';
import {
  type RunDetail,
  type TaskSummary,
  type LocalPreviewStatus,
  type ConversationMessage,
  retryTask,
  startPreview,
  stopPreview,
  getPreviewStatus,
  getLiveCost,
  conversationChat,
} from '@/lib/api';
import {useSse} from '@/hooks/useSse';
import {useSseStore} from '@/stores/useSseStore';
import PlanCard from './PlanCard';
import Pill from './Pill';
import TaskTimeline from '@/components/runs/TaskTimeline';
import TestProgressTracker from '@/components/runs/TestProgressTracker';
import TestReportPanel from '@/components/runs/TestReportPanel';

/**
 * Right-side panel on RunDetailPage — surfaces the operator views that
 * got cut when the v2 RunDetailTabs (Overview / Plan / Timeline / Live
 * Log / Code) was retired alongside the v2 chrome.
 *
 * Six tabs:
 *   - Overview — provider · model · cost · tokens · GitHub PR link, and
 *               a phase rollup (planner / worker / reviewer) when the
 *               cost endpoint is wired. Polls /runs/{id}/cost every 5s
 *               while the run is in flight (mirrors v2 RunDetailTabs).
 *   - Plan    — task list with live status; embeds PlanCard inline when
 *               the run is AWAITING_APPROVAL so users hitting the URL
 *               directly can approve without bouncing back to Compose.
 *   - Timeline — chronological swimlane (TaskTimeline.tsx, reused from
 *               the v2 RunDetailTabs surface).
 *   - Log     — SSE event stream (tool calls, phase transitions, retry
 *               triggers), tail-follow.
 *   - Tests   — TestProgressTracker (live) + TestReportPanel (results)
 *               for the post-run test-gen pipeline. Quiet for runs that
 *               haven't kicked off test gen yet.
 *   - Preview — auto-preview iframe; falls back to a "Start preview"
 *               CTA when the deploy/preview-engine hasn't kicked in yet.
 *   - Chat    — 2026-05-07 — run-scoped chat. Wraps POST /api/conversation
 *               with the run's spec / status / verdict pre-seeded into
 *               the thread so the assistant can answer "what changed?",
 *               "why did this fail?", "summarise the diff" without the
 *               user re-pasting context. Available regardless of project
 *               linkage (works on FREE / unlinked runs too — the chat
 *               endpoint has no tier gate).
 *
 * No new backend. Reuses /runs/{id}/events SSE channel + /runs/{id}/preview/*
 * + /runs/{id}/tasks/{taskId}/retry + /runs/{id}/cost — all of which the
 * backend kept shipping when v2 RunDetailTabs was deleted.
 */
type Tab = 'overview' | 'plan' | 'timeline' | 'log' | 'tests' | 'preview' | 'chat';

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

  // Default tab:
  //   - AWAITING_APPROVAL → Plan (gate is the action)
  //   - in-flight non-gate → Plan (so users can watch tasks march)
  //   - terminal-ok → Overview (cost / PR / verdict at a glance)
  //   - terminal-fail → Plan (what failed where)
  const [tab, setTab] = useState<Tab>('plan');
  useEffect(() => {
    if (isAwaitingApproval) setTab('plan');
    else if (status === 'COMPLETE') setTab('overview');
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
      <div style={{flex: 1, overflow: 'auto', display: 'flex', flexDirection: 'column'}}>
        {tab === 'overview' && <OverviewPane runId={runId} detail={detail} live={isInFlight} />}
        {tab === 'plan' && (
          <PlanPane
            runId={runId}
            detail={detail}
            isAwaitingApproval={isAwaitingApproval}
            onApproved={onRefetch}
          />
        )}
        {tab === 'timeline' && <TimelinePane detail={detail} />}
        {tab === 'log' && <LogPane runId={runId} live={isInFlight} />}
        {tab === 'tests' && <TestsPane runId={runId} status={status} />}
        {tab === 'preview' && <PreviewPane runId={runId} runStatus={status} />}
        {tab === 'chat' && <ChatPane detail={detail} />}
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
  tab: Tab;
  onChange: (t: Tab) => void;
  showInFlightDot: boolean;
  showApprovalBadge: boolean;
}) {
  const items: {key: Tab; label: string; badge?: ReactNode}[] = [
    {key: 'overview', label: 'Overview'},
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
    {key: 'timeline', label: 'Timeline'},
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
    {key: 'tests', label: 'Tests'},
    {key: 'preview', label: 'Preview'},
    {key: 'chat', label: 'Chat'},
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
              padding: '10px 6px',
              fontSize: 11.5,
              fontWeight: isActive ? 600 : 500,
              whiteSpace: 'nowrap',
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
            fontFamily: 'var(--v3-font-mono)',
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
                  fontFamily: 'var(--v3-font-mono)',
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
                  fontFamily: 'var(--v3-font-mono)',
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
          fontFamily: 'var(--v3-font-mono)',
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
          fontFamily: 'var(--v3-font-mono)',
          fontSize: 11,
          lineHeight: 1.6,
        }}
      >
        {filtered.length === 0 && (
          <div style={{padding: 8, color: 'var(--v3-text-subtle)'}}>
            {live ? 'Waiting for events…' : 'No log captured for this run.'}
          </div>
        )}
        {filtered.map((e) => {
          const body = formatEventBody(e.data);
          const tone = typeTone(e.type);
          const taskId =
            typeof (e.data as Record<string, unknown>)?.taskId === 'string'
              ? ((e.data as Record<string, unknown>).taskId as string)
              : null;
          return (
            <div
              key={e.id}
              style={{
                borderBottom: '1px solid var(--v3-border)',
                padding: '5px 4px 7px',
                wordBreak: 'break-word',
                display: 'flex',
                gap: 8,
                alignItems: 'baseline',
              }}
            >
              <span style={{color: 'var(--v3-text-subtle)', flexShrink: 0}}>
                {new Date(e.timestamp).toLocaleTimeString()}
              </span>
              <span
                style={{
                  color: tone.fg,
                  background: tone.bg,
                  border: `1px solid ${tone.bd}`,
                  fontWeight: 600,
                  padding: '0 5px',
                  borderRadius: 3,
                  fontSize: 10,
                  letterSpacing: 0.4,
                  whiteSpace: 'nowrap',
                  flexShrink: 0,
                }}
              >
                {e.type}
              </span>
              {taskId && (
                <span
                  style={{
                    color: 'var(--v3-text-subtle)',
                    fontSize: 10,
                    flexShrink: 0,
                  }}
                >
                  {taskId}
                </span>
              )}
              {body && (
                <span
                  style={{
                    color: 'var(--v3-text-muted)',
                    fontSize: 11,
                    flex: 1,
                    minWidth: 0,
                    whiteSpace: 'pre-wrap',
                  }}
                >
                  {body}
                </span>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}

/**
 * 2026-05-07 — humanise the SSE event body. The backend wraps every
 * event in `{type, runId, taskId, payload, timestamp}`; the metadata
 * is already rendered separately (timestamp + type pill on the row,
 * runId implicit from the page, taskId rendered as a chip when set),
 * so the body only needs to surface `payload`. Pre-fix the formatter
 * fell through to `JSON.stringify(data).slice(0, 240)` for any event
 * with ≥5 keys — every LOG / TOKEN_USAGE / RUN_STATUS event hit that
 * branch and rendered as a wall of raw JSON. The user reported "the
 * live log had better presentation earlier than just showing JSON."
 *
 * Strategy:
 *   - If payload is a string, render it verbatim (most events: LOG
 *     human-readable text, TOKEN_USAGE summary, RUN_STATUS status name).
 *   - If payload is an object, compactly key=value-join it (drift-safe
 *     fallback for new event types we haven't styled yet).
 *   - Otherwise drop everything that's already covered by the chips.
 */
function formatEventBody(d: Record<string, unknown> | null | undefined): string {
  if (!d) return '';
  const payload = (d as Record<string, unknown>).payload;
  if (typeof payload === 'string') return payload;
  if (payload == null) {
    // No payload — surface remaining fields except the ones already
    // chipped at the row level so we don't double-print metadata.
    const remaining = Object.entries(d).filter(
      ([k]) => k !== 'type' && k !== 'runId' && k !== 'timestamp' && k !== 'taskId',
    );
    if (remaining.length === 0) return '';
    return remaining.map(([k, v]) => `${k}=${shortValue(v)}`).join(' · ');
  }
  if (typeof payload === 'object') {
    try {
      const entries = Object.entries(payload as Record<string, unknown>);
      if (entries.length <= 4) {
        return entries.map(([k, v]) => `${k}=${shortValue(v)}`).join(' · ');
      }
      return JSON.stringify(payload).slice(0, 240);
    } catch {
      return '';
    }
  }
  return String(payload);
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

/**
 * Colour-code the event-type pill so the user can scan the stream at a
 * glance — RUN_STATUS / GATE_OPENED / VALIDATION_REPORT_READY pop, LOG
 * stays neutral, ERROR / BUDGET_EXCEEDED go red.
 */
function typeTone(type: string): {fg: string; bg: string; bd: string} {
  if (type === 'RUN_STATUS' || type === 'GATE_OPENED' || type === 'VALIDATION_REPORT_READY')
    return {fg: '#7AA5F5', bg: 'rgba(91,141,239,0.10)', bd: 'rgba(91,141,239,0.36)'};
  if (
    type === 'BUDGET_EXCEEDED' ||
    type === 'BATCH_FAILED' ||
    type === 'ERROR' ||
    type === 'RUN_FAILED'
  )
    return {fg: '#FCA5A5', bg: 'rgba(239,68,68,0.10)', bd: 'rgba(239,68,68,0.36)'};
  if (type === 'TOKEN_USAGE' || type === 'COST')
    return {fg: '#FBBF24', bg: 'rgba(245,158,11,0.10)', bd: 'rgba(245,158,11,0.36)'};
  if (type === 'BATCH_PROGRESS' || type === 'BATCH_COMPLETE' || type === 'RUN_COMPLETE')
    return {fg: '#34D399', bg: 'rgba(16,185,129,0.10)', bd: 'rgba(16,185,129,0.36)'};
  // LOG + everything else — neutral chip.
  return {
    fg: 'var(--v3-text-muted)',
    bg: 'var(--v3-bg)',
    bd: 'var(--v3-border)',
  };
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
              fontFamily: 'var(--v3-font-mono)',
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

// ── Overview pane ───────────────────────────────────────────────────────
//
// Compact key-value rollup of the run's high-level signals: provider,
// model, cost, tokens, GitHub PR link, batch badge. Polls /runs/{id}/cost
// every 5s while in flight (mirrors v2 RunDetailTabs.OverviewTab) so cost
// shown matches the live spend, not the mount-time DB snapshot.

function OverviewPane({
  runId,
  detail,
  live,
}: {
  runId: string;
  detail: RunDetail | null;
  live: boolean;
}) {
  const [liveCost, setLiveCost] = useState<number | null>(null);
  const [liveTokens, setLiveTokens] = useState<{input: number; output: number} | null>(null);

  useEffect(() => {
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | null = null;

    const poll = async () => {
      try {
        const res = await getLiveCost(runId);
        if (cancelled) return;
        if (typeof res.costUsd === 'number') setLiveCost(res.costUsd);
        if (typeof res.inputTokens === 'number' && typeof res.outputTokens === 'number') {
          setLiveTokens({input: res.inputTokens, output: res.outputTokens});
        }
      } catch {
        // 404s pre-plan-approval are expected; swallow + retry.
      }
      if (!cancelled && live) {
        timer = setTimeout(poll, 5000);
      }
    };
    poll();
    return () => {
      cancelled = true;
      if (timer) clearTimeout(timer);
    };
  }, [runId, live]);

  if (!detail) {
    return <div style={paneEmptyStyle}>Loading overview…</div>;
  }

  // Run-total tokens — computed from agent + per-task usage since RunDetail
  // doesn't carry an aggregate field. Live cost endpoint returns the same
  // sum (planner + reviewer + worker tasks + classifier/outline/expansion/
  // coherence phases) so liveTokens, when present, wins.
  const taskInputSum = (detail.tasks ?? []).reduce((s, t) => s + (t.inputTokens || 0), 0);
  const taskOutputSum = (detail.tasks ?? []).reduce((s, t) => s + (t.outputTokens || 0), 0);
  const agentInputSum = (detail.agentTokens ?? []).reduce(
    (s, a) => s + (a.inputTokens || 0),
    0,
  );
  const agentOutputSum = (detail.agentTokens ?? []).reduce(
    (s, a) => s + (a.outputTokens || 0),
    0,
  );
  const dbInputTokens = taskInputSum + agentInputSum;
  const dbOutputTokens = taskOutputSum + agentOutputSum;
  const inputTokens = Math.max(liveTokens?.input ?? 0, dbInputTokens);
  const outputTokens = Math.max(liveTokens?.output ?? 0, dbOutputTokens);
  const totalTokens = inputTokens + outputTokens;
  const cost = liveCost ?? detail.actualCostUsd ?? null;
  const estLow = detail.costEstimateLow ?? null;
  const estHigh = detail.costEstimateHigh ?? null;
  const cap = detail.hardCapUsd ?? null;

  // Provider on RunDetail is `aiProvider`. Per-task `model` is the per-call
  // model (planner / worker tier resolves at dispatch); RunDetail itself
  // doesn't carry a single model — pull the most-recent task's model as a
  // best-effort summary, fall back to '—'.
  const provider = detail.aiProvider ?? null;
  const lastTaskModel =
    (detail.tasks ?? []).map((t) => t.model).filter((m): m is string => !!m).pop() ?? null;
  const isBatch = detail.approvedExecutionMode === 'BATCH';
  const prUrl = detail.githubPrUrl ?? detail.scmPrUrl ?? null;

  return (
    <div style={{padding: 12, display: 'flex', flexDirection: 'column', gap: 12}}>
      {/* Title (run spec) */}
      {detail.userRequest && (
        <div
          style={{
            fontSize: 12,
            color: 'var(--v3-text-muted)',
            lineHeight: 1.55,
            padding: '8px 10px',
            background: 'var(--v3-bg)',
            border: '1px solid var(--v3-border)',
            borderRadius: 6,
            whiteSpace: 'pre-wrap',
            wordBreak: 'break-word',
          }}
        >
          {detail.userRequest.length > 280
            ? detail.userRequest.slice(0, 280) + '…'
            : detail.userRequest}
        </div>
      )}

      <KvSection label='Run'>
        <Kv k='Status' v={detail.status ?? '—'} />
        <Kv k='Provider' v={provider ? PROVIDER_LABEL[provider.toUpperCase()] ?? provider : '—'} />
        <Kv k='Model' v={lastTaskModel || '—'} mono />
        {isBatch && <Kv k='Mode' v='Batch (queued)' />}
        {detail.startedAt && <Kv k='Started' v={fmtAbsolute(detail.startedAt)} mono />}
        {detail.completedAt && <Kv k='Completed' v={fmtAbsolute(detail.completedAt)} mono />}
        {detail.startedAt && (
          <Kv
            k='Duration'
            v={fmtDuration(detail.startedAt, detail.completedAt ?? undefined)}
            mono
          />
        )}
      </KvSection>

      <KvSection label='Cost'>
        <Kv k='Spent' v={cost != null ? `$${cost.toFixed(4)}` : '—'} mono />
        {(estLow != null || estHigh != null) && (
          <Kv
            k='Estimate'
            v={
              estLow != null && estHigh != null && estLow !== estHigh
                ? `$${estLow.toFixed(2)}–$${estHigh.toFixed(2)}`
                : `$${(estHigh ?? estLow ?? 0).toFixed(2)}`
            }
            mono
          />
        )}
        {cap != null && <Kv k='Hard cap' v={`$${cap.toFixed(2)}`} mono />}
      </KvSection>

      <KvSection label='Tokens'>
        <Kv k='Total' v={fmtNum(totalTokens)} mono />
        <Kv k='Input' v={fmtNum(inputTokens)} mono />
        <Kv k='Output' v={fmtNum(outputTokens)} mono />
      </KvSection>

      {prUrl && (
        <a
          href={prUrl}
          target='_blank'
          rel='noopener noreferrer'
          style={{
            fontSize: 12,
            fontWeight: 600,
            color: 'var(--accent-blue-text, var(--v3-brand))',
            textDecoration: 'none',
            padding: '8px 10px',
            border: '1px solid var(--v3-border)',
            borderRadius: 6,
            background: 'var(--v3-bg)',
            display: 'inline-flex',
            alignItems: 'center',
            gap: 8,
          }}
        >
          Open pull request ↗
        </a>
      )}
    </div>
  );
}

const PROVIDER_LABEL: Record<string, string> = {
  ANTHROPIC: 'Anthropic',
  OPENAI: 'OpenAI',
  GEMINI: 'Gemini',
  GOOGLE: 'Gemini',
};

function KvSection({label, children}: {label: string; children: ReactNode}) {
  return (
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
        {label}
      </div>
      <div
        style={{
          background: 'var(--v3-bg)',
          border: '1px solid var(--v3-border)',
          borderRadius: 6,
          padding: '6px 10px',
          display: 'flex',
          flexDirection: 'column',
          gap: 4,
        }}
      >
        {children}
      </div>
    </div>
  );
}

function Kv({k, v, mono}: {k: string; v: string; mono?: boolean}) {
  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'baseline',
        justifyContent: 'space-between',
        gap: 12,
        fontSize: 12,
      }}
    >
      <span style={{color: 'var(--v3-text-muted)'}}>{k}</span>
      <span
        style={{
          color: 'var(--v3-text)',
          fontFamily: mono ? 'var(--v3-font-mono)' : 'inherit',
          fontSize: mono ? 11.5 : 12,
          textAlign: 'right',
          maxWidth: '60%',
          overflow: 'hidden',
          textOverflow: 'ellipsis',
          whiteSpace: 'nowrap',
        }}
        title={v}
      >
        {v}
      </span>
    </div>
  );
}

function fmtNum(n: number): string {
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(2) + 'M';
  if (n >= 1_000) return (n / 1_000).toFixed(1) + 'K';
  return String(n);
}

function fmtAbsolute(iso: string): string {
  try {
    const d = new Date(iso);
    return d.toLocaleString(undefined, {
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    });
  } catch {
    return iso;
  }
}

function fmtDuration(startIso: string, endIso?: string): string {
  try {
    const start = new Date(startIso).getTime();
    const end = endIso ? new Date(endIso).getTime() : Date.now();
    const ms = Math.max(0, end - start);
    const sec = Math.round(ms / 1000);
    if (sec < 60) return `${sec}s`;
    const min = Math.floor(sec / 60);
    const s = sec % 60;
    if (min < 60) return s ? `${min}m ${s}s` : `${min}m`;
    const hr = Math.floor(min / 60);
    const m = min % 60;
    return m ? `${hr}h ${m}m` : `${hr}h`;
  } catch {
    return '—';
  }
}

const paneEmptyStyle: React.CSSProperties = {
  padding: 16,
  fontSize: 12,
  color: 'var(--v3-text-subtle)',
};

// ── Timeline pane ───────────────────────────────────────────────────────
//
// Reuses the v2 TaskTimeline swimlane. Renders a Gantt-ish bar per task
// (start offset + duration), status-coloured. Hover shows duration +
// model + tokens. Preserves the chronological view that the v2 chrome
// shipped on /runs/{id}#timeline.

function TimelinePane({detail}: {detail: RunDetail | null}) {
  if (!detail) {
    return <div style={paneEmptyStyle}>Loading timeline…</div>;
  }
  if (!detail.tasks || detail.tasks.length === 0) {
    return (
      <div style={paneEmptyStyle}>
        No tasks yet — the timeline appears once the planner emits tasks.
      </div>
    );
  }
  return (
    <div style={{padding: 12}}>
      <TaskTimeline tasks={detail.tasks} runStartedAt={detail.startedAt ?? null} />
    </div>
  );
}

// ── Tests pane ──────────────────────────────────────────────────────────
//
// TestProgressTracker (live progress while test gen is in flight) +
// TestReportPanel (results when COMPLETE). Both components handle their
// own quiet states for runs that haven't kicked off test gen yet, so we
// render them unconditionally.

function TestsPane({runId, status}: {runId: string; status: string}) {
  // Both components self-hide when there's no test signal — leaves a blank
  // pane on every run that hasn't kicked off test gen, which is most of
  // them. Fallback hint explains the surface so the empty state isn't a
  // dead end.
  return (
    <div style={{padding: 12, display: 'flex', flexDirection: 'column', gap: 12}}>
      <div
        style={{
          fontSize: 11,
          fontWeight: 600,
          color: 'var(--v3-text-subtle)',
          letterSpacing: 0.5,
          textTransform: 'uppercase',
          padding: '0 2px',
        }}
      >
        Integration tests
      </div>
      <TestProgressTracker runId={runId} status={status} />
      <TestReportPanel runId={runId} status={status} />
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
        {status === 'COMPLETE' || status === 'PARTIAL' || status === 'FAILED'
          ? 'Test generation runs after the build completes — if no report is shown, this run did not trigger the test pipeline.'
          : 'Tests will appear here once the run reaches a terminal state.'}
      </div>
    </div>
  );
}

// ── Chat pane ──────────────────────────────────────────────────────────────
//
// 2026-05-07 — run-scoped chat. Wraps POST /api/conversation (the same
// endpoint the pre-run "Refine with assistant" modal uses; no tier gate)
// with the run's spec / status / verdict pre-seeded as conversation
// history so the assistant can answer questions like "what changed?",
// "why did this fail?", "summarise the diff" without the user re-pasting
// context.
//
// We deliberately don't use the legacy `/runs/{id}/chat` endpoint — V2
// returns 410 Gone there per docs/plans/harness-v2-remaining-work.md.
// `/api/conversation` is stateless from the backend's perspective; we
// rebuild the seed context from `detail` on every send so a stale tab
// can't drift away from the truth.

function ChatPane({detail}: {detail: RunDetail | null}) {
  const [messages, setMessages] = useState<ConversationMessage[]>([]);
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const threadRef = useRef<HTMLDivElement | null>(null);

  // Auto-scroll thread on each new message.
  useEffect(() => {
    threadRef.current?.scrollTo({top: threadRef.current.scrollHeight, behavior: 'smooth'});
  }, [messages, busy]);

  // Build a single-shot context preface every time we send so the
  // assistant always sees the latest run state. Cheap (a few hundred
  // chars at most) and keeps the conversation honest if the run flips
  // mid-chat (e.g. WORKING → COMPLETE).
  const seedContext = useMemo(() => {
    if (!detail) return '';
    const lines = [
      `I just submitted this request to ExecuteSpec: "${detail.userRequest ?? '(no spec)'}"`,
      `Status: ${detail.status ?? 'UNKNOWN'}.`,
    ];
    if (detail.aiProvider) lines.push(`AI provider: ${detail.aiProvider}.`);
    const tasks = detail.tasks ?? [];
    if (tasks.length > 0) {
      const done = tasks.filter((t) => t.status === 'COMPLETE').length;
      lines.push(`Tasks: ${done}/${tasks.length} complete.`);
    }
    if (detail.actualCostUsd != null) {
      lines.push(`Cost so far: $${detail.actualCostUsd.toFixed(4)}.`);
    }
    if (detail.githubPrUrl) {
      lines.push(`Draft PR: ${detail.githubPrUrl}`);
    }
    lines.push(
      'Help me understand what happened in this run, answer follow-up questions, and suggest next steps. Skip the requirements-clarification mode — I already submitted; treat this as a post-run debrief.',
    );
    return lines.join(' ');
  }, [detail]);

  async function send(userText: string) {
    setBusy(true);
    setError(null);
    try {
      const isFirst = messages.length === 0;
      // On the first turn we synthesise a seeded thread so the
      // assistant has run context before the user's first question.
      // Subsequent turns just append to the running thread.
      const thread: ConversationMessage[] = isFirst
        ? [
            {role: 'user', content: seedContext},
            {
              role: 'assistant',
              content:
                'Got it — I have the run context. What would you like to know about this run?',
            },
            {role: 'user', content: userText},
          ]
        : [...messages, {role: 'user', content: userText}];
      const res = await conversationChat(thread);
      // For the first turn we drop the seed pair from the rendered
      // thread (keep them in conversationChat's internal context only)
      // so the user sees a clean "Q → A" without the synthesised
      // preface. The seedContext effect rebuilds it fresh on every
      // call, so this is safe.
      const visible: ConversationMessage[] = isFirst
        ? [
            {role: 'user', content: userText},
            {role: 'assistant', content: res.reply},
          ]
        : [...thread, {role: 'assistant', content: res.reply}];
      setMessages(visible);
    } catch (e) {
      setError((e as Error)?.message ?? 'Chat failed');
    } finally {
      setBusy(false);
    }
  }

  async function onSubmit(e?: React.FormEvent) {
    e?.preventDefault();
    const text = input.trim();
    if (!text || busy) return;
    setInput('');
    await send(text);
  }

  // Quick-question chips help users start a conversation without
  // staring at an empty textbox. Each chip is just a pre-filled
  // first-turn user message; the seed context kicks in on send.
  const QUICK_QUESTIONS = [
    'Summarise what this run changed.',
    detail?.status === 'FAILED' || detail?.status === 'PARTIAL'
      ? 'Why did this run fail and how do I fix it?'
      : 'What should I review before merging?',
    'What follow-up runs would you suggest?',
  ];

  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        flex: 1,
        minHeight: 0,
      }}
    >
      <div
        ref={threadRef}
        style={{
          flex: 1,
          overflowY: 'auto',
          padding: '12px 14px',
          display: 'flex',
          flexDirection: 'column',
          gap: 10,
          minHeight: 200,
        }}
      >
        {messages.length === 0 && (
          <div
            style={{
              textAlign: 'center',
              color: 'var(--v3-text-muted)',
              fontSize: 12.5,
              lineHeight: 1.6,
              padding: '24px 12px',
            }}
          >
            Ask anything about this run — what changed, why a task failed,
            what to ship next. The assistant has the spec, status, and
            cost already loaded.
          </div>
        )}
        {messages.map((m, i) => (
          <ChatBubble key={i} role={m.role} content={m.content} />
        ))}
        {busy && (
          <div
            style={{
              fontSize: 12,
              color: 'var(--v3-text-subtle)',
              padding: '4px 8px',
            }}
          >
            Assistant is thinking…
          </div>
        )}
        {error && (
          <div
            role='alert'
            style={{
              padding: '8px 10px',
              background: 'rgba(239,68,68,0.10)',
              border: '1px solid rgba(239,68,68,0.36)',
              color: '#FCA5A5',
              borderRadius: 6,
              fontSize: 12,
            }}
          >
            {error}
          </div>
        )}
      </div>

      {messages.length === 0 && !busy && (
        <div
          style={{
            display: 'flex',
            flexWrap: 'wrap',
            gap: 6,
            padding: '0 12px 8px',
          }}
        >
          {QUICK_QUESTIONS.map((q, i) => (
            <button
              key={i}
              type='button'
              onClick={() => void send(q)}
              style={{
                padding: '4px 10px',
                background: 'transparent',
                color: 'var(--v3-text-muted)',
                border: '1px solid var(--v3-border)',
                borderRadius: 999,
                fontSize: 11.5,
                fontFamily: 'inherit',
                cursor: 'pointer',
                whiteSpace: 'normal',
                textAlign: 'left',
                lineHeight: 1.4,
              }}
            >
              {q}
            </button>
          ))}
        </div>
      )}

      <form
        onSubmit={onSubmit}
        style={{
          padding: '10px 12px',
          borderTop: '1px solid var(--v3-border)',
          display: 'flex',
          gap: 6,
          background: 'var(--v3-bg)',
        }}
      >
        <input
          type='text'
          value={input}
          onChange={(e) => setInput(e.target.value)}
          placeholder={messages.length === 0 ? 'Ask about this run…' : 'Reply…'}
          disabled={busy || !detail}
          style={{
            flex: 1,
            padding: '7px 10px',
            background: 'var(--v3-surface)',
            color: 'var(--v3-text)',
            border: '1px solid var(--v3-border)',
            borderRadius: 6,
            fontSize: 13,
            fontFamily: 'inherit',
          }}
        />
        <button
          type='submit'
          disabled={!input.trim() || busy || !detail}
          style={{
            padding: '7px 14px',
            fontSize: 12.5,
            fontWeight: 600,
            background: input.trim() && !busy ? 'var(--v3-brand)' : 'var(--v3-surface-elevated)',
            color: input.trim() && !busy ? 'var(--v3-text-on-brand)' : 'var(--v3-text-subtle)',
            border: 'none',
            borderRadius: 6,
            cursor: input.trim() && !busy ? 'pointer' : 'not-allowed',
            fontFamily: 'inherit',
          }}
        >
          Send
        </button>
      </form>
    </div>
  );
}

function ChatBubble({role, content}: {role: 'user' | 'assistant'; content: string}) {
  const isUser = role === 'user';
  return (
    <div style={{display: 'flex', justifyContent: isUser ? 'flex-end' : 'flex-start'}}>
      <div
        style={{
          maxWidth: '88%',
          background: isUser
            ? 'color-mix(in srgb, var(--v3-brand) 16%, transparent)'
            : 'var(--v3-bg)',
          border: `1px solid ${
            isUser ? 'color-mix(in srgb, var(--v3-brand) 40%, transparent)' : 'var(--v3-border)'
          }`,
          borderRadius: 10,
          padding: '7px 10px',
          fontSize: 12.5,
          lineHeight: 1.55,
          color: 'var(--v3-text)',
          whiteSpace: 'pre-wrap',
          wordBreak: 'break-word',
        }}
      >
        {content}
      </div>
    </div>
  );
}
