import {useEffect, useState} from 'react';
import {Link, useLocation, useNavigate, useParams} from 'react-router-dom';
import {ArrowLeft, Rocket, Info, ExternalLink} from 'lucide-react';
import {useExecutionStore} from '@/stores/useExecutionStore';
import {useExecutionSse} from '@/hooks/useExecutionSse';
import {getProject, type ProjectDto} from '@/lib/api';
import ExecutionStepper from '@/components/execution/ExecutionStepper';
import ExecutionSetup from '@/components/execution/ExecutionSetup';
import ExecutionAnalysis from '@/components/execution/ExecutionAnalysis';
import ArchReviewGate from '@/components/execution/ArchReviewGate';
import ExecutionPlanReview from '@/components/execution/ExecutionPlanReview';
import ExecutionMonitor from '@/components/execution/ExecutionMonitor';
import CancellationPanel from '@/components/execution/CancellationPanel';
import ExecutionComplete from '@/components/execution/ExecutionComplete';
import ExecutionReportPanel from '@/components/execution/ExecutionReportPanel';
import type {ExecutionRunStatus} from '@/types/execution';

const NEXT_ACTION: Record<ExecutionRunStatus, {label: string; hint: string}> = {
  SETUP: {label: 'You', hint: 'Fill the form and click Launch. Analysis kicks off automatically.'},
  ANALYSING: {
    label: 'Automatic',
    hint: 'Codemaps + unified architecture doc are being generated. No action needed.',
  },
  ARCH_REVIEW: {
    label: 'You',
    hint: 'Review the architecture doc and click Approve to let the planner decompose work.',
  },
  PLANNING: {
    label: 'Automatic',
    hint: 'The planner is decomposing your requirement into solutions. Will pause on the plan for your approval.',
  },
  EXECUTING: {
    label: 'Automatic',
    hint: 'Solutions execute in dependency layers via V2 pipeline runs. Pauses on any failure for your decision.',
  },
  PAUSED: {
    label: 'You',
    hint: 'A solution needs a decision. Open its card below to choose SKIP / RETRY / FIX / ATTEMPT_ANYWAY.',
  },
  COMPLETE: {label: 'Done', hint: 'Launch complete. Review PRs and the execution report below.'},
  ABORTED: {label: 'Done', hint: 'Launch aborted. Start a new Launch to try again.'},
  FAILED: {
    label: 'Done',
    hint: 'Launch failed before any solution started. Start a new Launch to try again.',
  },
};

// 2026-05-13 — fallback used when the backend emits a status the frontend
// type doesn't yet know about. Keeps the page rendering instead of crashing
// the ErrorBoundary on every status drift.
const NEXT_ACTION_FALLBACK = {
  label: 'Unknown',
  hint: 'Status not recognised by this client build — refresh, or check the launch from the dashboard.',
};

export default function ExecutionViewPage() {
  const {projectId, executionId} = useParams<{projectId: string; executionId: string}>();
  const navigate = useNavigate();
  const location = useLocation();
  const {executionRun, fetchExecution, reset, triggerAnalysis} = useExecutionStore();
  const [project, setProject] = useState<ProjectDto | null>(null);
  // 2026-05-04 — track the in-flight fetch + last error explicitly so we can render a loading
  // state when executionId is in the URL but the data is still on the wire. Previously the page
  // fell through to ExecutionSetup (the new-launch form) whenever executionRun was null, which
  // meant a user clicking an existing PIPELINE_RUN entry in the launches list (whose id doesn't
  // resolve via /api/projects/{id}/executions/{id}) saw the form and any submit created an
  // ENTIRELY NEW launch. Defense-in-depth pair to the kind-discriminator routing fix in
  // ProjectLaunchesList.
  const [fetching, setFetching] = useState(false);
  const [fetchError, setFetchError] = useState<string | null>(null);
  // v2.5.7 — "Address review" flow passes continueOnPr via navigation state.
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const continueOnPr = (location.state as any)?.continueOnPr ?? undefined;

  useExecutionSse(executionId ?? null);

  useEffect(() => {
    if (projectId)
      getProject(projectId)
        .then(setProject)
        .catch(() => setProject(null));
  }, [projectId]);

  useEffect(() => {
    if (!executionId) return;
    let cancelled = false;
    setFetching(true);
    setFetchError(null);
    Promise.resolve(fetchExecution(executionId))
      .catch((e) => {
        if (cancelled) return;
        const msg = e instanceof Error ? e.message : String(e);
        setFetchError(msg || 'Failed to load launch');
      })
      .finally(() => {
        if (!cancelled) setFetching(false);
      });
    return () => {
      cancelled = true;
      reset();
    };
  }, [executionId, fetchExecution, reset]);

  if (!projectId)
    return (
      <div className='mx-auto max-w-5xl p-4 text-[12px] text-accent-red'>Project required.</div>
    );

  // Only fall into the new-launch flow when there's NO executionId in the URL. If executionId is
  // present, render either a loading shim or an error block — never the setup form. This is the
  // defensive layer; the launches-list routing fix in ProjectLaunchesList prevents PIPELINE_RUN
  // ids from reaching this page in the first place.
  const inSetup = !executionId;
  const status: ExecutionRunStatus = executionRun?.status ?? 'SETUP';
  const next = NEXT_ACTION[status] ?? NEXT_ACTION_FALLBACK;

  return (
    <div className='h-full overflow-y-auto'>
      <div className='mx-auto max-w-5xl p-4 space-y-3'>
        {/* Back row */}
        <Link
          to={`/projects/${projectId}/dashboard`}
          className='inline-flex items-center gap-1 text-[11px] text-muted hover:text-accent-blue transition-colors'
        >
          <ArrowLeft className='h-3 w-3' />
          {project?.name ?? 'Project'}
        </Link>

        {/* Header */}
        <header className='flex items-center justify-between gap-3'>
          <div className='flex items-center gap-2 min-w-0'>
            <Rocket className='h-4 w-4 text-accent-blue flex-shrink-0' />
            <h1 className='text-[14px] font-medium text-primary truncate'>
              {inSetup ? 'New Launch' : 'Launch'}
            </h1>
            {executionRun && (
              <span className='text-[10px] font-mono text-muted'>
                {executionRun.id.substring(0, 8)}
              </span>
            )}
            {executionRun?.pipelineRunId && (
              <Link
                to={`/runs/${executionRun.pipelineRunId}`}
                className='inline-flex items-center gap-1 text-[11px] text-accent-blue hover:underline'
                title='Open the underlying V2 run — task progress, worker logs, PRs'
              >
                V2 run <ExternalLink className='h-3 w-3' />
              </Link>
            )}
          </div>
          <div className='flex items-center gap-3'>
            <ExecutionStepper status={status} activeSince={executionRun?.updatedAt} />
            {executionId && !['COMPLETE', 'ABORTED', 'SETUP', 'FAILED'].includes(status) && (
              <CancelLaunchButton executionId={executionId} projectId={projectId} />
            )}
          </div>
        </header>

        {/* Next-step hint */}
        <div className='flex items-start gap-2 rounded-sm border border-accent-blue/30 bg-accent-blue/5 px-2.5 py-1.5'>
          <Info className='h-3 w-3 text-accent-blue mt-0.5 flex-shrink-0' />
          <div className='text-[11px]'>
            <span className='text-accent-blue font-medium'>{next.label}</span>
            <span className='text-muted'> · </span>
            <span className='text-secondary'>{next.hint}</span>
          </div>
        </div>

        {/* Body */}
        <div className='space-y-3'>
          {inSetup ? (
            <ExecutionSetup
              projectId={projectId}
              continueOnPr={continueOnPr}
              onSubmitted={async (id) => {
                // Kick the backend phases off.
                try {
                  await triggerAnalysis(id);
                } catch {
                  /* backend 4xx already surfaced via toast */
                }
                // v2.5.7 UX-fix: redirect to the execution detail page so the
                // user sees the Launch progressing through Analysing / Arch /
                // Planning. Previously we landed on the project dashboard
                // which did not auto-refresh, making the Launch appear lost.
                navigate(`/projects/${projectId}/executions/${id}`);
              }}
            />
          ) : !executionRun && fetching ? (
            // Loading shim while the initial fetch is in flight. Critical: never render
            // ExecutionSetup here — see comment at the inSetup definition above.
            <div
              className='flex items-center gap-2 rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] px-3 py-3 text-[12px] text-muted'
              role='status'
              aria-live='polite'
            >
              <span className='inline-block h-3 w-3 rounded-full border-2 border-accent-blue/40 border-t-accent-blue animate-spin' />
              <span>Loading launch {executionId?.substring(0, 8)}…</span>
            </div>
          ) : !executionRun ? (
            // Fetch settled but no data — most likely the id isn't an ExecutionRun (e.g. it's a
            // V2 PIPELINE_RUN id that landed here by mistake). Help the user recover instead of
            // leaving a blank page or an unrelated form.
            <div className='space-y-2 rounded-sm border border-status-error/30 bg-status-error/5 px-3 py-3 text-[12px]'>
              <div className='text-status-error'>
                {fetchError
                  ? `Couldn't load this launch: ${fetchError}`
                  : "Couldn't load this launch."}
              </div>
              <div className='text-muted'>
                If this is a chat-launched run, it lives at{' '}
                <Link
                  to={`/projects/${projectId}/runs/${executionId ?? ''}`}
                  className='text-accent-blue underline'
                >
                  /runs/{executionId?.substring(0, 8)}
                </Link>{' '}
                instead. Otherwise, return to{' '}
                <Link
                  to={`/projects/${projectId}/dashboard`}
                  className='text-accent-blue underline'
                >
                  the project dashboard
                </Link>
                .
              </div>
            </div>
          ) : (
            <>
              {status === 'SETUP' && (
                <ExecutionSetup projectId={projectId} onSubmitted={() => {}} />
              )}
              {status === 'ANALYSING' && <ExecutionAnalysis />}
              {status === 'ARCH_REVIEW' && executionId && (
                <ArchReviewGate executionId={executionId} />
              )}
              {status === 'PLANNING' && executionId && (
                <ExecutionPlanReview executionId={executionId} />
              )}
              {(status === 'EXECUTING' || status === 'PAUSED') && executionId && (
                <ExecutionMonitor executionId={executionId} />
              )}
              {(status === 'COMPLETE' || status === 'ABORTED') && (
                <>
                  {status === 'ABORTED' && <CancellationPanel />}
                  <ExecutionComplete />
                  {executionId && <ExecutionReportPanel executionId={executionId} />}
                </>
              )}
              {status === 'FAILED' && (
                <div className='space-y-2 rounded-sm border border-status-error/30 bg-status-error/5 px-3 py-3 text-[12px]'>
                  <div className='text-status-error font-medium'>
                    Launch failed before any solution could start.
                  </div>
                  <div className='text-muted'>
                    The planner couldn't produce a valid plan, or a setup-time error aborted the
                    launch. Solutions never executed — no PRs were opened. Start a new Launch to try
                    again.
                  </div>
                  <div>
                    <Link
                      to={`/projects/${projectId}/executions/new`}
                      className='inline-flex items-center gap-1 rounded-sm border border-accent-blue/40 bg-accent-blue/10 text-accent-blue text-[11px] px-2 py-1 hover:bg-accent-blue/20'
                    >
                      Start a new Launch
                    </Link>
                  </div>
                </div>
              )}
            </>
          )}
        </div>
      </div>
    </div>
  );
}

function CancelLaunchButton({executionId, projectId}: {executionId: string; projectId?: string}) {
  const {abort} = useExecutionStore();
  const navigate = useNavigate();
  const [busy, setBusy] = useState(false);
  return (
    <button
      disabled={busy}
      onClick={async () => {
        if (!confirm('Cancel this Launch? Progress will be discarded.')) return;
        setBusy(true);
        try {
          await abort(executionId);
          if (projectId) navigate(`/projects/${projectId}/dashboard`);
        } finally {
          setBusy(false);
        }
      }}
      className='rounded-sm border border-[var(--border-default)] text-[11px] text-muted hover:text-accent-red hover:border-accent-red/50 px-2 py-1 disabled:opacity-50'
      title='Abort this Launch'
    >
      {busy ? 'Cancelling…' : 'Cancel'}
    </button>
  );
}
