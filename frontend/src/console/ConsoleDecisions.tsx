import {useCallback, useEffect, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import * as api from '@/lib/api';
import {IdeBadge, statusKind} from '@/ide/IdeKit';
import {StatusDot} from '@/ide/IdeIcons';
import {relTime, runTitleFor} from '@/signal/lib/runUtils';

const TERMINAL = ['COMPLETE', 'COMPLETED', 'FAILED', 'ERROR', 'CANCELLED', 'PARTIAL'];
const isTerminal = (s?: string | null) => TERMINAL.includes((s || '').toUpperCase());
const isFailed = (s?: string | null) =>
  ['FAILED', 'ERROR', 'CANCELLED'].includes((s || '').toUpperCase());

function decisionFor(
  r: api.RunSummary,
  prPending: number,
): {label: string; to: string; tone: 'ok' | 'warn' | 'bad'} {
  const status = (r.status || '').toUpperCase();
  if (status === 'AWAITING_APPROVAL') {
    return {label: 'Approve plan', to: `/plan/${r.runId}`, tone: 'warn'};
  }
  if (prPending > 0) {
    return {
      label: `${prPending} PR comment${prPending === 1 ? '' : 's'}`,
      to: `/result/${r.runId}`,
      tone: 'warn',
    };
  }
  if (!isTerminal(r.status)) return {label: 'Watch progress', to: `/live/${r.runId}`, tone: 'warn'};
  if (isFailed(r.status)) return {label: 'Fix failure', to: `/result/${r.runId}`, tone: 'bad'};
  return {label: 'Review result', to: `/result/${r.runId}`, tone: 'ok'};
}

function DecisionRow({r, prPending = 0}: {r: api.RunSummary; prPending?: number}) {
  const navigate = useNavigate();
  const decision = decisionFor(r, prPending);
  return (
    <button className='con-runrow con-decision-row' onClick={() => navigate(decision.to)}>
      <StatusDot kind={statusKind(r.status)} size={9} />
      <span className='con-runrow-main'>
        <span className='con-runrow-title'>{runTitleFor(r)}</span>
        <span className='con-runrow-meta'>
          {(r.projectName || 'Sandbox') + ' · ' + relTime(r.startedAt)}
          {r.actualCostUsd != null && r.actualCostUsd > 0
            ? ` · $${r.actualCostUsd.toFixed(2)}`
            : ''}
        </span>
      </span>
      {r.verifiedScore != null && (
        <IdeBadge kind={r.verifiedScore >= 85 ? 'ok' : r.verifiedScore >= 70 ? 'warn' : 'bad'}>
          {r.verifiedScore}
        </IdeBadge>
      )}
      <IdeBadge kind={decision.tone}>{decision.label}</IdeBadge>
    </button>
  );
}

export default function ConsoleDecisions() {
  const [runs, setRuns] = useState<api.RunSummary[]>([]);
  const [gate, setGate] = useState<api.RunSummary[]>([]);
  const [prPending, setPrPending] = useState<Record<string, number>>({});
  const [loading, setLoading] = useState(true);

  const load = useCallback(() => {
    setLoading(true);
    api
      .getRuns({size: 30})
      .then((r) => {
        const list = r.content ?? [];
        setRuns(list);
        const withPr = list.filter((x) => x.scmPrUrl || x.githubPrUrl).slice(0, 10);
        return Promise.all(
          withPr.map((x) =>
            api
              .getPrCommentPendingCount(x.runId)
              .then((c) => [x.runId, c.count ?? 0] as const)
              .catch(() => [x.runId, 0] as const),
          ),
        );
      })
      .then((pairs) => {
        const next: Record<string, number> = {};
        for (const [id, n] of pairs) if (n > 0) next[id] = n;
        setPrPending(next);
      })
      .catch(() => {})
      .finally(() => setLoading(false));
    api
      .getRuns({status: 'AWAITING_APPROVAL', size: 25})
      .then((r) => setGate(r.content ?? []))
      .catch(() => {});
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const gateIds = new Set(gate.map((r) => r.runId));
  const active = runs.filter((r) => !isTerminal(r.status) && !gateIds.has(r.runId));
  const pendingPrRuns = runs.filter(
    (r) => prPending[r.runId] && !gate.some((g) => g.runId === r.runId),
  );
  const failures = runs.filter((r) => isFailed(r.status) && !gateIds.has(r.runId)).slice(0, 8);
  const hasAny =
    gate.length > 0 || active.length > 0 || pendingPrRuns.length > 0 || failures.length > 0;

  return (
    <div className='con-home con-decisions'>
      <section className='con-decision-hero'>
        <div>
          <h1 className='con-hero-title'>Decisions</h1>
          <div className='con-hero-sub'>
            The work that needs human attention: plan approvals, running work, PR review comments,
            and failures.
          </div>
        </div>
        <button className='con-newrun lg' onClick={load}>
          Refresh
        </button>
      </section>

      {loading && !hasAny ? (
        <div className='con-empty'>Loading decisions…</div>
      ) : !hasAny ? (
        <div className='con-empty'>Nothing needs attention right now.</div>
      ) : (
        <>
          {gate.length > 0 && (
            <section className='con-section'>
              <div className='con-section-title attn'>Approve plans</div>
              {gate.map((r) => (
                <DecisionRow key={r.runId} r={r} />
              ))}
            </section>
          )}
          {active.length > 0 && (
            <section className='con-section'>
              <div className='con-section-title'>In progress</div>
              {active.map((r) => (
                <DecisionRow key={r.runId} r={r} />
              ))}
            </section>
          )}
          {pendingPrRuns.length > 0 && (
            <section className='con-section'>
              <div className='con-section-title attn'>PR review comments</div>
              {pendingPrRuns.map((r) => (
                <DecisionRow key={r.runId} r={r} prPending={prPending[r.runId]} />
              ))}
            </section>
          )}
          {failures.length > 0 && (
            <section className='con-section'>
              <div className='con-section-title attn'>Failures</div>
              {failures.map((r) => (
                <DecisionRow key={r.runId} r={r} />
              ))}
            </section>
          )}
        </>
      )}
    </div>
  );
}
