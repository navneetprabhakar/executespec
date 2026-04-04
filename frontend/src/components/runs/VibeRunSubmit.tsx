import { useState } from 'react';
import { Sparkles, X } from 'lucide-react';
import { useRunStore } from '@/stores/useRunStore';
import { useUiStore } from '@/stores/useUiStore';
import ProviderModelSelector, { getPersistedSelection } from '@/components/runs/ProviderModelSelector';

interface Props {
  onClose: () => void;
}

export default function VibeRunSubmit({ onClose }: Props) {
  const createRun = useRunStore((s) => s.createRun);
  const setActiveRunId = useRunStore((s) => s.setActiveRunId);
  const projectFilter = useRunStore((s) => s.projectFilter);
  const pendingConfig = useUiStore((s) => s.pendingNewRunConfig);
  const setPendingConfig = useUiStore((s) => s.setPendingNewRunConfig);

  const [goal, setGoal] = useState(pendingConfig?.goal ?? '');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const persisted = getPersistedSelection();
  const [provider, setProvider] = useState(persisted.provider);
  const [model, setModel] = useState(persisted.model);

  const handleSubmit = async () => {
    const trimmed = goal.trim();
    if (!trimmed || submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      const runId = await createRun({
        userRequest: trimmed,
        executionMode: 'PRODUCTION',
        skipApproval: true,
        autoMerge: true,
        ...(provider ? { aiProvider: provider } : {}),
        ...(model ? { modelName: model } : {}),
        ...(projectFilter ? { projectId: projectFilter } : {}),
      });
      setPendingConfig(null);
      setActiveRunId(runId);
      onClose();
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Something went wrong');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-[var(--gray-200)]">
      <div className="w-full max-w-xl mx-4 rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] p-6">
        <div className="flex items-center justify-between mb-6">
          <h2 className="text-lg font-medium text-[var(--text-primary)]">What do you want to build?</h2>
          <button onClick={onClose} className="text-muted hover:text-secondary transition-colors">
            <X className="h-5 w-5" />
          </button>
        </div>

        <textarea
          value={goal}
          onChange={(e) => setGoal(e.target.value)}
          placeholder="Describe what you want to build..."
          className="w-full h-36 rounded-sm border border-[var(--border-default)] bg-[var(--geist-bg)] text-[var(--text-primary)] placeholder:text-[var(--text-muted)] p-4 text-sm resize-none focus:outline-none focus:border-accent-blue"
          autoFocus
          onKeyDown={(e) => {
            if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) handleSubmit();
          }}
        />

        <ProviderModelSelector
          provider={provider}
          model={model}
          onProviderChange={setProvider}
          onModelChange={setModel}
        />

        {error && (
          <p className="mt-2 text-sm text-[var(--mc-status-error)]">{error}</p>
        )}

        <div className="flex items-center justify-between mt-4">
          <span className="hidden lg:inline text-xs text-[var(--text-muted)]">
            Cmd+Enter to submit
          </span>
          <button
            onClick={handleSubmit}
            disabled={!goal.trim() || submitting}
            className="flex items-center gap-2 px-5 py-2.5 rounded-sm bg-accent-blue text-primary text-sm font-medium hover:bg-accent-blue/80 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
          >
            <Sparkles className="h-4 w-4" />
            {submitting ? 'Starting...' : 'Build It'}
          </button>
        </div>
      </div>
    </div>
  );
}
