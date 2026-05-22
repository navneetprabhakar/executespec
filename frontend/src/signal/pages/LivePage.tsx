import {useEffect, useRef, useState} from 'react';
import {useNavigate, useParams} from 'react-router-dom';
import {TopBar, SectionHeader, IconButton} from '@/signal/components/Chrome';
import {Feed, Card} from '@/signal/components/Primitives';
import {Spinner} from '@/signal/components/Loaders';
import {CostProgressBar} from '@/signal/components/CostProgressBar';
import {LiveHero, LiveHeroAction, StreamCard, TaskLine} from '@/signal/components/Compose';
import {ConfirmModal} from '@/signal/components/Modal';
import {useSse} from '@/hooks/useSse';
import {useSseStore} from '@/stores/useSseStore';
import * as api from '@/lib/api';

type LiveTask = {
  taskId?: string;
  title: string;
  status?: string;
  complexity?: string;
  files?: string[];
  spent?: number;
  iterations?: number;
};

type LiveEvent = {
  ts?: string;
  kind?: 'tool' | 'llm' | 'warn' | 'ok' | 'default';
  topLeft?: string;
  body?: string;
};

export default function LivePage() {
  const {runId} = useParams<{runId?: string}>();
  const navigate = useNavigate();
  const [run, setRun] = useState<api.RunDetail | null>(null);
  const [err, setErr] = useState('');
  const [confirmCancel, setConfirmCancel] = useState(false);
  const [cancelBusy, setCancelBusy] = useState(false);

  // ── Real-time event stream ──
  // useSse subscribes to /api/runs/{id}/events using a scoped JWT and
  // populates useSseStore with task / cost / log entries as the agent runs.
  // The earlier polling-only approach updated every 3s; SSE delivers
  // sub-second updates so the live log + score actually feel live.
  useSse(runId || null);
  const sseEntries = useSseStore((s) => s.entries);
  const sseStatus = useSseStore((s) => s.status);
  const fileTick = useSseStore((s) => s.fileWrittenTickAt);
  // Auto-scroll the live log to the latest entry as the stream comes in.
  const logScrollRef = useRef<HTMLDivElement | null>(null);
  const logBottomRef = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    logBottomRef.current?.scrollIntoView({behavior: 'smooth', block: 'end'});
  }, [sseEntries.length]);

  // Background poll for status transitions + cost updates. SSE drives the log
  // and per-task progress; this is the safety net so we never miss a terminal
  // state if the stream drops (e.g. proxy timeout). 8s cadence — much lighter
  // than the pre-SSE 3s because the live updates aren't on this path.
  useEffect(() => {
    if (!runId) return;
    const id = runId;
    let cancelled = false;
    let timer: number | undefined;
    async function tick() {
      try {
        const r = await api.getRun(id);
        if (cancelled) return;
        setRun(r);
        const status = r?.status as string | undefined;
        if (status === 'COMPLETED' || status === 'PARTIAL') {
          navigate(`/result/${id}`, {replace: true});
          return;
        }
        if (status === 'FAILED' || status === 'CANCELLED') {
          setErr(`Run ${status.toLowerCase()}.`);
          return;
        }
        timer = window.setTimeout(tick, 8000);
      } catch (e: unknown) {
        if (!cancelled) {
          setErr(e instanceof Error ? e.message : 'Failed to load run.');
          timer = window.setTimeout(tick, 12000);
        }
      }
    }
    void tick();
    return () => {
      cancelled = true;
      if (timer) clearTimeout(timer);
    };
  }, [runId, navigate, fileTick]);

  async function performCancel() {
    if (!runId) return;
    setCancelBusy(true);
    try {
      await api.cancelRun(runId);
      navigate('/home');
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Cancel failed.');
    } finally {
      setCancelBusy(false);
      setConfirmCancel(false);
    }
  }

  if (!runId) {
    return (
      <>
        <TopBar title='Live run' sub='no active run' back backTo='/home' />
        <Feed>
          <Card>
            <p style={{fontSize: 14, color: 'var(--signal-text-dim)', margin: 0}}>
              No active run. Start one from the composer.
            </p>
          </Card>
        </Feed>
      </>
    );
  }

  const status = run?.status as string | undefined;
  const tasksRaw = (run as unknown as {tasks?: LiveTask[]})?.tasks || [];
  const completed = tasksRaw.filter((t) => t.status === 'COMPLETED' || t.status === 'DONE').length;
  const total = tasksRaw.length;
  const events = ((run as unknown as {events?: LiveEvent[]})?.events ||
    (run as unknown as {liveLog?: LiveEvent[]})?.liveLog ||
    []) as LiveEvent[];
  const elapsedMs =
    (run as unknown as {elapsedMs?: number; durationMs?: number})?.elapsedMs ||
    (run as unknown as {durationMs?: number})?.durationMs ||
    0;
  const elapsed = formatElapsed(elapsedMs);
  const spent =
    (run as unknown as {actualCost?: number; totalCost?: number})?.actualCost ||
    (run as unknown as {totalCost?: number})?.totalCost ||
    0;
  const userRequest = (run as unknown as {userRequest?: string})?.userRequest || 'Run in progress';

  return (
    <>
      <TopBar
        title={`Live · R-${runId.slice(0, 6)}`}
        sub={`${total ? `${completed}/${total} tasks` : status?.toLowerCase() || 'running'} · ${elapsed}`}
        back
        backTo='/home'
        actions={
          <>
            <IconButton ariaLabel='Cancel' onClick={() => setConfirmCancel(true)}>
              <svg
                viewBox='0 0 24 24'
                style={{width: 18, height: 18}}
                fill='none'
                stroke='currentColor'
                strokeWidth={2}
                strokeLinecap='round'
              >
                <circle cx='12' cy='12' r='10' />
                <line x1='15' y1='9' x2='9' y2='15' />
                <line x1='9' y1='9' x2='15' y2='15' />
              </svg>
            </IconButton>
          </>
        }
      />
      <Feed>
        {err && (
          <div
            style={{
              background: 'var(--signal-coral-soft)',
              color: 'var(--signal-coral-text)',
              border: '1px solid var(--signal-coral)',
              borderRadius: 10,
              padding: '10px 14px',
              marginBottom: 12,
              fontSize: 13,
            }}
          >
            {err}
          </div>
        )}

        <LiveHero
          runId={`R-${runId.slice(0, 6)}`}
          title={userRequest.slice(0, 120)}
          tasks={total ? `${completed}/${total}` : '—'}
          elapsed={elapsed}
          spent={spent ? `$${spent.toFixed(2)}` : '$0.00'}
          // Map backend status to a human phase label. The user lands on Live
          // and sees the reviewer working — calling that "Running now" is a
          // small lie; "Reviewing changes" matches what's on screen.
          phaseLabel={(() => {
            const s = (status || '').toUpperCase();
            if (s === 'CLASSIFYING') return 'Classifying';
            if (s === 'PLANNING') return 'Planning';
            if (s === 'AWAITING_APPROVAL') return 'Awaiting approval';
            if (s === 'REVIEWING') return 'Reviewing changes';
            if (s === 'PROVIDER_SWITCHING') return 'Switching provider';
            if (s === 'PAUSED') return 'Paused';
            return 'Running now';
          })()}
          actions={
            <>
              <LiveHeroAction to='/billing'>Raise cap</LiveHeroAction>
              <LiveHeroAction to='/home'>Background</LiveHeroAction>
            </>
          }
        />

        {/* Live cost vs hard-cap bar — green 0-80%, amber 80-100%, red ≥100%.
            Restores v3 parity (CostProgressBar) so users see how close they
            are to the cap before it's hit. */}
        {((run as unknown as {hardCapUsd?: number})?.hardCapUsd ?? 0) > 0 && (
          <Card style={{padding: 14, marginTop: 12, marginBottom: 4}}>
            <CostProgressBar
              spent={spent || 0}
              cap={(run as unknown as {hardCapUsd: number}).hardCapUsd || 0}
              label='Spend vs hard cap'
            />
          </Card>
        )}

        {tasksRaw.length > 0 && (
          <>
            <SectionHeader>Tasks</SectionHeader>
            <Card>
              {tasksRaw.map((t, i) => (
                <TaskLine
                  key={t.taskId || i}
                  status={
                    t.status === 'COMPLETED' || t.status === 'DONE'
                      ? 'done'
                      : t.status === 'RUNNING' || t.status === 'EXECUTING'
                        ? 'live'
                        : 'todo'
                  }
                  title={t.title || `Task ${i + 1}`}
                  sub={
                    [
                      t.iterations ? `iter ${t.iterations}` : null,
                      t.spent ? `$${t.spent.toFixed(2)}` : null,
                    ]
                      .filter(Boolean)
                      .join(' · ') || undefined
                  }
                  tag={t.complexity || 'AUTO'}
                />
              ))}
            </Card>
          </>
        )}

        {/* Prefer SSE entries when connected — those arrive in real time as
            the agent makes tool calls / LLM completions. Fall back to the
            polled `run.events` blob only when SSE hasn't started yet. The
            log lives in a scroll-locked container so the latest entries
            stay pinned to the bottom while older ones scroll out of view —
            that way you can always see what's happening NOW. */}
        {(() => {
          const sseToShow = sseEntries.slice(-100);
          const useSseLog = sseToShow.length > 0;
          const polledToShow = events.slice(-100);
          const list = useSseLog ? sseToShow : polledToShow;
          const sseConnected = sseStatus === 'CONNECTED';
          const sseLabel = sseConnected
            ? 'live · streaming'
            : sseStatus === 'CONNECTING'
              ? 'connecting…'
              : sseStatus === 'RECONNECTING'
                ? 'reconnecting…'
                : 'polling';
          const dotColor = sseConnected
            ? 'var(--signal-mint)'
            : sseStatus === 'CONNECTING' || sseStatus === 'RECONNECTING'
              ? 'var(--signal-y)'
              : 'var(--signal-text-mute)';
          const sseStatusChip = (
            <span
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: 6,
                fontSize: 11,
                fontFamily: "'JetBrains Mono', monospace",
                color: 'var(--signal-text-dim)',
              }}
            >
              <span
                style={{
                  width: 7,
                  height: 7,
                  borderRadius: '50%',
                  background: dotColor,
                  boxShadow: sseConnected ? '0 0 6px var(--signal-mint)' : 'none',
                }}
              />
              {sseLabel}
            </span>
          );
          if (list.length === 0) {
            return (
              <>
                <SectionHeader action={sseStatusChip}>Live log</SectionHeader>
                <Card
                  style={{display: 'flex', alignItems: 'center', gap: 12, padding: '18px 16px'}}
                >
                  <Spinner size={16} />
                  <p
                    style={{
                      fontSize: 13.5,
                      color: 'var(--signal-text-dim)',
                      margin: 0,
                      lineHeight: 1.5,
                    }}
                  >
                    Waiting for the first tool call… the agent is warming up.
                  </p>
                </Card>
              </>
            );
          }
          return (
            <>
              <SectionHeader action={sseStatusChip}>Live log · {list.length}</SectionHeader>
              <div
                ref={logScrollRef}
                style={{
                  maxHeight: '50vh',
                  overflowY: 'auto',
                  paddingRight: 4,
                  borderRadius: 14,
                  border: '1px solid var(--signal-hairline)',
                  background: 'var(--signal-card-bg)',
                  padding: 8,
                }}
              >
                {list.map((ev, i) => {
                  if (useSseLog) {
                    // SSE entries: {id, type, timestamp, data:{message,payload}}
                    const sse = ev as unknown as {
                      id?: string;
                      type?: string;
                      timestamp?: number;
                      data?: {message?: string; payload?: string};
                    };
                    const t = (sse.type || 'log').toLowerCase();
                    const kind: 'tool' | 'llm' | 'warn' | 'ok' | 'default' = t.includes('tool')
                      ? 'tool'
                      : t.includes('llm') || t.includes('completion')
                        ? 'llm'
                        : t.includes('warn')
                          ? 'warn'
                          : t.includes('complete') || t.includes('ok')
                            ? 'ok'
                            : 'default';
                    const body = sse.data?.message || sse.data?.payload || '';
                    return (
                      <StreamCard
                        key={sse.id || i}
                        kind={kind}
                        topLeft={sse.type || 'log'}
                        ts={sse.timestamp ? new Date(sse.timestamp).toLocaleTimeString() : ''}
                      >
                        {body}
                      </StreamCard>
                    );
                  }
                  const polled = ev as unknown as LiveEvent;
                  return (
                    <StreamCard
                      key={i}
                      kind={
                        (polled.kind as 'tool' | 'llm' | 'warn' | 'ok' | 'default' | undefined) ||
                        'default'
                      }
                      topLeft={polled.topLeft || polled.kind || 'log'}
                      ts={polled.ts || ''}
                    >
                      {polled.body || ''}
                    </StreamCard>
                  );
                })}
                <div ref={logBottomRef} />
              </div>
            </>
          );
        })()}
      </Feed>

      <ConfirmModal
        open={confirmCancel}
        title='Cancel this run?'
        body='The agent will be interrupted immediately. Already-completed tasks are preserved; in-flight changes are discarded. This cannot be undone.'
        confirmLabel='Cancel run'
        danger
        busy={cancelBusy}
        onClose={() => setConfirmCancel(false)}
        onConfirm={performCancel}
      />
    </>
  );
}

function formatElapsed(ms: number): string {
  if (!ms || ms < 0) return '0s';
  const totalS = Math.floor(ms / 1000);
  const m = Math.floor(totalS / 60);
  const s = totalS % 60;
  if (m === 0) return `${s}s`;
  return `${m}m ${s}s`;
}
