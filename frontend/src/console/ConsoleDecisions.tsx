import {useCallback, useEffect, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import * as api from '@/lib/api';
import {IdeBadge, statusKind} from '@/ide/IdeKit';
import {ScoreBadge} from '@/ide/ScoreBadge';
import {StatusDot} from '@/ide/IdeIcons';
import {relTime, runTitleFor} from '@/signal/lib/runUtils';
import {useAuthStore, selectIsSuperAdmin} from '@/stores/useAuthStore';
import {fmtCost} from '@/lib/utils';
import {
  loadSkips,
  skipDecision,
  unskipDecision,
  skippedIds,
  type DecisionSkip,
} from './decisionSkips';

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

function DecisionRow({
  r,
  prPending = 0,
  skipped = false,
  onSkip,
  onUnskip,
}: {
  r: api.RunSummary;
  prPending?: number;
  skipped?: boolean;
  onSkip?: (runId: string) => void;
  onUnskip?: (runId: string) => void;
}) {
  const navigate = useNavigate();
  const isSuperAdmin = useAuthStore(selectIsSuperAdmin);
  const decision = decisionFor(r, prPending);
  const title = runTitleFor(r);
  return (
    // A row, not a button: the skip control is a second action, and a button inside a button is
    // invalid and unreachable by keyboard.
    <div className='con-runrow con-decision-row'>
      <button
        type='button'
        className='con-decision-open'
        onClick={() => navigate(decision.to)}
        aria-label={`${decision.label}: ${title}`}
      >
        <StatusDot kind={statusKind(r.status)} size={9} />
        <span className='con-runrow-main'>
          <span className='con-runrow-title'>{title}</span>
          <span className='con-runrow-meta'>
            {(r.projectName || 'Standalone') + ' · ' + relTime(r.startedAt)}
            {isSuperAdmin && r.actualCostUsd != null && r.actualCostUsd > 0
              ? ` · ${fmtCost(r.actualCostUsd)}`
              : ''}
          </span>
        </span>
        {r.verifiedScore != null && <ScoreBadge score={r.verifiedScore} />}
        <IdeBadge kind={decision.tone}>{decision.label}</IdeBadge>
      </button>
      {skipped ? (
        <button
          type='button'
          className='ide-btn ghost con-decision-skip'
          onClick={() => onUnskip?.(r.runId)}
          title='Show this decision in your queue again'
        >
          Restore
        </button>
      ) : (
        <button
          type='button'
          className='ide-btn ghost con-decision-skip'
          onClick={() => onSkip?.(r.runId)}
          // The run is untouched; be explicit, because "Skip" alone reads like a run action.
          title='Hide from your queue. The run is not changed, cancelled, or rejected.'
        >
          Skip
        </button>
      )}
    </div>
  );
}

export default function ConsoleDecisions() {
  const [runs, setRuns] = useState<api.RunSummary[]>([]);
  const [gate, setGate] = useState<api.RunSummary[]>([]);
  const [prPending, setPrPending] = useState<Record<string, number>>({});
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(false);
  const [skips, setSkips] = useState<DecisionSkip[]>(() => loadSkips());
  const [showSkipped, setShowSkipped] = useState(false);

  const load = useCallback(() => {
    setLoadError(false);
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
      .catch(() => setLoadError(true))
      .finally(() => setLoading(false));
    api
      .getRuns({status: 'AWAITING_APPROVAL', size: 25})
      .then((r) => setGate(r.content ?? []))
      .catch(() => setLoadError(true));
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const skippedSet = skippedIds(skips);
  const visible = (list: api.RunSummary[]) =>
    showSkipped ? list : list.filter((r) => !skippedSet.has(r.runId));

  const gateIds = new Set(gate.map((r) => r.runId));
  const active = visible(runs.filter((r) => !isTerminal(r.status) && !gateIds.has(r.runId)));
  const pendingPrRuns = visible(
    runs.filter((r) => prPending[r.runId] && !gate.some((g) => g.runId === r.runId)),
  );
  const failures = visible(runs.filter((r) => isFailed(r.status) && !gateIds.has(r.runId))).slice(
    0,
    8,
  );
  const gateVisible = visible(gate);
  // Count only rows a skip is actually hiding right now, so the notice never claims work is
  // hidden when the underlying run has already left the queue on its own.
  const hiddenCount = showSkipped
    ? 0
    : [...gate, ...runs].filter((r) => skippedSet.has(r.runId)).length;
  const hasAny =
    gateVisible.length > 0 || active.length > 0 || pendingPrRuns.length > 0 || failures.length > 0;

  const rowSkipProps = (runId: string) => ({
    skipped: skippedSet.has(runId),
    onSkip: (id: string) => setSkips(skipDecision(id)),
    onUnskip: (id: string) => setSkips(unskipDecision(id)),
  });

  return (
    <div className='con-home con-decisions'>
      <section className='con-decision-hero'>
        <div>
          <h1 className='con-hero-title'>Decisions</h1>
          <div className='con-hero-sub'>
            Plan approvals, running work, PR comments, and failures.
          </div>
        </div>
        {/* Ghost, not accent-filled. Decisions has no commit action of its own,
            and a reload control was rendering as the loudest thing on the page. */}
        <button type='button' className='ide-btn ghost' onClick={load}>
          Refresh
        </button>
      </section>

      {loadError && (
        <div className='con-api-error' role='alert'>
          <span>Decision data could not be loaded.</span>
          <button type='button' className='con-api-error-retry' onClick={load}>
            Retry
          </button>
        </div>
      )}

      {loading && !hasAny ? (
        <div className='con-empty'>Loading decisions…</div>
      ) : loadError ? (
        <div className='con-empty'>Decision data is incomplete until the retry succeeds.</div>
      ) : !hasAny ? (
        <div className='con-empty'>Nothing needs attention right now.</div>
      ) : (
        <>
          {gateVisible.length > 0 && (
            <section className='con-section'>
              <div className='con-section-title attn'>Approve plans</div>
              {gateVisible.map((r) => (
                <DecisionRow key={r.runId} r={r} {...rowSkipProps(r.runId)} />
              ))}
            </section>
          )}
          {active.length > 0 && (
            <section className='con-section'>
              <div className='con-section-title'>In progress</div>
              {active.map((r) => (
                <DecisionRow key={r.runId} r={r} {...rowSkipProps(r.runId)} />
              ))}
            </section>
          )}
          {pendingPrRuns.length > 0 && (
            <section className='con-section'>
              <div className='con-section-title attn'>PR review comments</div>
              {pendingPrRuns.map((r) => (
                <DecisionRow
                  key={r.runId}
                  r={r}
                  prPending={prPending[r.runId]}
                  {...rowSkipProps(r.runId)}
                />
              ))}
            </section>
          )}
          {failures.length > 0 && (
            <section className='con-section'>
              <div className='con-section-title attn'>Failures</div>
              {failures.map((r) => (
                <DecisionRow key={r.runId} r={r} {...rowSkipProps(r.runId)} />
              ))}
            </section>
          )}
        </>
      )}

      {(hiddenCount > 0 || showSkipped) && (
        <div className='con-decision-hidden'>
          <span>
            {showSkipped
              ? 'Showing decisions you hid from this queue. The runs themselves were never changed.'
              : `${hiddenCount} decision${hiddenCount === 1 ? '' : 's'} hidden from your queue. The run${hiddenCount === 1 ? ' is' : 's are'} unchanged.`}
          </span>
          <button type='button' className='ide-btn ghost' onClick={() => setShowSkipped((v) => !v)}>
            {showSkipped ? 'Hide them again' : 'Show hidden'}
          </button>
        </div>
      )}
    </div>
  );
}
