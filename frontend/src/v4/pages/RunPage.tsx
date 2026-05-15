/**
 * v4 Run page — verdict-first mission control, fully self-contained.
 *
 * Layout:
 *   - Back link → project (if scoped) or activity
 *   - LargeTitle (run title + status meta)
 *   - MissionCard hero (verdict pill, score, signals, primary actions)
 *   - <RunDetailTabs /> — the existing v3 detail surface (Overview /
 *     Plan / Log / Code / Timeline / Preview / Chat) embedded inline so
 *     v4 has feature parity with v3 without users ever leaving /v4/*.
 *
 * RunDetailTabs reads from useRunStore; we seed it via
 * setActiveRunId + fetchRun so the existing component picks up its
 * data path with no modifications.
 */
import {useEffect, useState} from 'react';
import {Link, useParams} from 'react-router-dom';
import {ChevronLeft} from 'lucide-react';
import {getRun, type RunDetail} from '@/lib/api';
import {fmtCost, fmtMs} from '@/lib/utils';
import {useRunStore} from '@/stores/useRunStore';
import RunDetailTabs from '@/components/runs/RunDetailTabs';
import LargeTitle from '../components/LargeTitle';
import MissionCard, {type MissionRun} from '../components/MissionCard';
import {useV4Context} from '../lib/useV4Context';

export default function RunPage() {
  const {runId, projectId} = useParams<{runId: string; projectId?: string}>();
  const [run, setRun] = useState<RunDetail | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const setCtx = useV4Context((s) => s.set);
  const clearCtx = useV4Context((s) => s.clear);
  const setActiveRunId = useRunStore((s) => s.setActiveRunId);
  const fetchRun = useRunStore((s) => s.fetchRun);

  // Seed the run store so RunDetailTabs (which reads from useRunStore.activeRun)
  // picks up our run without any changes to its code path.
  useEffect(() => {
    if (!runId) return;
    let cancelled = false;
    setActiveRunId(runId);
    fetchRun(runId).catch(() => undefined);
    getRun(runId)
      .then((r) => {
        if (!cancelled) setRun(r);
      })
      .catch((e) => !cancelled && setErr(String(e)));
    return () => {
      cancelled = true;
    };
  }, [runId, setActiveRunId, fetchRun]);

  useEffect(() => {
    if (run?.runTitle) {
      setCtx({label: run.runTitle, projectId: run.projectId ?? undefined});
    }
    return () => clearCtx();
  }, [run?.runTitle, run?.projectId, setCtx, clearCtx]);

  if (err) {
    return (
      <div className='v4-card' style={{padding: 24, color: 'var(--v4-fail)'}}>
        Couldn't load run: {err}
      </div>
    );
  }
  if (!run) {
    return (
      <p
        style={{
          color: 'var(--v4-text-subtle)',
          padding: 16,
          fontSize: 'var(--v4-text-sm)',
        }}
      >
        Loading run…
      </p>
    );
  }

  const completed =
    run.startedAt && run.completedAt
      ? new Date(run.completedAt).getTime() - new Date(run.startedAt).getTime()
      : null;
  const mission: MissionRun = {
    runId: run.runId,
    runTitle: run.runTitle,
    status: run.status,
    verdict: null,
    verifiedScore: null,
    estimatedScore: null,
    completedAt: run.completedAt ?? null,
    startedAt: run.startedAt ?? null,
    durationMs: completed,
    actualCostUsd: run.actualCostUsd ?? null,
    githubPrUrl: run.githubPrUrl ?? run.scmPrUrl ?? null,
    buildOk: null,
    reviewerVerdict: null,
    testRatio: null,
    projectId: run.projectId ?? projectId ?? null,
  };

  const backTo = projectId ? `/v4/projects/${projectId}` : '/v4/activity';
  const backLabel = projectId ? 'Workspace' : 'Activity';

  return (
    <>
      <Link
        to={backTo}
        className='v4-press'
        style={{
          display: 'inline-flex',
          alignItems: 'center',
          gap: 4,
          padding: '4px 8px 4px 4px',
          marginBottom: 6,
          fontSize: 'var(--v4-text-sm)',
          color: 'var(--v4-text-muted)',
          textDecoration: 'none',
          borderRadius: 8,
        }}
      >
        <ChevronLeft size={16} aria-hidden='true' />
        {backLabel}
      </Link>

      <LargeTitle
        title={run.runTitle ?? 'Run'}
        subtitle={
          <>
            {run.status.toLowerCase()}
            {completed ? ` · ${fmtMs(completed)}` : ''}
            {run.actualCostUsd != null && run.actualCostUsd > 0
              ? ` · ${fmtCost(run.actualCostUsd)}`
              : ''}
            {run.aiProvider ? ` · ${run.aiProvider.toLowerCase()}` : ''}
          </>
        }
      />

      <div style={{marginBottom: 20}}>
        <MissionCard run={mission} />
      </div>

      {/* Full v3 run detail embedded inside v4 chrome. Same tabs, same
          data, same SSE wiring — RunDetailTabs reads from useRunStore
          which we seeded above. No escape to /runs/:id. */}
      <section
        className='v4-card'
        style={{padding: 0, overflow: 'hidden', marginBottom: 24}}
      >
        <RunDetailTabs />
      </section>
    </>
  );
}
