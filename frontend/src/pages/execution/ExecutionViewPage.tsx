import { useEffect } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useExecutionStore } from '@/stores/useExecutionStore';
import { useExecutionSse } from '@/hooks/useExecutionSse';
import ExecutionStepper from '@/components/execution/ExecutionStepper';
import ExecutionSetup from '@/components/execution/ExecutionSetup';
import ExecutionAnalysis from '@/components/execution/ExecutionAnalysis';
import ArchReviewGate from '@/components/execution/ArchReviewGate';
import ExecutionPlanReview from '@/components/execution/ExecutionPlanReview';
import ExecutionMonitor from '@/components/execution/ExecutionMonitor';
import ExecutionComplete from '@/components/execution/ExecutionComplete';
import ExecutionReportPanel from '@/components/execution/ExecutionReportPanel';

export default function ExecutionViewPage() {
  const { projectId, executionId } = useParams<{ projectId: string; executionId: string }>();
  const navigate = useNavigate();
  const { executionRun, fetchExecution, reset, triggerAnalysis } = useExecutionStore();

  useExecutionSse(executionId ?? null);

  useEffect(() => {
    if (executionId) fetchExecution(executionId);
    return () => reset();
  }, [executionId, fetchExecution, reset]);

  if (!projectId) return <div className="p-4 text-accent-red">Project required.</div>;

  // "New" flow: no executionId yet → show setup. After creation we navigate to
  // the execution's canonical URL.
  const inSetup = !executionId || !executionRun;
  const status = executionRun?.status ?? 'SETUP';

  return (
    <div className="mx-auto max-w-4xl p-4 space-y-4">
      <div className="flex items-center justify-between">
        <h2 className="text-[14px] font-medium text-primary">Execution View</h2>
        <ExecutionStepper status={status} />
      </div>

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
          {(status === 'EXECUTING' || status === 'PAUSED') && executionId && <ExecutionMonitor executionId={executionId} />}
          {(status === 'COMPLETE' || status === 'ABORTED') && (
            <>
              <ExecutionComplete />
              {executionId && <ExecutionReportPanel executionId={executionId} />}
            </>
          )}
        </>
      )}
    </div>
  );
}
