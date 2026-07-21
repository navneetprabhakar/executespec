import {useEffect, useState} from 'react';
import {useNavigate, useParams} from 'react-router-dom';
import * as api from '@/lib/api';
import {IdeBadge, IdeLoader, statusKind} from '@/ide/IdeKit';
import {RunTimeline} from '@/ide/RunTimeline';
import {useDocumentTitle} from '@/hooks/useDocumentTitle';
import {
  formatManagedAiPlanMessage,
  formatPlanUsd,
  hasUnlimitedAiCredits,
  isPlanExecutingStatus,
  isPlanTerminalStatus,
  managedAiBadgeKind,
  managedAiStatusLabel,
  planFingerprint,
  planStatusPhrase,
} from '@/ide/planUtils';
import {
  ackPayload,
  approvalConflictMessage,
  canApprove,
  classifyApprovalConflict,
  gateBlocksApproval,
  toGateView,
} from '@/ide/planContractGate';
import {PlanContractGateCard} from '@/ide/PlanContractGateCard';
import {useAuthStore, selectIsSuperAdmin} from '@/stores/useAuthStore';
import {useRunStore} from '@/stores/useRunStore';
import {useSse} from '@/hooks/useSse';

// Plan-approval gate. Loads the work item once, then follows the authenticated,
// replayable run SSE stream; auto-routes to Live (executing) or Result (terminal).
// Keeping this on the same scoped SSE contract as Live/Result means a tab resume
// cannot miss the transition out of AWAITING_APPROVAL (the hook replays from the
// last event id and fetches a durable detail on reconnect).
export default function IdePlan() {
  const {runId = ''} = useParams();
  const navigate = useNavigate();
  const isSuperAdmin = useAuthStore(selectIsSuperAdmin);
  useDocumentTitle('Plan approval');
  const [loadedRun, setLoadedRun] = useState<api.RunDetail | null>(null);
  const [busy, setBusy] = useState('');
  const [err, setErr] = useState('');
  const [feedback, setFeedback] = useState('');
  const [editing, setEditing] = useState(false);
  const [managedAi, setManagedAi] = useState<api.ManagedAiBalance | null>(null);
  // RR-819 — the loaded contract-gate report, carried with the runId it was fetched for so a
  // report belonging to run A can never gate (or un-gate) run B. See `toGateView`.
  const [loadedGate, setLoadedGate] = useState<{
    runId: string;
    report: api.PlanContractGateReport | null;
  } | null>(null);
  const [breakingAck, setBreakingAck] = useState('');
  const [reloadGate, setReloadGate] = useState(0);

  // RR-1459 — the router reuses this component across /plan/:runId, so gate state has to be keyed
  // by runId. Resetting during render (not in an effect) is what makes it synchronous: no frame
  // can paint run A's plan, credits, feedback draft or in-flight busy state underneath run B's
  // route. Without this a user could approve B while reading A.
  const [gateRunId, setGateRunId] = useState(runId);
  if (gateRunId !== runId) {
    setGateRunId(runId);
    setLoadedRun(null);
    setBusy('');
    setErr('');
    setFeedback('');
    setEditing(false);
    // RR-819 — the typed acknowledgement is evidence about ONE plan's contract report. Carrying it
    // across a route change would let a user acknowledge run A's breaking change and approve run B.
    setLoadedGate(null);
    setBreakingAck('');
  }

  useSse(runId || null);
  const streamedRun = useRunStore((state) =>
    state.activeRun?.runId === runId ? state.activeRun : null,
  );

  // RR-1459 — second gate: even a late-resolving fetch or a store write cannot surface a detail
  // that belongs to a different run than the route we are on.
  const run = loadedRun && loadedRun.runId === runId ? loadedRun : null;

  // RR-819 — same discipline for the contract gate: only a report fetched for THIS runId, and
  // whose own runId (when the server sends one) matches the route, can gate this approval.
  const gate =
    loadedGate && loadedGate.runId === runId ? toGateView(runId, loadedGate.report) : null;
  const gateRequiresAck = gateBlocksApproval(gate);
  const approvalAllowed = canApprove(gate, breakingAck);

  useEffect(() => {
    if (!runId) return;
    let cancelled = false;
    api
      .getRun(runId)
      .then((r) => {
        if (!cancelled && r && r.runId === runId) setLoadedRun(r);
      })
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, [runId, navigate]);

  // RR-819 — fetch the gate report the server composed for this run. A 204 (no contract touched,
  // the overwhelming majority of runs) resolves to `undefined`, which stores a null report and
  // leaves approval with exactly the friction it had before this existed. A failed fetch is also
  // left null: the server is the enforcement point and answers 409 regardless, so a client-side
  // read failure cannot be used to approve a gated run, only to lose the explanation.
  useEffect(() => {
    if (!runId) return;
    let cancelled = false;
    api
      .getPlanContractGate(runId)
      .then((report) => {
        if (!cancelled) setLoadedGate({runId, report: report ?? null});
      })
      .catch(() => {
        if (!cancelled) setLoadedGate({runId, report: null});
      });
    return () => {
      cancelled = true;
    };
  }, [runId, reloadGate]);

  // RUN_DETAIL replay updates the shared run store. Keep the page-local copy so
  // existing plan rendering and approval actions remain unchanged.
  useEffect(() => {
    if (streamedRun) setLoadedRun(streamedRun);
  }, [streamedRun]);

  useEffect(() => {
    const status = (run?.status || '').toUpperCase();
    if (isPlanExecutingStatus(status)) navigate(`/live/${runId}`, {replace: true});
    else if (isPlanTerminalStatus(status)) navigate(`/result/${runId}`, {replace: true});
  }, [run, runId, navigate]);

  useEffect(() => {
    api
      .getManagedAiBalance()
      .then(setManagedAi)
      .catch(() => setManagedAi(null));
  }, []);

  // RR-1459 — every mutation is refused unless a plan for THIS runId is loaded, and unless no
  // other gate mutation is already in flight. `run` is null whenever the loaded detail does not
  // belong to the route, so this cannot spend against a plan the approver was not shown.
  function gateMutationBlocked(): boolean {
    return busy !== '' || !run || run.runId !== runId;
  }

  async function approve() {
    if (gateMutationBlocked()) return;
    const approving = run;
    if (!approving) return;
    // RR-819 — refuse locally too. The server is the enforcement point, but firing an approval we
    // already know will 409 would spend a round trip to tell the user what the disabled button
    // already says.
    if (!approvalAllowed) return;
    setBusy('a');
    setErr('');
    try {
      const fingerprint = await planFingerprint(approving.planJson);
      await api.approveRun(
        runId,
        true,
        approving.creditsCharged ?? undefined,
        undefined,
        fingerprint,
        ackPayload(gate, breakingAck),
      );
      navigate(`/live/${runId}`);
    } catch (e: unknown) {
      const serverMessage = e instanceof Error ? e.message : 'Approve failed.';
      const conflict = classifyApprovalConflict(serverMessage);
      setErr(approvalConflictMessage(conflict, serverMessage));
      if (conflict === 'BREAKING_REPORT_STALE') {
        // What was acknowledged is no longer what is on this run. Drop the stale acknowledgement
        // and re-read the report so the user re-acknowledges the change they will actually ship.
        setBreakingAck('');
        setReloadGate((n) => n + 1);
      }
      setBusy('');
    }
  }
  async function cancel() {
    if (gateMutationBlocked()) return;
    setBusy('c');
    setErr('');
    try {
      await api.cancelRun(runId);
      navigate('/runs');
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Cancel failed.');
      setBusy('');
    }
  }
  // Revise the plan before approving — re-plans with the feedback; the poll
  // loop above picks up the new AWAITING_APPROVAL version.
  async function revise() {
    if (!feedback.trim()) return;
    if (gateMutationBlocked()) return;
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
      <h1 className='ide-h1'>Review plan</h1>
      <div className='ide-sub ide-mono'>Work {runId}</div>

      {!run ? (
        <IdeLoader label='Loading plan…' />
      ) : !awaiting ? (
        <div className='ide-card'>
          <div style={{fontSize: 13, color: 'var(--ide-fg-muted)', marginBottom: 10}}>
            This work is {planStatusPhrase(status)} — this page updates automatically.
          </div>
          <div style={{display: 'flex', alignItems: 'center', gap: 10}}>
            <IdeBadge kind={statusKind(run.status)}>{run.status}</IdeBadge>
            {planStatusPhrase(status) === 'being planned' && (
              <span style={{color: 'var(--ide-fg-muted)'}}>
                Planning — classifying + outlining your request…
              </span>
            )}
          </div>
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
              <span>Plan shape</span>
              <span>{run.planStrategy || '—'}</span>
            </div>
            <div className='ide-kv'>
              <span>Steps</span>
              <span>{tasks.length}</span>
            </div>
            <div className='ide-kv'>
              <span>Credits on approval</span>
              <span>{run.creditsCharged ?? '—'}</span>
            </div>
            {isSuperAdmin && (
              <>
                <div className='ide-kv'>
                  <span>Estimate</span>
                  <span>
                    {run.costEstimateLow != null
                      ? `${formatPlanUsd(run.costEstimateLow)} – ${formatPlanUsd(run.costEstimateHigh)}`
                      : '—'}
                  </span>
                </div>
                <div className='ide-kv'>
                  <span>Spend cap</span>
                  <span>{formatPlanUsd(run.hardCapUsd)}</span>
                </div>
              </>
            )}
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
              Run credits reserve the approved work. Managed AI credits debit per successful
              platform-key model call while work executes.{' '}
              <strong style={{color: 'var(--ide-fg)'}}>
                Run credits are refunded if the work fails
              </strong>
              .
            </div>
          </div>

          <div className='ide-card' style={{fontSize: 12.5, lineHeight: 1.5}}>
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                gap: 10,
                marginBottom: 8,
              }}
            >
              <strong style={{color: 'var(--ide-fg-bright)'}}>Managed AI</strong>
              {managedAi && (
                <IdeBadge
                  kind={
                    hasUnlimitedAiCredits(managedAi)
                      ? 'ok'
                      : managedAiBadgeKind(managedAi.routingState)
                  }
                >
                  {managedAiStatusLabel(managedAi)}
                </IdeBadge>
              )}
            </div>
            {managedAi ? (
              <div style={{color: 'var(--ide-fg-muted)'}}>
                {formatManagedAiPlanMessage(managedAi)}
              </div>
            ) : (
              <div style={{color: 'var(--ide-fg-muted)'}}>
                Run credits are charged at approval; managed-AI debit details are unavailable on
                this server profile.
              </div>
            )}
          </div>

          <div className='ide-h2'>
            Work plan · {tasks.length} step{tasks.length === 1 ? '' : 's'}
          </div>
          <div className='ide-card'>
            <RunTimeline tasks={tasks} />
          </div>

          <div className='ide-h2'>Not quite right?</div>
          {editing ? (
            <div className='ide-card'>
              <textarea
                className='ide-textarea'
                rows={4}
                maxLength={2000}
                placeholder='Tell the planner what to change — add or drop tasks, adjust scope, change approach…'
                value={feedback}
                onChange={(e) => setFeedback(e.target.value)}
              />
              <div
                style={{
                  textAlign: 'right',
                  fontSize: 11,
                  color: 'var(--ide-fg-muted)',
                  marginTop: 4,
                }}
              >
                {feedback.length} / 2000
              </div>
              <div style={{display: 'flex', gap: 8, marginTop: 8}}>
                <button
                  className='ide-btn'
                  disabled={busy !== '' || !feedback.trim()}
                  onClick={revise}
                >
                  {busy === 'e' ? 'Revising…' : 'Submit requested changes'}
                </button>
                <button className='ide-btn ghost' onClick={() => setEditing(false)}>
                  Cancel
                </button>
              </div>
            </div>
          ) : (
            <button
              className='ide-btn ghost'
              style={{borderColor: 'var(--ide-accent)', color: 'var(--ide-accent)'}}
              onClick={() => setEditing(true)}
            >
              ✎ Request changes
            </button>
          )}

          {gateRequiresAck && gate && (
            <PlanContractGateCard
              rows={gate.rows}
              typed={breakingAck}
              onTypedChange={setBreakingAck}
              disabled={busy !== ''}
            />
          )}

          {err && (
            <div style={{color: 'var(--ide-red)', fontSize: 13, margin: '12px 0'}}>{err}</div>
          )}
          <div style={{display: 'flex', gap: 8, marginTop: 16}}>
            <button
              className='ide-btn'
              disabled={busy !== '' || !approvalAllowed}
              onClick={approve}
            >
              {busy === 'a'
                ? 'Approving…'
                : `Approve and start work · ${run.creditsCharged ?? 1} credit(s)`}
            </button>
            <button
              className='ide-btn ghost'
              style={{color: 'var(--ide-red)'}}
              disabled={busy !== ''}
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
