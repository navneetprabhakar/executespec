import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, Rocket, Info } from 'lucide-react';
import { useExecutionStore } from '@/stores/useExecutionStore';
import { useExecutionSse } from '@/hooks/useExecutionSse';
import { getProject, type ProjectDto } from '@/lib/api';
import ExecutionStepper from '@/components/execution/ExecutionStepper';
import ExecutionSetup from '@/components/execution/ExecutionSetup';
import ExecutionAnalysis from '@/components/execution/ExecutionAnalysis';
import ArchReviewGate from '@/components/execution/ArchReviewGate';
import ExecutionPlanReview from '@/components/execution/ExecutionPlanReview';
import ExecutionMonitor from '@/components/execution/ExecutionMonitor';
import CancellationPanel from '@/components/execution/CancellationPanel';
import ExecutionComplete from '@/components/execution/ExecutionComplete';
import ExecutionReportPanel from '@/components/execution/ExecutionReportPanel';
import type { ExecutionRunStatus } from '@/types/execution';

const NEXT_ACTION: Record<ExecutionRunStatus, { label: string; hint: string }> = {
  SETUP:        { label: 'You',      hint: 'Fill the form and click Launch. Analysis kicks off automatically.' },
  ANALYSING:    { label: 'Automatic', hint: 'Codemaps + unified architecture doc are being generated. No action needed.' },
  ARCH_REVIEW:  { label: 'You',      hint: 'Review the architecture doc and click Approve to let the planner decompose work.' },
  PLANNING:     { label: 'Automatic', hint: 'The planner is decomposing your requirement into solutions. Will pause on the plan for your approval.' },
  EXECUTING:    { label: 'Automatic', hint: 'Solutions execute in dependency layers via V2 pipeline runs. Pauses on any failure for your decision.' },
  PAUSED:       { label: 'You',      hint: 'A solution needs a decision. Open its card below to choose SKIP / RETRY / FIX / ATTEMPT_ANYWAY.' },
  COMPLETE:     { label: 'Done',     hint: 'Launch complete. Review PRs and the execution report below.' },
  ABORTED:      { label: 'Done',     hint: 'Launch aborted. Start a new Launch to try again.' },
};

export default function ExecutionViewPage() {
  const { projectId, executionId } = useParams<{ projectId: string; executionId: string }>();
  const navigate = useNavigate();
  const { executionRun, fetchExecution, reset, triggerAnalysis } = useExecutionStore();
  const [project, setProject] = useState<ProjectDto | null>(null);

  useExecutionSse(executionId ?? null);

  useEffect(() => {
    if (projectId) getProject(projectId).then(setProject).catch(() => setProject(null));
  }, [projectId]);

  useEffect(() => {
    if (executionId) fetchExecution(executionId);
    return () => reset();
  }, [executionId, fetchExecution, reset]);

  if (!projectId) return <div className="mx-auto max-w-5xl p-4 text-[12px] text-accent-red">Project required.</div>;

  const inSetup = !executionId || !executionRun;
  const status: ExecutionRunStatus = executionRun?.status ?? 'SETUP';
  const next = NEXT_ACTION[status];

  return (
    <div className="h-full overflow-y-auto">
    <div className="mx-auto max-w-5xl p-4 space-y-3">
      {/* Back row */}
      <Link
        to={`/projects/${projectId}/dashboard`}
        className="inline-flex items-center gap-1 text-[11px] text-muted hover:text-accent-blue transition-colors"
      >
        <ArrowLeft className="h-3 w-3" />
        {project?.name ?? 'Project'}
      </Link>

      {/* Header */}
      <header className="flex items-center justify-between gap-3">
        <div className="flex items-center gap-2 min-w-0">
          <Rocket className="h-4 w-4 text-accent-blue flex-shrink-0" />
          <h1 className="text-[14px] font-medium text-primary truncate">
            {inSetup ? 'New Launch' : 'Launch'}
          </h1>
          {executionRun && (
            <span className="text-[10px] font-mono text-muted">
              {executionRun.id.substring(0, 8)}
            </span>
          )}
        </div>
        <div className="flex items-center gap-3">
          <ExecutionStepper status={status} />
          {executionId && !['COMPLETE', 'ABORTED', 'SETUP'].includes(status) && (
            <CancelLaunchButton executionId={executionId} projectId={projectId} />
          )}
        </div>
      </header>

      {/* Next-step hint */}
      <div className="flex items-start gap-2 rounded-sm border border-accent-blue/30 bg-accent-blue/5 px-2.5 py-1.5">
        <Info className="h-3 w-3 text-accent-blue mt-0.5 flex-shrink-0" />
        <div className="text-[11px]">
          <span className="text-accent-blue font-medium">{next.label}</span>
          <span className="text-muted"> · </span>
          <span className="text-secondary">{next.hint}</span>
        </div>
      </div>

      {/* Body */}
      <div className="space-y-3">
        {inSetup ? (
          <ExecutionSetup
            projectId={projectId}
            onSubmitted={async (id) => {
              navigate(`/projects/${projectId}/executions/${id}`);
              await fetchExecution(id);
              await triggerAnalysis(id);
            }}
          />
        ) : (
          <>
            {status === 'SETUP' && (
              <ExecutionSetup projectId={projectId} onSubmitted={() => {}} />
            )}
            {status === 'ANALYSING' && <ExecutionAnalysis />}
            {status === 'ARCH_REVIEW' && executionId && <ArchReviewGate executionId={executionId} />}
            {status === 'PLANNING' && executionId && <ExecutionPlanReview executionId={executionId} />}
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
          </>
        )}
      </div>
    </div>
    </div>
  );
}

function CancelLaunchButton({ executionId, projectId }: { executionId: string; projectId?: string }) {
  const { abort } = useExecutionStore();
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
        } finally { setBusy(false); }
      }}
      className="rounded-sm border border-[var(--border-default)] text-[11px] text-muted hover:text-accent-red hover:border-accent-red/50 px-2 py-1 disabled:opacity-50"
      title="Abort this Launch"
    >
      {busy ? 'Cancelling…' : 'Cancel'}
    </button>
  );
}
