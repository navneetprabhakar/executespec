import {useCallback, useEffect, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import * as api from '@/lib/api';
import {IdeBadge, statusKind} from '@/ide/IdeKit';
import {relTime, runTitleFor} from '@/signal/lib/runUtils';
import {PlusIcon, StatusDot} from '@/ide/IdeIcons';

/**
 * ConsoleHome — product cockpit. It avoids implementation nouns on the first
 * screen: users see what needs attention, how to start work, what finished, and
 * which project contexts exist.
 */
const TERMINAL = ['COMPLETE', 'COMPLETED', 'FAILED', 'ERROR', 'CANCELLED', 'PARTIAL'];
const isTerminal = (s?: string | null) => TERMINAL.includes((s || '').toUpperCase());
const isFailed = (s?: string | null) => ['FAILED', 'ERROR'].includes((s || '').toUpperCase());

// C5 — the verified score (the product's headline metric) + cost render on
// every run row; "no project" reads as "Sandbox" (§5 copy).
function scoreKind(s: number): 'ok' | 'warn' | 'bad' {
  return s >= 85 ? 'ok' : s >= 70 ? 'warn' : 'bad';
}

function RunRow({r, to, action}: {r: api.RunSummary; to: string; action?: string}) {
  const navigate = useNavigate();
  return (
    <button className='con-runrow' onClick={() => navigate(to)} title={runTitleFor(r)}>
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
        <IdeBadge kind={scoreKind(r.verifiedScore)}>{r.verifiedScore}</IdeBadge>
      )}
      {action ? (
        <span className='con-runrow-action'>{action}</span>
      ) : (
        <IdeBadge kind={statusKind(r.status)}>{r.status}</IdeBadge>
      )}
    </button>
  );
}

function nextAction(r: api.RunSummary, pendingPr = 0): {label: string; to: string} {
  const status = (r.status || '').toUpperCase();
  if (status === 'AWAITING_APPROVAL') return {label: 'Approve plan', to: `/plan/${r.runId}`};
  if (pendingPr > 0) {
    return {
      label: `Review ${pendingPr} PR comment${pendingPr === 1 ? '' : 's'}`,
      to: `/result/${r.runId}`,
    };
  }
  if (!isTerminal(r.status)) return {label: 'Watch progress', to: `/live/${r.runId}`};
  if (isFailed(r.status)) return {label: 'Fix failure', to: `/result/${r.runId}`};
  if (r.githubPrUrl || r.scmPrUrl) return {label: 'Open result', to: `/result/${r.runId}`};
  return {label: 'Review result', to: `/result/${r.runId}`};
}

export default function ConsoleHome() {
  const navigate = useNavigate();
  const [runs, setRuns] = useState<api.RunSummary[]>([]);
  const [gate, setGate] = useState<api.RunSummary[]>([]);
  const [projects, setProjects] = useState<api.ProjectDto[]>([]);
  const [loading, setLoading] = useState(true);
  // C1.2 (reframe §7 item 3) — PRs with inbound review comments belong in
  // "Needs your attention". The API is per-run only, so this is bounded:
  // pending-counts for the ≤8 recent runs that actually have a PR, fetched
  // once per load (not on the 12s poll).
  const [prPending, setPrPending] = useState<Record<string, number>>({});

  const load = useCallback(() => {
    api
      .getRuns({size: 25})
      .then((r) => {
        const list = r.content ?? [];
        setRuns(list);
        const withPr = list.filter((x) => x.scmPrUrl || x.githubPrUrl).slice(0, 8);
        Promise.all(
          withPr.map((x) =>
            api
              .getPrCommentPendingCount(x.runId)
              .then((c) => [x.runId, c.count ?? 0] as const)
              .catch(() => [x.runId, 0] as const),
          ),
        ).then((pairs) => {
          const next: Record<string, number> = {};
          for (const [id, n] of pairs) if (n > 0) next[id] = n;
          setPrPending(next);
        });
      })
      .catch(() => {})
      .finally(() => setLoading(false));
    api
      .getRuns({status: 'AWAITING_APPROVAL', size: 25})
      .then((r) => setGate(r.content ?? []))
      .catch(() => {});
    api
      .getProjects()
      .then(setProjects)
      .catch(() => {});
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const active = runs.filter((r) => !isTerminal(r.status));
  const recent = runs.filter((r) => isTerminal(r.status)).slice(0, 8);
  const failed = runs.filter((r) => isFailed(r.status)).slice(0, 5);
  const reviewRuns = runs.filter(
    (r) => prPending[r.runId] && !gate.some((g) => g.runId === r.runId),
  );
  const attention = [
    ...gate,
    ...reviewRuns,
    ...failed.filter((f) => !gate.some((g) => g.runId === f.runId) && !prPending[f.runId]),
    ...active.filter((a) => !gate.some((g) => g.runId === a.runId)),
  ].slice(0, 8);

  // Live refresh while anything is in flight (gate waiting or a run active).
  const liveCount = active.length + gate.length;
  useEffect(() => {
    if (liveCount === 0) return;
    const id = window.setInterval(() => {
      if (!document.hidden) load();
    }, 12000);
    return () => window.clearInterval(id);
  }, [liveCount, load]);

  return (
    <div className='con-home'>
      <div className='con-cockpit'>
        <div className='con-hero'>
          <div>
            <div className='con-hero-kicker'>Mission control</div>
            <div className='con-hero-title'>What needs attention?</div>
            <div className='con-hero-sub'>
              Review approvals, watch live work, and start the next code change from one console.
            </div>
          </div>
          <button className='con-newrun lg' onClick={() => navigate('/compose')}>
            <PlusIcon size={16} />
            New work
          </button>
        </div>
        <div className='con-cockpit-metrics'>
          <button className='con-metric-card' onClick={() => navigate('/decisions')}>
            <span className='con-metric-label'>Awaiting decisions</span>
            <strong>{gate.length}</strong>
          </button>
          <button className='con-metric-card' onClick={() => navigate('/runs')}>
            <span className='con-metric-label'>Live work</span>
            <strong>{active.length}</strong>
          </button>
          <button className='con-metric-card' onClick={() => navigate('/projects')}>
            <span className='con-metric-label'>Project contexts</span>
            <strong>{projects.length}</strong>
          </button>
        </div>
      </div>

      <section className='con-section con-section-priority'>
        <div className='con-section-title attn'>
          Attention queue
          <button className='con-section-link' onClick={() => navigate('/decisions')}>
            View decisions
          </button>
        </div>
        {loading ? (
          <div className='con-empty'>Loading work…</div>
        ) : attention.length === 0 ? (
          <div className='con-empty'>No blockers. Start new work or inspect project health.</div>
        ) : (
          attention.map((r) => {
            const action = nextAction(r, prPending[r.runId] || 0);
            return (
              <RunRow key={`attention-${r.runId}`} r={r} to={action.to} action={action.label} />
            );
          })
        )}
      </section>

      <div className='con-home-grid'>
        <section className='con-section'>
          <div className='con-section-title'>Recent outcomes</div>
          {loading ? (
            <div className='con-empty'>Loading…</div>
          ) : recent.length === 0 ? (
            <div className='con-empty'>No completed work yet.</div>
          ) : (
            recent.slice(0, 6).map((r) => {
              const action = nextAction(r);
              return <RunRow key={r.runId} r={r} to={action.to} action={action.label} />;
            })
          )}
        </section>

        <section className='con-section'>
          <div className='con-section-title'>
            Project health
            <button className='con-section-link' onClick={() => navigate('/projects')}>
              View all
            </button>
          </div>
          {projects.length === 0 ? (
            <div className='con-empty'>
              No project contexts yet —{' '}
              <button className='con-inline-link' onClick={() => navigate('/projects')}>
                create one
              </button>{' '}
              to connect repos, architecture, memory, and chat.
            </div>
          ) : (
            <div className='con-proj-grid compact'>
              {projects.slice(0, 6).map((p) => (
                <button
                  key={p.id}
                  className='con-proj-card'
                  onClick={() => navigate(`/projects/${p.id}`)}
                >
                  <span className='con-proj-name'>{p.name}</span>
                  <span className='con-proj-desc'>
                    {p.description || `${p.status || 'ACTIVE'} · open project`}
                  </span>
                </button>
              ))}
            </div>
          )}
        </section>
      </div>
    </div>
  );
}
