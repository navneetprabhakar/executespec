import {useEffect, useState} from 'react';
import {useNavigate, useParams} from 'react-router-dom';
import {TopBar, SectionHeader} from '@/signal/components/Chrome';
import {Feed, Button, Card, KvList} from '@/signal/components/Primitives';
import {LoadingState, SkeletonRow} from '@/signal/components/Loaders';
import {TaskLine} from '@/signal/components/Compose';
import {Glossary} from '@/signal/components/Glossary';
import * as api from '@/lib/api';

type PlanTask = {
  taskId?: string;
  title: string;
  files?: string[];
  complexity?: string;
};

export default function PlanPage() {
  const {runId} = useParams<{runId?: string}>();
  const navigate = useNavigate();
  const [run, setRun] = useState<api.RunDetail | null>(null);
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);

  // Poll the run while it's in CLASSIFYING / PLANNING / AWAITING_APPROVAL.
  useEffect(() => {
    if (!runId) {
      setLoading(false);
      return;
    }
    let cancelled = false;
    let timer: number | undefined;
    async function tick() {
      try {
        const r = await api.getRun(runId!);
        if (cancelled) return;
        setRun(r);
        setLoading(false);
        const status = r?.status as string | undefined;
        // Auto-redirect once the plan gate is closed
        if (status === 'EXECUTING' || status === 'COMPLETED') {
          navigate(status === 'COMPLETED' ? `/result/${runId}` : `/live/${runId}`, {replace: true});
          return;
        }
        if (status === 'FAILED' || status === 'CANCELLED') {
          setErr(`Run ${status.toLowerCase()} during planning.`);
          return;
        }
        if (status !== 'AWAITING_APPROVAL') {
          timer = window.setTimeout(tick, 2000);
        }
      } catch (e: unknown) {
        if (!cancelled) {
          setErr(e instanceof Error ? e.message : 'Failed to load plan.');
          setLoading(false);
        }
      }
    }
    void tick();
    return () => {
      cancelled = true;
      if (timer) clearTimeout(timer);
    };
  }, [runId, navigate]);

  async function approve() {
    if (!runId) return;
    setBusy(true);
    setErr('');
    try {
      await api.approveRun(runId, true);
      navigate(`/live/${runId}`);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Approval failed.');
      setBusy(false);
    }
  }

  async function reject() {
    if (!runId) return;
    setBusy(true);
    try {
      await api.approveRun(runId, false);
      navigate('/compose');
    } catch {
      navigate('/compose');
    }
  }

  // Helpful fallback for users who hit /plan without a runId (deep-link / typo).
  if (!runId) {
    return (
      <>
        <TopBar title='Plan ready' sub='no active run' back backTo='/compose' />
        <Feed>
          <Card>
            <p style={{fontSize: 14, color: 'var(--signal-text-dim)', margin: 0, lineHeight: 1.5}}>
              No active run. Submit a spec from the composer to get a plan.
            </p>
          </Card>
          <Button kind='primary' block to='/compose' style={{marginTop: 12}}>
            Open composer
          </Button>
        </Feed>
      </>
    );
  }

  // Loading state — first poll hasn't returned yet
  if (loading && !run) {
    return (
      <>
        <TopBar title='Plan ready' sub={`R-${runId.slice(0, 6)} · loading`} back backTo='/compose' />
        <Feed>
          <Card>
            <p style={{fontSize: 14, color: 'var(--signal-text-dim)', margin: 0}}>
              Classifying your spec + building a plan… this usually takes 10–20 seconds.
            </p>
          </Card>
        </Feed>
      </>
    );
  }

  const status = run?.status as string | undefined;
  const awaiting = status === 'AWAITING_APPROVAL';
  // RunDetail shape varies by version — try a few common task fields safely.
  const rawTasks = ((run as unknown as {tasks?: PlanTask[]; plan?: {tasks?: PlanTask[]}})
    ?.tasks ||
    (run as unknown as {plan?: {tasks?: PlanTask[]}})?.plan?.tasks ||
    []) as PlanTask[];
  const planSummary =
    (run as unknown as {planSummary?: string})?.planSummary ||
    (run as unknown as {userRequest?: string})?.userRequest ||
    'Plan ready for review.';
  const credits = (run as unknown as {credits?: number; estimatedCredits?: number})?.estimatedCredits || (run as unknown as {credits?: number})?.credits || 3;
  const costCap = (run as unknown as {userCostCap?: number; costCap?: number})?.costCap || (run as unknown as {userCostCap?: number})?.userCostCap || 2.0;
  const classifier = (run as unknown as {complexity?: string; classification?: string})?.classification ||
    (run as unknown as {complexity?: string})?.complexity || 'SMALL';

  return (
    <>
      <TopBar
        title={awaiting ? 'Plan ready' : 'Plan'}
        sub={`R-${runId.slice(0, 6)} · ${status?.toLowerCase() || 'unknown'}`}
        back
        backTo='/home'
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

        <div className='signal-composer-card'>
          <div style={{position: 'relative', paddingTop: 16}}>
            <div
              style={{
                fontSize: 11,
                fontWeight: 700,
                letterSpacing: '0.16em',
                textTransform: 'uppercase',
                color: 'var(--signal-coral)',
                marginBottom: 10,
              }}
            >
              {awaiting ? 'Plan · approve to start' : `Plan · ${status?.toLowerCase() || 'pending'}`}
            </div>
            <h3 style={{fontSize: 20, fontWeight: 600, letterSpacing: '-0.02em', lineHeight: 1.25, margin: '0 0 4px'}}>
              {planSummary.slice(0, 160)}
            </h3>
            <div style={{fontSize: 13.5, color: 'var(--signal-text-dim)', marginBottom: 18}}>
              {rawTasks.length} {rawTasks.length === 1 ? 'task' : 'tasks'} · <Glossary term='classifier'>classifier</Glossary> read this as {classifier}.
            </div>

            <div
              style={{
                background: 'var(--signal-y-soft)',
                borderRadius: 14,
                padding: '12px 16px',
                marginBottom: 16,
                display: 'flex',
                gap: 20,
                alignItems: 'center',
              }}
            >
              <div style={{flex: 1}}>
                <div style={{fontSize: 10, fontWeight: 700, letterSpacing: '0.1em', textTransform: 'uppercase', color: 'var(--signal-y-text)', opacity: 0.75}}>Cost</div>
                <div style={{fontSize: 24, fontWeight: 700, color: 'var(--signal-y-text)', letterSpacing: '-0.02em'}}>{credits} credits</div>
              </div>
              <div style={{textAlign: 'right'}}>
                <div style={{fontSize: 10, fontWeight: 700, letterSpacing: '0.1em', textTransform: 'uppercase', color: 'var(--signal-y-text)', opacity: 0.75}}>Cap</div>
                <div style={{fontSize: 24, fontWeight: 700, color: 'var(--signal-y-text)', letterSpacing: '-0.02em'}}>${costCap.toFixed(2)}</div>
              </div>
            </div>

            <div style={{marginBottom: 18}}>
              {rawTasks.length === 0 ? (
                <p style={{fontSize: 13.5, color: 'var(--signal-text-dim)', margin: 0}}>
                  Plan body still resolving… the page will update as soon as the planner finishes.
                </p>
              ) : (
                rawTasks.map((t, i) => (
                  <TaskLine
                    key={t.taskId || i}
                    title={t.title || `Task ${i + 1}`}
                    sub={Array.isArray(t.files) ? t.files.join(' · ') : undefined}
                    tag={t.complexity || 'AUTO'}
                  />
                ))
              )}
            </div>

            <Button kind='mint' block onClick={approve} disabled={busy || !awaiting}>
              {busy ? 'Approving…' : awaiting ? `Approve · charge ${credits} credits` : `Status: ${status?.toLowerCase() || 'pending'}`}
            </Button>
            <Button kind='ghost' block onClick={reject} disabled={busy}>
              Cancel
            </Button>
          </div>
        </div>

        <SectionHeader>Run details</SectionHeader>
        <Card>
          <KvList
            pairs={[
              {k: 'Run ID', v: runId},
              {k: 'Status', v: status || '—'},
              {k: 'Classification', v: classifier},
              {k: 'Estimated credits', v: String(credits)},
              {k: 'Hard cap', v: `$${costCap.toFixed(2)}`},
            ]}
          />
        </Card>
      </Feed>
    </>
  );
}
