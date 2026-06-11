import {useCallback, useEffect, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import * as api from '@/lib/api';
import {IdeBadge, statusKind} from '@/ide/IdeKit';
import {relTime, runTitleFor} from '@/signal/lib/runUtils';
import {PlusIcon, StatusDot} from '@/ide/IdeIcons';

/**
 * ConsoleHome — the control-plane dashboard (RL-2026-05-052, web reframe). Replaces
 * the IDE "welcome" with mission control: a Start-a-run hero, a Needs-your-attention
 * block (plan gates + failures), Active runs (live), Recent runs, and Projects.
 * Rendered at /home only when the console flag is on (App.tsx). Reuses the existing
 * api + the --ide-* themed card styles.
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
      <div className='con-hero'>
        <div>
          <div className='con-hero-title'>Ship a change from a spec</div>
          <div className='con-hero-sub'>
            Describe what you want. ExecuteSpec plans it, you approve the plan, then it codes,
            tests, and opens a pull request with a verified score.
          </div>
        </div>
        <button className='con-newrun lg' onClick={() => navigate('/compose')}>
          <PlusIcon size={16} />
          Start a new run
        </button>
      </div>

      {(gate.length > 0 || failed.length > 0 || Object.keys(prPending).length > 0) && (
        <section className='con-section'>
          <div className='con-section-title attn'>Needs your attention</div>
          {gate.map((r) => (
            <RunRow key={r.runId} r={r} to={`/plan/${r.runId}`} action='Approve →' />
          ))}
          {runs
            .filter((r) => prPending[r.runId] && !gate.some((g) => g.runId === r.runId))
            .map((r) => (
              <RunRow
                key={`pr-${r.runId}`}
                r={r}
                to={`/result/${r.runId}`}
                action={`${prPending[r.runId]} review comment${prPending[r.runId] === 1 ? '' : 's'} →`}
              />
            ))}
          {failed
            .filter((f) => !gate.some((g) => g.runId === f.runId) && !prPending[f.runId])
            .map((r) => (
              <RunRow key={r.runId} r={r} to={`/result/${r.runId}`} />
            ))}
        </section>
      )}

      {active.length > 0 && (
        <section className='con-section'>
          <div className='con-section-title'>Active runs</div>
          {active.map((r) => (
            <RunRow key={r.runId} r={r} to={`/live/${r.runId}`} />
          ))}
        </section>
      )}

      <section className='con-section'>
        <div className='con-section-title'>Recent runs</div>
        {loading ? (
          <div className='con-empty'>Loading…</div>
        ) : recent.length === 0 ? (
          <div className='con-empty'>No runs yet — start your first one above.</div>
        ) : (
          recent.map((r) => <RunRow key={r.runId} r={r} to={`/result/${r.runId}`} />)
        )}
      </section>

      <section className='con-section'>
        <div className='con-section-title'>
          Projects
          <button className='con-section-link' onClick={() => navigate('/projects')}>
            View all
          </button>
        </div>
        {projects.length === 0 ? (
          <div className='con-empty'>
            No projects yet —{' '}
            <button className='con-inline-link' onClick={() => navigate('/projects')}>
              create one
            </button>{' '}
            to group runs by repo.
          </div>
        ) : (
          <div className='con-proj-grid'>
            {projects.slice(0, 8).map((p) => (
              <button
                key={p.id}
                className='con-proj-card'
                onClick={() => navigate(`/projects/${p.id}`)}
              >
                <span className='con-proj-name'>{p.name}</span>
                {p.description ? <span className='con-proj-desc'>{p.description}</span> : null}
              </button>
            ))}
          </div>
        )}
      </section>
    </div>
  );
}
