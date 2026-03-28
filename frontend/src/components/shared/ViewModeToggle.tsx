import { useViewMode } from '@/hooks/useViewMode';
import { cn } from '@/lib/utils';

export default function ViewModeToggle() {
  const { viewMode, setViewMode } = useViewMode();

  return (
    <div className="flex rounded-sm border border-[var(--mc-border)] overflow-hidden">
      <button
        onClick={() => setViewMode('vibe')}
        className={cn(
          'px-3 py-1 text-xs font-mono font-medium uppercase tracking-wider transition-colors',
          viewMode === 'vibe'
            ? 'bg-accent-violet text-white'
            : 'bg-transparent text-zinc-500 hover:text-zinc-300'
        )}
      >
        Vibe
      </button>
      <button
        onClick={() => setViewMode('pro')}
        className={cn(
          'px-3 py-1 text-xs font-mono font-medium uppercase tracking-wider transition-colors',
          viewMode === 'pro'
            ? 'bg-accent-violet text-white'
            : 'bg-transparent text-zinc-500 hover:text-zinc-300'
        )}
      >
        Pro
      </button>
    </div>
  );
}
