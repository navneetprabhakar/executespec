import {useEffect, useState} from 'react';
import {useNavigate, useParams} from 'react-router-dom';
import * as api from '@/lib/api';
import {IdeBadge, IdeLoader, statusKind} from '@/ide/IdeKit';

const usd = (n?: number | null) => (n == null ? '—' : `$${n.toFixed(2)}`);
const EXEC = ['EXECUTING', 'RUNNING', 'REVIEWING', 'BUILDING'];
const TERM = ['COMPLETE', 'COMPLETED', 'PARTIAL', 'FAILED', 'ERROR', 'CANCELLED'];

// Plan-approval gate. Polls the run; auto-routes to Live (executing) or Result
// (terminal). When AWAITING_APPROVAL, shows the plan + credits/cap + Approve.
export default function IdePlan() {
  const {runId = ''} = useParams();
  const navigate = useNavigate();
  const [run, setRun] = useState<api.RunDetail | null>(null);
  const [busy, setBusy] = useState('');
  const [err, setErr] = useState('');
  const [feedback, setFeedback] = useState('');
  const [editing, setEditing] = useState(false);

  useEffect(() => {
    if (!runId) return;
    let stop = false;
    const poll = () =>
      api
        .getRun(runId)
        .then((r) => {
          if (stop) return;
          setRun(r);
          const s = (r.status || '').toUpperCase();
          if (EXEC.includes(s)) navigate(`/live/${runId}`, {replace: true});
          else if (TERM.includes(s)) navigate(`/result/${runId}`, {replace: true});
        })
        .catch(() => {});
    poll();
    const t = setInterval(poll, 2000);
    return () => {
      stop = true;
      clearInterval(t);
    };
  }, [runId, navigate]);

  async function approve() {
    setBusy('a');
    setErr('');
    try {
      await api.approveRun(runId, true, run?.creditsCharged ?? undefined);
      navigate(`/live/${runId}`);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Approve failed.');
      setBusy('');
    }
  }
  async function cancel() {
    setBusy('c');
    setErr('');
    try {
      await api.cancelRun(runId);
      navigate('/home');
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Cancel failed.');
      setBusy('');
    }
  }
  // Revise the plan before approving — re-plans with the feedback; the poll
  // loop above picks up the new AWAITING_APPROVAL version.
  async function revise() {
    if (!feedback.trim()) return;
    setBusy('e');
    setErr('');
    try {
      await api.editRunPlan(runId, feedback.trim());
      setFeedback('');
      setEditing(false);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Revision failed.');
    } finally {
      setBusy('');
    }
  }

  const status = (run?.status || '').toUpperCase();
  const awaiting = status === 'AWAITING_APPROVAL';
  const tasks = run?.tasks ?? [];

  return (
    <div className='ide-pane' style={{maxWidth: 820}}>
      <div className='ide-h1'>Plan</div>
      <div className='ide-sub ide-mono'>{runId}</div>

      {!run ? (
        <IdeLoader label='Loading plan…' />
      ) : !awaiting ? (
        <div className='ide-card' style={{display: 'flex', alignItems: 'center', gap: 10}}>
          <IdeBadge kind={statusKind(run.status)}>{run.status}</IdeBadge>
          <span style={{color: 'var(--ide-fg-muted)'}}>
            Planning — classifying + outlining your spec…
          </span>
        </div>
      ) : (
        <>
          {run.runSummary && (
            <div className='ide-card' style={{fontSize: 13, lineHeight: 1.6, marginBottom: 12}}>
              {run.runSummary}
            </div>
          )}
          <div className='ide-card'>
            <div className='ide-kv'>
              <span>Strategy</span>
              <span>{run.planStrategy || '—'}</span>
            </div>
            <div className='ide-kv'>
              <span>Tasks</span>
              <span>{tasks.length}</span>
            </div>
            <div className='ide-kv'>
              <span>Credits to charge</span>
              <span>{run.creditsCharged ?? '—'}</span>
            </div>
            <div className='ide-kv'>
              <span>Estimate</span>
              <span>
                {run.costEstimateLow != null
                  ? `${usd(run.costEstimateLow)} – ${usd(run.costEstimateHigh)}`
                  : '—'}
              </span>
            </div>
            <div className='ide-kv'>
              <span>Hard cap</span>
              <span>{usd(run.hardCapUsd)}</span>
            </div>
            <div
              style={{
                marginTop: 10,
                paddingTop: 10,
                borderTop: '1px solid var(--ide-border)',
                fontSize: 12,
                color: 'var(--ide-fg-muted)',
                lineHeight: 1.5,
              }}
            >
              1 credit ≈ $5 of model spend. Charged only when you approve — and{' '}
              <strong style={{color: 'var(--ide-fg)'}}>fully refunded if the run fails</strong>.
              Spend never exceeds the hard cap.
            </div>
          </div>

          <div className='ide-h2'>Tasks · {tasks.length}</div>
          <div className='ide-card' style={{padding: 0}}>
            {tasks.map((t, i) => (
              <div
                key={t.taskId}
                style={{
                  padding: '10px 16px',
                  borderBottom: i < tasks.length - 1 ? '1px solid var(--ide-border)' : 'none',
                  display: 'flex',
                  gap: 10,
                  alignItems: 'center',
                }}
              >
                <span style={{color: 'var(--ide-fg-muted)', fontSize: 11, width: 18}}>{i + 1}</span>
                <span style={{flex: 1}}>{t.title}</span>
              </div>
            ))}
          </div>

          <div className='ide-h2'>Not quite right?</div>
          {editing ? (
            <div className='ide-card'>
              <textarea
                className='ide-textarea'
                rows={4}
                placeholder='Tell the planner what to change — add or drop tasks, adjust scope, change approach…'
                value={feedback}
                onChange={(e) => setFeedback(e.target.value)}
              />
              <div style={{display: 'flex', gap: 8, marginTop: 8}}>
                <button
                  className='ide-btn'
                  disabled={busy === 'e' || !feedback.trim()}
                  onClick={revise}
                >
                  {busy === 'e' ? 'Revising…' : '↻ Submit revision'}
                </button>
                <button className='ide-btn ghost' onClick={() => setEditing(false)}>
                  Cancel
                </button>
              </div>
            </div>
          ) : (
            <button className='ide-btn ghost' onClick={() => setEditing(true)}>
              ✎ Revise plan
            </button>
          )}

          {err && (
            <div style={{color: 'var(--ide-red)', fontSize: 13, margin: '12px 0'}}>{err}</div>
          )}
          <div style={{display: 'flex', gap: 8, marginTop: 16}}>
            <button className='ide-btn' disabled={busy === 'a'} onClick={approve}>
              {busy === 'a'
                ? 'Approving…'
                : `✓ Approve · charge ${run.creditsCharged ?? 1} credit(s)`}
            </button>
            <button
              className='ide-btn ghost'
              style={{color: 'var(--ide-red)'}}
              disabled={busy === 'c'}
              onClick={cancel}
            >
              Cancel
            </button>
          </div>
        </>
      )}
    </div>
  );
}
