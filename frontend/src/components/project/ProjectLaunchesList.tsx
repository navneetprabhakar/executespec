import {errMsg} from '@/lib/utils';
import {useEffect, useState} from 'react';
import {Link} from 'react-router-dom';
import {Rocket} from 'lucide-react';
import {executionApi} from '@/lib/execution-api';
import {getRuns} from '@/lib/api';
import type {ExecutionRunDto} from '@/types/execution';
import StatusBadge from '@/components/shared/StatusBadge';
import EmptyState from '@/components/shared/EmptyState';

export default function ProjectLaunchesList({projectId}: {projectId: string}) {
  const [launches, setLaunches] = useState<ExecutionRunDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    executionApi
      .listProjectLaunches(projectId)
      .then((page) => setLaunches(page.content))
      .catch(async (e: unknown) => {
        // 2026-05-06 — graceful FREE-tier fallback. /api/projects/{id}/launches
        // is gated to the execution_view_enabled tier flag (ENTERPRISE / MAX);
        // every other tier hits 403 "Project Shell requires an eligible
        // tier" and the Runs tab errored out. Fall through to /api/runs
        // (NOT tier-gated, scoped via RunVisibilitySpec) so users still see
        // the per-project pipeline-run history. The cross-repo Execution
        // Run workflow itself stays paid-only — only the read fallback is
        // opened up.
        const status =
          (e as {response?: {status?: number}; status?: number})?.response?.status ??
          (e as {status?: number})?.status;
        if (status !== 403) {
          setError(errMsg(e) ?? 'Failed to load launches');
          return;
        }
        try {
          const res = await getRuns({projectId, scope: 'mine', size: 50});
          const fallback: ExecutionRunDto[] = res.content.map(
            (r) =>
              ({
                id: r.runId,
                kind: 'PIPELINE_RUN',
                status: r.status,
                requirement: r.runTitle ?? r.userRequest ?? '(no spec)',
                createdAt: r.startedAt,
                solutions: [],
                // PR-B (2026-05-13) — bubble PR URL up to the launches row so the listing surfaces
                // a click-through pill without a per-row drill-down. Without this, every chat-
                // launched run showed pr=- in the project page even when a PR had been raised.
                scmPrUrl: r.scmPrUrl,
                githubPrUrl: r.githubPrUrl,
              }) as unknown as ExecutionRunDto,
          );
          setLaunches(fallback);
        } catch (fb) {
          setError(errMsg(fb) ?? 'Failed to load runs');
        }
      });
  }, [projectId]);

  if (error) return <div className='text-xs text-status-error'>{error}</div>;
  if (launches == null) return <div className='text-xs text-muted'>Loading launches…</div>;

  // 2026-05-13 audit — collapse child V2 pipeline_runs under their parent execution.
  // Each ExecutionSolution carries a pipelineRunId; the launches endpoint returns BOTH
  // the parent execution AND every child pipeline_run as separate top-level rows. On a
  // 10-solution execution with retries that meant ~13 list items competing for attention,
  // making the parent execution row hard to spot. Filter out any PIPELINE_RUN whose id
  // appears in some execution's solutions[].pipelineRunId — the user can still reach
  // the child run by drilling into the execution detail page.
  const ownedPipelineRunIds = new Set<string>();
  for (const l of launches) {
    if (l.kind === 'PIPELINE_RUN') continue;
    for (const s of l.solutions ?? []) {
      if (s.pipelineRunId) ownedPipelineRunIds.add(s.pipelineRunId);
    }
  }
  const visibleLaunches = launches.filter(
    (l) => !(l.kind === 'PIPELINE_RUN' && ownedPipelineRunIds.has(l.id)),
  );
  const hiddenChildCount = launches.length - visibleLaunches.length;

  if (visibleLaunches.length === 0) {
    return (
      <EmptyState
        compact
        icon={Rocket}
        title='No launches yet'
        description='Click Launch above to start a cross-repo run.'
      />
    );
  }

  return (
    <div className='space-y-1.5'>
      {hiddenChildCount > 0 && (
        <div
          className='text-[10px] text-muted'
          title={`${hiddenChildCount} child V2 run(s) are nested under their parent execution row(s) below. Open the execution to see them.`}
        >
          {hiddenChildCount} child run{hiddenChildCount > 1 ? 's' : ''} hidden — open the parent
          execution to view
        </div>
      )}
      {visibleLaunches.map((l) => {
        const doneCount = l.solutions.filter((s) => s.status === 'DONE').length;
        // 2026-05-04 — route based on the V169-companion `kind` discriminator. Without it,
        // PIPELINE_RUN entries (V2 runs from the project chat / V3 composer) routed to
        // /executions/{id} which doesn't recognise pipeline_run ids → ExecutionViewPage fell
        // through to the setup form → user clicking an existing run got the new-launch form
        // and any submit there created an ENTIRELY NEW launch. Missing kind is treated as
        // EXECUTION for back-compat with pre-V169 API responses.
        const isPipelineRun = l.kind === 'PIPELINE_RUN';
        const target = isPipelineRun
          ? `/projects/${projectId}/runs/${l.id}`
          : `/projects/${projectId}/executions/${l.id}`;
        // PipelineRun entries don't have solutions (they're a single V2 run, not a multi-repo
        // launch with per-solution rows), so the "repos × N · M/N done" right-rail line is
        // misleading. Show the V2 token count instead.
        // PR-B (2026-05-13) — prefer scm-agnostic PR URL; fall back to githubPrUrl for pre-SCM-
        // split rows. Only PIPELINE_RUN rows surface a single-PR pill in the listing; EXECUTION
        // rows aggregate multiple PRs across solutions and surface those at the detail page.
        const prUrl = isPipelineRun ? (l.scmPrUrl ?? l.githubPrUrl ?? null) : null;
        const prNumber = (() => {
          if (!prUrl) return null;
          const m = prUrl.match(/\/(?:pull|pull-requests)\/(\d+)/);
          return m ? m[1] : null;
        })();
        return (
          <Link
            key={l.id}
            to={target}
            className='flex items-center gap-2 rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] px-3 py-2 text-xs hover:bg-[var(--gray-200)]'
          >
            <StatusBadge status={l.status} className='text-[11px]' />
            <span className='font-mono text-[11px] text-muted'>{l.id.substring(0, 8)}</span>
            <span className='truncate flex-1'>{l.requirement.split('\n')[0]}</span>
            {prUrl && (
              <a
                href={prUrl}
                target='_blank'
                rel='noreferrer'
                onClick={(e) => e.stopPropagation()}
                className='inline-flex items-center gap-1 rounded-sm border border-accent-blue/40 bg-accent-blue/10 text-accent-blue text-[10px] px-1.5 py-0.5 hover:bg-accent-blue/20'
                title='Open the pull request raised by this run'
              >
                PR{prNumber ? ` #${prNumber}` : ''}
              </a>
            )}
            <span className='text-[11px] text-muted'>
              {isPipelineRun
                ? `${l.totalActualTokens ?? 0} tok`
                : `repos × ${l.solutions.length} · ${doneCount}/${l.solutions.length} done · ${l.totalActualTokens ?? 0} tok`}
            </span>
            {l.jiraEnabled && <span className='text-[11px] text-accent-blue'>JIRA</span>}
          </Link>
        );
      })}
    </div>
  );
}
