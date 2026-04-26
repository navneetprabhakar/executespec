import { useLocation, useNavigate } from 'react-router-dom';
import { useViewMode } from '@/hooks/useViewMode';
import { cn } from '@/lib/utils';

// v2.5.7 UX-fix: route targets per mode. When the user clicks a mode while
// already inside a deep child route (e.g. viewing a run detail), we always
// navigate to the mode's home so the toggle is never a silent no-op.
const HOME_BY_MODE: Record<'vibe' | 'pro' | 'project', string> = {
  vibe: '/',
  pro: '/',
  project: '/org/projects',
};

export default function ViewModeToggle() {
  const { viewMode, setViewMode, canUseProjectMode } = useViewMode();
  const navigate = useNavigate();
  const location = useLocation();

  // v2.5.13 — mobile-compact base. Was `px-3` everywhere which made the 3-up
  // pill overflow a 375px header. `px-2 sm:px-3` keeps the tap target large
  // enough (≥32px wide at mobile) without eating header budget.
  const base =
      'px-2 sm:px-3 py-1 text-[11px] sm:text-xs font-mono font-medium uppercase tracking-wider transition-colors';
  const activeCls = 'bg-[var(--gray-300)] text-primary';
  const idleCls = 'bg-transparent text-muted hover:text-secondary';

  const handle = (mode: 'vibe' | 'pro' | 'project') => {
    if (mode === 'project' && !canUseProjectMode) return;
    setViewMode(mode);
    const target = HOME_BY_MODE[mode];
    // Always navigate — even when already in the same mode, the user might be
    // on a deep child route (e.g. /runs/:id) and expects the toggle to take
    // them back to the mode's home.
    if (location.pathname !== target) {
      navigate(target);
    }
  };

  return (
    <div className="flex rounded-md border border-[var(--border-default)] overflow-hidden flex-shrink-0">
      <button
        onClick={() => handle('vibe')}
        title="Vibe mode"
        className={cn(base, viewMode === 'vibe' ? activeCls : idleCls)}
      >
        Vibe
      </button>
      <button
        onClick={() => handle('pro')}
        title="Pro mode"
        className={cn(base, viewMode === 'pro' ? activeCls : idleCls)}
      >
        Pro
      </button>
      <button
        onClick={() => handle('project')}
        disabled={!canUseProjectMode}
        title={canUseProjectMode
            ? 'Multi-repo projects workspace'
            : 'Project mode requires ENTERPRISE or TEAM tier'}
        className={cn(
            base,
            viewMode === 'project' ? activeCls : idleCls,
            !canUseProjectMode && 'opacity-40 cursor-not-allowed',
        )}
      >
        {/* Shorter label at mobile so the 3-up pill fits alongside avatar + bell */}
        <span className="sm:hidden">Proj</span>
        <span className="hidden sm:inline">Project</span>
      </button>
    </div>
  );
}
