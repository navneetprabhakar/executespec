import { Search, Zap } from 'lucide-react';
import { useUiStore } from '@/stores/useUiStore';
import { useRunStore } from '@/stores/useRunStore';
import { useSseStore } from '@/stores/useSseStore';
import { ACTIVE_STATUSES } from '@/lib/constants';
import { cn } from '@/lib/utils';
import UserMenu from '@/components/auth/UserMenu';

export default function Header() {
  const { setCommandPaletteOpen } = useUiStore();
  const runs = useRunStore((s) => s.runs);
  const activeRunId = useRunStore((s) => s.activeRunId);
  const sseStatus = useSseStore((s) => s.status);
  const activeCount = runs.filter((r) => ACTIVE_STATUSES.has(r.status)).length;
  // SSE connects per-run; show "Standby" when no run is selected rather than "Offline"
  const displayStatus = sseStatus === 'DISCONNECTED' && !activeRunId ? 'STANDBY' : sseStatus;

  return (
    <header className="flex items-center h-12 px-4 gap-3 bg-surface-1 border-b border-white/[0.06] flex-shrink-0 z-10">
      {/* SSE status */}
      <div className="flex items-center gap-1.5 text-xs">
        <span className={cn(
          'relative flex h-1.5 w-1.5',
          displayStatus === 'CONNECTED' ? '' : 'opacity-60'
        )}>
          {displayStatus === 'CONNECTED' && (
            <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-accent-emerald opacity-75" />
          )}
          <span className={cn(
            'relative inline-flex rounded-full h-1.5 w-1.5',
            displayStatus === 'CONNECTED' ? 'bg-accent-emerald' :
            displayStatus === 'RECONNECTING' ? 'bg-accent-amber' : 'bg-zinc-600'
          )} />
        </span>
        <span className={cn(
          'text-xs hidden sm:block',
          displayStatus === 'CONNECTED' ? 'text-accent-emerald' :
          displayStatus === 'RECONNECTING' ? 'text-accent-amber' : 'text-zinc-600'
        )}>
          {displayStatus === 'CONNECTED' ? 'Live' :
           displayStatus === 'RECONNECTING' ? 'Reconnecting' :
           displayStatus === 'STANDBY' ? 'Standby' : 'Offline'}
        </span>
      </div>

      {/* Spacer */}
      <div className="flex-1" />

      {/* Command palette trigger */}
      <button
        onClick={() => setCommandPaletteOpen(true)}
        className="flex items-center gap-2 px-3 py-1.5 rounded-lg bg-white/[0.04] border border-white/[0.06] text-sm text-zinc-500 hover:text-zinc-300 hover:bg-white/[0.06] transition-colors"
      >
        <Search className="h-3.5 w-3.5" />
        <span className="hidden md:block">Search...</span>
        <kbd className="hidden md:block text-xs bg-white/[0.06] px-1.5 py-0.5 rounded border border-white/[0.08] font-mono">⌘K</kbd>
      </button>

      {/* Active runs badge */}
      {activeCount > 0 && (
        <div className="flex items-center gap-1.5 px-2.5 py-1 rounded-full bg-accent-violet/15 border border-accent-violet/20">
          <Zap className="h-3 w-3 text-accent-violet" />
          <span className="text-xs font-medium text-accent-violet">{activeCount} active</span>
        </div>
      )}

      {/* User menu */}
      <UserMenu />
    </header>
  );
}
