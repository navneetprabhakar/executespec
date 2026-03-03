import { useState } from 'react';
import { ChevronDown, RefreshCw, BookOpen } from 'lucide-react';
import GlowDot from '@/components/shared/GlowDot';
import { fmtTokens, fmtMs, cn } from '@/lib/utils';
import { useRunStore } from '@/stores/useRunStore';
import type { TaskSummary } from '@/lib/api';

interface TaskCardProps {
  task: TaskSummary;
  runId: string;
}

export default function TaskCard({ task, runId }: TaskCardProps) {
  const [expanded, setExpanded] = useState(false);
  const { retryTask } = useRunStore();

  return (
    <div className="rounded-lg border border-white/[0.06] bg-white/[0.02] overflow-hidden">
      <button
        onClick={() => setExpanded(!expanded)}
        className="w-full flex items-center gap-3 px-4 py-3 text-left hover:bg-white/[0.02]"
      >
        <GlowDot status={task.status} size="sm" className="flex-shrink-0" />
        <div className="flex-1 min-w-0">
          <p className="text-sm text-zinc-200 truncate">{task.title}</p>
          <div className="flex items-center gap-2 mt-0.5 flex-wrap">
            <span className="text-xs text-zinc-500 font-mono">{task.taskId.slice(0, 8)}</span>
            {task.branchName && (
              <span className="text-xs text-zinc-600 font-mono">{task.branchName}</span>
            )}
            {task.assignedModel && (
              <span className="text-xs text-zinc-600">{task.assignedModel}</span>
            )}
          </div>
        </div>
        <div className="flex items-center gap-3 text-right flex-shrink-0">
          {(task.inputTokens + task.outputTokens) > 0 && <span className="text-xs text-zinc-600">{fmtTokens(task.inputTokens + task.outputTokens)}</span>}
          {task.durationMs > 0 && <span className="text-xs text-zinc-600">{fmtMs(task.durationMs)}</span>}
          {task.readmeUpdated && <span title="README updated"><BookOpen className="h-3.5 w-3.5 text-accent-emerald" /></span>}
          <ChevronDown className={cn('h-4 w-4 text-zinc-600 transition-transform', expanded && 'rotate-180')} />
        </div>
      </button>

      {expanded && (
        <div className="px-4 py-3 border-t border-white/[0.06] space-y-2">
          {task.status === 'PARTIAL' && (
            <button
              onClick={() => retryTask(runId, task.taskId)}
              className="flex items-center gap-1.5 text-xs text-accent-amber hover:text-accent-amber/80"
            >
              <RefreshCw className="h-3 w-3" />
              Retry task
            </button>
          )}
        </div>
      )}
    </div>
  );
}
