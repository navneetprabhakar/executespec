import {useEffect, useState} from 'react';
import {useNavigate, useParams} from 'react-router-dom';
import {Pencil} from 'lucide-react';
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
import {RequirementGateCard} from '@/ide/RequirementGateCard';
import {PlanReviewDetails} from '@/ide/PlanReviewDetails';
import {writeSpecHandoff} from '@/lib/specHandoff';
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
  const [runLoadError, setRunLoadError] = useState('');
  const [reloadRun, setReloadRun] = useState(0);
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
  const [gateLoadError, setGateLoadError] = useState('');
  const [loadedRequirementGate, setLoadedRequirementGate] = useState<{
    runId: string;
    evidence: api.RequirementGateEvidence | null;
  } | null>(null);
  const [requirementGateLoadError, setRequirementGateLoadError] = useState('');
  const [reloadRequirementGate, setReloadRequirementGate] = useState(0);
  const [verifiedPlan, setVerifiedPlan] = useState<{
    runId: string;
    planJson: string;
    fingerprint?: string;
    error?: string;
  } | null>(null);

  // RR-1459 — the router reuses this component across /plan/:runId, so gate state has to be keyed
  // by runId. Resetting during render (not in an effect) is what makes it synchronous: no frame
  // can paint run A's plan, credits, feedback draft or in-flight busy state underneath run B's
  // route. Without this a user could approve B while reading A.
  const [gateRunId, setGateRunId] = useState(runId);
  if (gateRunId !== runId) {
    setGateRunId(runId);
    setLoadedRun(null);
    setRunLoadError('');
    setBusy('');
    setErr('');
    setFeedback('');
    setEditing(false);
    // RR-819 — the typed acknowledgement is evidence about ONE plan's contract report. Carrying it
    // across a route change would let a user acknowledge run A's breaking change and approve run B.
    setLoadedGate(null);
    setGateLoadError('');
    setBreakingAck('');
    setLoadedRequirementGate(null);
    setRequirementGateLoadError('');
    setVerifiedPlan(null);
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
  const gateContextReady = loadedGate?.runId === runId && !gateLoadError;
  const requirementGate =
    loadedRequirementGate?.runId === runId ? loadedRequirementGate.evidence : null;
  const requirementContextReady =
    loadedRequirementGate?.runId === runId && !requirementGateLoadError;
  const exactPlanFingerprint =
    run &&
    verifiedPlan?.runId === runId &&
    verifiedPlan.planJson === run.planJson &&
    verifiedPlan.fingerprint
      ? verifiedPlan.fingerprint
      : undefined;
  const requirementHasBoundPlan =
    requirementGate?.bindingMode === 'SHADOW' || requirementGate?.bindingMode === 'ENFORCED';
  const requirementPlanIdentityReady =
    !requirementHasBoundPlan ||
    Boolean(exactPlanFingerprint && requirementGate?.exactPlanFingerprint === exactPlanFingerprint);
  const requirementPlanIdentityError =
    requirementHasBoundPlan &&
    exactPlanFingerprint &&
    requirementGate?.exactPlanFingerprint !== exactPlanFingerprint
      ? 'Requirement evidence is bound to a different plan. Retry before approval.'
      : '';
  const requirementBlocksApproval = Boolean(
    requirementGate?.enforced && !requirementGate.approvalReady,
  );
  const gateRequiresAck = gateBlocksApproval(gate);
  const approvalAllowed =
    gateContextReady &&
    requirementContextReady &&
    requirementPlanIdentityReady &&
    !requirementBlocksApproval &&
    canApprove(gate, breakingAck);
  const planIdentityError =
    run && verifiedPlan?.runId === runId && verifiedPlan.planJson === run.planJson
      ? verifiedPlan.error
      : undefined;

  useEffect(() => {
    if (!run || run.runId !== runId) {
      setVerifiedPlan(null);
      return;
    }
    const planJson = run.planJson;
    let cancelled = false;
    setVerifiedPlan(null);
    void planFingerprint(planJson)
      .then((fingerprint) => {
        if (cancelled) return;
        setVerifiedPlan(
          fingerprint
            ? {runId, planJson, fingerprint}
            : {
                runId,
                planJson,
                error: 'This run has no exact plan to verify.',
              },
        );
      })
      .catch((error: unknown) => {
        if (!cancelled) {
          setVerifiedPlan({
            runId,
            planJson,
            error: error instanceof Error ? error.message : 'Exact plan verification failed.',
          });
        }
      });
    return () => {
      cancelled = true;
    };
  }, [run, runId]);

  useEffect(() => {
    if (!runId) return;
    let cancelled = false;
    setRunLoadError('');
    api
      .getRun(runId)
      .then((r) => {
        if (cancelled) return;
        if (r && r.runId === runId) {
          setLoadedRun(r);
          setRunLoadError('');
          return;
        }
        setRunLoadError('The plan response did not match this work item. Retry the request.');
      })
      .catch((error: unknown) => {
        if (!cancelled) {
          setRunLoadError(
            error instanceof Error && error.message
              ? error.message
              : 'The plan could not be loaded. Retry the request.',
          );
        }
      });
    return () => {
      cancelled = true;
    };
  }, [runId, reloadRun]);

  // SSE is the fast path, but plan approval must not depend on an uninterrupted event stream.
  // A run can finish planning between the initial detail read and SSE subscription (or while a
  // laptop briefly loses the stream). In that case the old page stayed on PLANNING forever even
  // though the durable run was already AWAITING_APPROVAL. Poll quickly while planning and retain a
  // low-frequency fallback at the gate so changes made from another client also become visible.
  const durablePlanStatus = (run?.status || '').toUpperCase();
  useEffect(() => {
    if (!runId || !durablePlanStatus) return;
    const status = durablePlanStatus;
    if (isPlanExecutingStatus(status) || isPlanTerminalStatus(status)) return;
    const intervalMs = status === 'AWAITING_APPROVAL' ? 15_000 : 3_000;
    let cancelled = false;
    const timer = window.setInterval(() => {
      void api
        .getRun(runId)
        .then((fresh) => {
          if (!cancelled && fresh?.runId === runId) setLoadedRun(fresh);
        })
        .catch(() => {});
    }, intervalMs);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [runId, durablePlanStatus]);

  // RR-819 — fetch the gate report the server composed for this run. A 204 (no contract touched,
  // the overwhelming majority of runs) resolves to `undefined`, which stores a null report and
  // leaves approval with exactly the friction it had before this existed. A failed fetch is also
  // left null: the server is the enforcement point and answers 409 regardless, so a client-side
  // read failure cannot be treated as proof that no contract gate exists. Keep approval disabled
  // until the exact report (including a legitimate 204/no-gate response) has loaded.
  useEffect(() => {
    if (!runId || durablePlanStatus !== 'AWAITING_APPROVAL') return;
    let cancelled = false;
    setLoadedGate(null);
    setGateLoadError('');
    api
      .getPlanContractGate(runId)
      .then((report) => {
        if (!cancelled) {
          setLoadedGate({runId, report: report ?? null});
          setGateLoadError('');
        }
      })
      .catch((error: unknown) => {
        if (!cancelled) {
          setGateLoadError(
            error instanceof Error && error.message
              ? error.message
              : 'Contract impact could not be loaded.',
          );
        }
      });
    return () => {
      cancelled = true;
    };
  }, [runId, reloadGate, durablePlanStatus]);

  // RequirementIR is immutable evidence about this exact plan generation. Keep it independently
  // keyed by runId and fail closed while it is loading: a missing/ambiguous contract must never be
  // treated as READY merely because the plan itself loaded.
  useEffect(() => {
    if (!runId || durablePlanStatus !== 'AWAITING_APPROVAL') return;
    let cancelled = false;
    setLoadedRequirementGate(null);
    setRequirementGateLoadError('');
    api
      .getRequirementGate(runId)
      .then((evidence) => {
        if (cancelled) return;
        if (evidence && evidence.runId !== runId) {
          setRequirementGateLoadError(
            'Requirement evidence did not match this work item. Retry the request.',
          );
          return;
        }
        setLoadedRequirementGate({runId, evidence: evidence ?? null});
      })
      .catch((error: unknown) => {
        if (!cancelled) {
          setRequirementGateLoadError(
            error instanceof Error && error.message
              ? error.message
              : 'Requirement evidence could not be loaded.',
          );
        }
      });
    return () => {
      cancelled = true;
    };
  }, [runId, reloadRequirementGate, durablePlanStatus, run?.planJson]);

  // RUN_DETAIL replay updates the shared run store. Keep the page-local copy so
  // existing plan rendering and approval actions remain unchanged.
  useEffect(() => {
    if (streamedRun) {
      setLoadedRun(streamedRun);
      setRunLoadError('');
    }
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
      if (!exactPlanFingerprint) {
        setErr(planIdentityError || 'Exact plan verification is still in progress.');
        setBusy('');
        return;
      }
      await api.approveRun(
        runId,
        true,
        approving.creditsCharged ?? undefined,
        undefined,
        exactPlanFingerprint,
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
  function revise() {
    if (!feedback.trim()) return;
    if (gateMutationBlocked()) return;
    const revising = run;
    if (!revising) return;
    setErr('');
    const original = revising.userRequest?.trim();
    if (!original) {
      setErr('The original request is unavailable; copy your changes into a new run manually.');
      return;
    }
    writeSpecHandoff({
      source: 'plan-revision',
      projectId: revising.projectId,
      spec: `${original}\n\nPlan revision requirements:\n${feedback.trim()}`,
    });
    // The canonical Start composer consumes the structured handoff, including project identity.
    // Navigating to /projects/:id?compose=1 was ineffective: the project route only honours
    // location.state.compose, redirected projects with work history to Pulse, and never consumed
    // the revision spec. Always use the actual handoff-aware composer.
    navigate('/compose');
  }

  const status = (run?.status || '').toUpperCase();
  const awaiting = status === 'AWAITING_APPROVAL';
  const tasks = run?.tasks ?? [];

  return (
    <div className='ide-pane' style={{maxWidth: 820}}>
      <h1 className='ide-h1'>Review plan</h1>
      <div className='ide-sub ide-mono'>Work {runId}</div>

      {!run && runLoadError ? (
        <div className='ide-card' role='alert'>
          <div style={{color: 'var(--ide-red)', marginBottom: 10}}>
            The plan could not be loaded. {runLoadError}
          </div>
          <button className='ide-btn ghost' onClick={() => setReloadRun((attempt) => attempt + 1)}>
            Retry loading plan
          </button>
        </div>
      ) : !run ? (
        <IdeLoader label='Loading plan…' />
      ) : !awaiting ? (
        <div className='ide-card'>
          <div
            style={{
              fontSize: 'var(--ide-t-body)',
              color: 'var(--ide-fg-muted)',
              marginBottom: 10,
            }}
          >
            This work is {planStatusPhrase(status)}. Updates automatically.
          </div>
          <div className='rl-row'>
            <IdeBadge kind={statusKind(run.status)}>{run.status}</IdeBadge>
            {planStatusPhrase(status) === 'being planned' && (
              <span style={{color: 'var(--ide-fg-muted)'}}>Classifying and outlining…</span>
            )}
          </div>
        </div>
      ) : (
        <>
          {run.runSummary && (
            <div
              className='ide-card'
              style={{fontSize: 'var(--ide-t-body)', lineHeight: 1.6, marginBottom: 12}}
            >
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
                fontSize: 'var(--ide-t-caption)',
                color: 'var(--ide-fg-muted)',
                lineHeight: 1.5,
              }}
            >
              {/* Compressed to two fragments. Every qualifier is load-bearing and kept verbatim in
                  meaning: credits are RESERVED (not spent), the refund is conditional on FAILURE,
                  and managed-AI debits only on a SUCCESSFUL PLATFORM-KEY call DURING execution. */}
              Run credits: reserved now,{' '}
              <strong style={{color: 'var(--ide-fg)'}}>refunded if the work fails</strong>. Managed
              AI credits: debited per successful platform-key model call while work executes.
            </div>
          </div>

          <div className='ide-card' style={{fontSize: 'var(--ide-t-caption)', lineHeight: 1.5}}>
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                flexWrap: 'wrap',
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

          <div className='ide-h2'>Step contracts</div>
          <PlanReviewDetails planJson={run.planJson} />

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
                  fontSize: 'var(--ide-t-caption)',
                  color: 'var(--ide-fg-muted)',
                  marginTop: 4,
                }}
              >
                {feedback.length} / 2000
              </div>
              {/* Ghost, not accent. Approve is this surface's one commit action; a second filled
                  button here made the page offer two equally-loud "go" affordances at once. */}
              <div className='rl-actions' style={{marginTop: 8}}>
                <button
                  className='ide-btn ghost'
                  disabled={busy !== '' || !feedback.trim()}
                  onClick={revise}
                >
                  Start revised run
                </button>
                <button className='ide-btn ghost' onClick={() => setEditing(false)}>
                  Discard
                </button>
              </div>
            </div>
          ) : (
            <button className='ide-btn ghost' onClick={() => setEditing(true)}>
              <Pencil className='rl-icon' size='1em' strokeWidth={1.75} aria-hidden='true' />
              Revise as new run
            </button>
          )}

          {gateRequiresAck && gate && (
            <PlanContractGateCard
              rows={gate.rows}
              brownfieldHttpRouteRemovals={gate.brownfieldHttpRouteRemovals}
              typed={breakingAck}
              onTypedChange={setBreakingAck}
              disabled={busy !== ''}
            />
          )}

          {requirementGate && <RequirementGateCard evidence={requirementGate} />}

          {(!requirementContextReady || !requirementPlanIdentityReady) && (
            <div
              className='ide-card'
              role={requirementGateLoadError || requirementPlanIdentityError ? 'alert' : 'status'}
            >
              <div
                style={{
                  color:
                    requirementGateLoadError || requirementPlanIdentityError
                      ? 'var(--ide-red)'
                      : 'var(--ide-fg-muted)',
                }}
              >
                {requirementGateLoadError
                  ? `Requirement evidence could not be loaded. ${requirementGateLoadError}`
                  : requirementPlanIdentityError
                    ? requirementPlanIdentityError
                    : 'Checking requirement coverage…'}
              </div>
              {(requirementGateLoadError || requirementPlanIdentityError) && (
                <button
                  className='ide-btn ghost'
                  style={{marginTop: 10}}
                  onClick={() => setReloadRequirementGate((attempt) => attempt + 1)}
                >
                  Retry requirement check
                </button>
              )}
            </div>
          )}

          {!gateContextReady && (
            <div className='ide-card' role={gateLoadError ? 'alert' : 'status'}>
              <div style={{color: gateLoadError ? 'var(--ide-red)' : 'var(--ide-fg-muted)'}}>
                {gateLoadError
                  ? `Contract impact could not be loaded. ${gateLoadError}`
                  : 'Checking contract impact…'}
              </div>
              {gateLoadError && (
                <button
                  className='ide-btn ghost'
                  style={{marginTop: 10}}
                  onClick={() => setReloadGate((attempt) => attempt + 1)}
                >
                  Retry contract check
                </button>
              )}
            </div>
          )}

          {err && (
            <div style={{color: 'var(--ide-red)', fontSize: 'var(--ide-t-body)', margin: '12px 0'}}>
              {err}
            </div>
          )}
          {planIdentityError && (
            <div
              role='alert'
              style={{color: 'var(--ide-red)', fontSize: 'var(--ide-t-body)', margin: '12px 0'}}
            >
              {planIdentityError} Approval is unavailable.
            </div>
          )}
          {/* Wraps at 375px: the approve label carries a server-supplied credit count and must not
              squeeze Cancel below its own word width. */}
          <div className='rl-actions' style={{marginTop: 16}}>
            <button
              className='ide-btn'
              disabled={busy !== '' || !approvalAllowed || !exactPlanFingerprint}
              onClick={approve}
            >
              {busy === 'a'
                ? 'Approving…'
                : `Approve and start work · ${run.creditsCharged ?? 1} credit(s)`}
            </button>
            {/* danger is a ghost that reddens on hover; a permanently red control competes with
                the accent CTA for the eye. */}
            <button className='ide-btn danger' disabled={busy !== ''} onClick={cancel}>
              Cancel
            </button>
          </div>
        </>
      )}
    </div>
  );
}
