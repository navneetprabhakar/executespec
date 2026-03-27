import RunList from '@/components/runs/RunList';
import RunDetail from '@/components/runs/RunDetail';
import { useRunStore } from '@/stores/useRunStore';
import { useSse } from '@/hooks/useSse';
import { useUiStore } from '@/stores/useUiStore';
import { useIsMobile } from '@/hooks/useIsMobile';
import { PanelLeftClose, PanelLeftOpen } from 'lucide-react';
import { cn } from '@/lib/utils';

export default function RunsPage() {
  const activeRunId = useRunStore((s) => s.activeRunId);
  const activeRun = useRunStore((s) => s.activeRun);
  useSse(activeRunId);
  const isMobile = useIsMobile();

  // Reset tab when run changes
  const setActiveTab = useUiStore((s) => s.setActiveTab);
  const runListCollapsed = useUiStore((s) => s.runListCollapsed);
  const setRunListCollapsed = useUiStore((s) => s.setRunListCollapsed);

  // Mobile: show either the list or the detail, not both
  const mobileShowDetail = isMobile && !!activeRunId;

  return (
    <div className="flex h-full overflow-hidden">
      {/* Left panel — run list (collapsible) */}
      {(!isMobile || !mobileShowDetail) && (
        <div
          className={cn(
            'relative flex-shrink-0 border-r border-white/[0.06] overflow-hidden transition-all duration-300',
            runListCollapsed ? 'w-0 border-r-0' : 'w-full md:w-[55%] lg:w-[45%]'
          )}
        >
          <RunList />
        </div>
      )}

      {/* Right panel — run detail */}
      {(!isMobile || mobileShowDetail) && (
        <div className="flex-1 overflow-hidden relative">
          {/* Collapse/expand toggle */}
          {!isMobile && (
            <button
              onClick={() => setRunListCollapsed(!runListCollapsed)}
              className={cn(
                'absolute top-3 left-3 z-10 p-1.5 rounded-lg transition-all',
                'bg-white/[0.04] border border-white/[0.08] text-zinc-500',
                'hover:bg-white/[0.08] hover:text-zinc-300 hover:border-white/[0.12]'
              )}
              title={runListCollapsed ? 'Show run list' : 'Hide run list'}
            >
              {runListCollapsed
                ? <PanelLeftOpen className="h-4 w-4" />
                : <PanelLeftClose className="h-4 w-4" />
              }
            </button>
          )}

          {activeRun ? (
            <RunDetail />
          ) : (
            <div className="h-full flex items-center justify-center">
              <p className="text-sm text-zinc-600">Select a run to view details</p>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
