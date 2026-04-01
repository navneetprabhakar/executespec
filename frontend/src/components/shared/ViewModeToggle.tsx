import { useViewMode } from '@/hooks/useViewMode';
import { cn } from '@/lib/utils';

export default function ViewModeToggle() {
  const { viewMode, setViewMode } = useViewMode();

  return (
    <div className="flex rounded-md border border-[var(--border-default)] overflow-hidden">
      <button
        onClick={() => setViewMode('vibe')}
        className={cn(
          'px-3 py-1 text-xs font-mono font-medium uppercase tracking-wider transition-colors',
          viewMode === 'vibe'
            ? 'bg-[var(--gray-300)] text-primary'
            : 'bg-transparent text-muted hover:text-secondary'
        )}
      >
        Vibe
      </button>
      <button
        onClick={() => setViewMode('pro')}
        className={cn(
          'px-3 py-1 text-xs font-mono font-medium uppercase tracking-wider transition-colors',
          viewMode === 'pro'
            ? 'bg-[var(--gray-300)] text-primary'
            : 'bg-transparent text-muted hover:text-secondary'
        )}
      >
        Pro
      </button>
    </div>
  );
}
