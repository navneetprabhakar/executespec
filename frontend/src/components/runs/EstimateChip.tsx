import { useEffect, useState } from 'react';
import { estimateRun, type LaunchEstimate } from '@/lib/api';

interface Props {
  /** When null/empty, the chip is hidden. Estimation is project-scoped. */
  projectId: string | null | undefined;
  /** Current requirement text. Hidden while below minLength. */
  requirement: string;
  /** Characters minimum before hitting the estimate endpoint. Default 12. */
  minLength?: number;
  /** Debounce ms. Default 1500 — matches ExecutionSetup. */
  debounceMs?: number;
  className?: string;
}

/**
 * Debounced launch-cost estimate chip. Shared between `ExecutionSetup`
 * (project Launch) and `NewRunModal` (global New Run) so both surfaces
 * call the same heuristic endpoint and render the same tasks/cost shape.
 */
export default function EstimateChip({
  projectId,
  requirement,
  minLength = 12,
  debounceMs = 1500,
  className,
}: Props) {
  const [estimate, setEstimate] = useState<LaunchEstimate | null>(null);
  const [estimating, setEstimating] = useState(false);

  useEffect(() => {
    setEstimate(null);
    if (!projectId || !requirement || requirement.trim().length < minLength) {
      setEstimating(false);
      return;
    }
    setEstimating(true);
    const handle = setTimeout(() => {
      estimateRun(projectId, requirement.trim())
        .then((r) => { setEstimate(r); setEstimating(false); })
        .catch(() => { setEstimate(null); setEstimating(false); });
    }, debounceMs);
    return () => clearTimeout(handle);
  }, [projectId, requirement, minLength, debounceMs]);

  if (!projectId) return null;

  if (estimating && !estimate) {
    return (
      <div className={`inline-flex items-center gap-2 text-[11px] text-muted bg-[var(--gray-100)] border border-[var(--border-default)] rounded-sm px-2 py-0.5 ${className ?? ''}`}>
        <span className="inline-block w-1.5 h-1.5 rounded-full bg-muted animate-pulse" />
        <span className="font-mono">estimating…</span>
      </div>
    );
  }

  if (estimate && !estimating) {
    return (
      <div className={`inline-flex items-center gap-2 text-[11px] text-muted bg-[var(--gray-100)] border border-[var(--border-default)] rounded-sm px-2 py-0.5 ${className ?? ''}`}>
        <span>~{estimate.estimatedTasksLow}–{estimate.estimatedTasksHigh} tasks</span>
        <span>·</span>
        <span>~${estimate.estimatedCostLow.toFixed(2)}–${estimate.estimatedCostHigh.toFixed(2)}</span>
        <span
          className="text-[9px]"
          title="Heuristic from historical run data. Actual cost depends on plan complexity."
        >
          ({estimate.basis})
        </span>
      </div>
    );
  }

  return null;
}
