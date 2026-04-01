import { Download, Eye, RotateCcw } from 'lucide-react';
import type { RunDetail } from '@/lib/api';
import { downloadWorkspace } from '@/lib/api';
import { useRunStore } from '@/stores/useRunStore';
import { useUiStore } from '@/stores/useUiStore';

interface Props {
  run: RunDetail;
}

export default function VibeRunSummary({ run }: Props) {
  const retryRun = useRunStore((s) => s.retryRun);
  const setActiveRunId = useRunStore((s) => s.setActiveRunId);
  const setActiveTab = useUiStore((s) => s.setActiveTab);
  const addToast = useUiStore((s) => s.addToast);

  const handleViewPreview = () => {
    setActiveTab('preview');
  };

  const handleDownload = async () => {
    try {
      await downloadWorkspace(run.runId);
    } catch {
      addToast({ type: 'error', message: 'Download failed' });
    }
  };

  const handleRunAgain = async () => {
    try {
      const newRunId = await retryRun(run.runId);
      setActiveRunId(newRunId);
    } catch {
      // toast handled by store
    }
  };

  return (
    <div className="mt-4 space-y-4">
      {/* Title + summary */}
      <div>
        {run.runTitle && (
          <h3 className="text-sm font-mono font-medium text-[var(--text-primary)] mb-1">{run.runTitle}</h3>
        )}
        {run.runSummary && (
          <p className="text-xs font-mono text-[var(--text-secondary)]">{run.runSummary}</p>
        )}
      </div>

      {/* Action buttons */}
      <div className="flex flex-wrap gap-2">
        <button
          onClick={handleDownload}
          className="flex items-center gap-2 px-4 py-2 rounded-sm border border-[var(--border-default)] bg-[var(--gray-200)] text-sm text-[var(--text-primary)] hover:bg-[var(--gray-300)] transition-colors"
        >
          <Download className="h-4 w-4" />
          Download Code
        </button>
        <button
          onClick={handleViewPreview}
          className="flex items-center gap-2 px-4 py-2 rounded-sm bg-accent-blue text-primary text-sm font-medium hover:bg-accent-blue/80 transition-colors"
        >
          <Eye className="h-4 w-4" />
          View Preview
        </button>
        <button
          onClick={handleRunAgain}
          className="flex items-center gap-2 px-4 py-2 rounded-sm border border-[var(--border-default)] bg-[var(--gray-200)] text-sm text-[var(--text-primary)] hover:bg-[var(--gray-300)] transition-colors"
        >
          <RotateCcw className="h-4 w-4" />
          Run Again
        </button>
      </div>
    </div>
  );
}
