import { useNavigate } from 'react-router-dom';
import { useViewMode } from '@/hooks/useViewMode';
import { cn } from '@/lib/utils';

export default function ViewModeToggle() {
  const { viewMode, setViewMode, canUseProjectMode } = useViewMode();
  const navigate = useNavigate();

  const base =
      'px-3 py-1 text-xs font-mono font-medium uppercase tracking-wider transition-colors';
  const activeCls = 'bg-[var(--gray-300)] text-primary';
  const idleCls = 'bg-transparent text-muted hover:text-secondary';

  return (
    <div className="flex rounded-md border border-[var(--border-default)] overflow-hidden">
      <button
        onClick={() => setViewMode('vibe')}
        className={cn(base, viewMode === 'vibe' ? activeCls : idleCls)}
      >
        Vibe
      </button>
      <button
        onClick={() => setViewMode('pro')}
        className={cn(base, viewMode === 'pro' ? activeCls : idleCls)}
      >
        Pro
      </button>
      <button
        onClick={() => {
          if (!canUseProjectMode) return;
          setViewMode('project');
          navigate('/projects');
        }}
        disabled={!canUseProjectMode}
        title={canUseProjectMode
            ? 'Multi-repo projects workspace'
            : 'Project mode requires ENTERPRISE or MAX tier'}
        className={cn(
            base,
            viewMode === 'project' ? activeCls : idleCls,
            !canUseProjectMode && 'opacity-40 cursor-not-allowed',
        )}
      >
        Project
      </button>
    </div>
  );
}
