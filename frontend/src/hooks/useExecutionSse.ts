import {useEffect, useRef} from 'react';
import {executionApi} from '@/lib/execution-api';
import {useExecutionStore} from '@/stores/useExecutionStore';

/**
 * SSE hook for the Execution View. Connects to /api/executions/{id}/events with
 * a short-lived scoped token and routes EXEC_* events into useExecutionStore.
 * Mirrors the pattern used by useSse for per-run events.
 */
export function useExecutionSse(executionId: string | null) {
  const esRef = useRef<EventSource | null>(null);
  const {
    fetchExecution,
    updateStatus,
    updateSolutionStatus,
    updateAnalysisProgress,
    appendSolutionLog,
    applyCompensationUpdate,
    setCompensationDone,
  } = useExecutionStore();

  useEffect(() => {
    if (!executionId) return;
    let cancelled = false;

    async function connect() {
      try {
        const {token} = await executionApi.getSseToken(executionId!);
        if (cancelled) return;
        const es = new EventSource(
          `/api/executions/${executionId}/events?token=${encodeURIComponent(token)}`,
        );
        esRef.current = es;
        es.onmessage = (e) => {
          try {
            handleEvent(JSON.parse(e.data));
          } catch {
            /* ignore */
          }
        };
        es.onerror = () => {
          es.close();
          if (!cancelled) setTimeout(connect, 3000);
        };
      } catch {
        if (!cancelled) setTimeout(connect, 3000);
      }
    }

    function handleEvent(evt: {type: string; taskId?: string | null; payload?: string | null}) {
      const solutionId = evt.taskId ?? undefined;
      const repoId = evt.payload ?? undefined;
      switch (evt.type) {
        case 'EXEC_REPO_CODEMAP_STARTED':
          if (repoId) updateAnalysisProgress(repoId, 'codemapStatus', 'RUNNING');
          break;
        case 'EXEC_REPO_CODEMAP_DONE':
          if (repoId) updateAnalysisProgress(repoId, 'codemapStatus', 'DONE');
          break;
        case 'EXEC_REPO_ARCH_STARTED':
          if (repoId) updateAnalysisProgress(repoId, 'archDocStatus', 'RUNNING');
          break;
        case 'EXEC_REPO_ARCH_DONE':
          if (repoId) updateAnalysisProgress(repoId, 'archDocStatus', 'DONE');
          break;
        case 'EXEC_ANALYSIS_COMPLETE':
          updateStatus('ARCH_REVIEW');
          fetchExecution(executionId!);
          break;
        case 'EXEC_PLANNING_STARTED':
          updateStatus('PLANNING');
          break;
        case 'EXEC_PLAN_READY':
          fetchExecution(executionId!);
          break;
        case 'EXEC_STARTED':
          updateStatus('EXECUTING');
          break;
        case 'EXEC_SOLUTION_RUNNING':
          if (solutionId) updateSolutionStatus(solutionId, 'RUNNING');
          break;
        case 'EXEC_SOLUTION_VALIDATING':
          if (solutionId) updateSolutionStatus(solutionId, 'VALIDATING');
          break;
        case 'EXEC_SOLUTION_PR_RAISED':
          if (solutionId) updateSolutionStatus(solutionId, 'PR_RAISED');
          fetchExecution(executionId!);
          break;
        case 'EXEC_SOLUTION_DONE':
          if (solutionId) updateSolutionStatus(solutionId, 'DONE');
          break;
        case 'EXEC_SOLUTION_FAILED':
          if (solutionId) updateSolutionStatus(solutionId, 'FAILED');
          updateStatus('PAUSED');
          fetchExecution(executionId!);
          break;
        case 'EXEC_SOLUTION_SKIPPED':
          if (solutionId) updateSolutionStatus(solutionId, 'SKIPPED');
          break;
        case 'EXEC_SOLUTION_LOG':
          if (solutionId && evt.payload) appendSolutionLog(solutionId, evt.payload);
          break;
        case 'EXEC_FINAL_PR_CREATED':
          fetchExecution(executionId!);
          break;
        case 'EXEC_COMPLETE':
          updateStatus('COMPLETE');
          fetchExecution(executionId!);
          break;
        case 'EXEC_ABORTED':
          updateStatus('ABORTED');
          fetchExecution(executionId!);
          break;
        case 'EXEC_RESUMED':
          updateStatus('EXECUTING');
          fetchExecution(executionId!);
          break;
        case 'EXEC_COMPENSATION_UPDATE':
          if (evt.payload) {
            try {
              applyCompensationUpdate(JSON.parse(evt.payload));
            } catch {
              /* ignore */
            }
          }
          break;
        case 'EXEC_COMPENSATION_DONE':
          if (evt.payload) {
            try {
              setCompensationDone(JSON.parse(evt.payload));
            } catch {
              /* ignore */
            }
          }
          break;
      }
    }

    connect();
    return () => {
      cancelled = true;
      esRef.current?.close();
    };
  }, [
    executionId,
    fetchExecution,
    updateStatus,
    updateSolutionStatus,
    updateAnalysisProgress,
    appendSolutionLog,
    applyCompensationUpdate,
    setCompensationDone,
  ]);
}
