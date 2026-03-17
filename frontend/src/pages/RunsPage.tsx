import RunList from '@/components/runs/RunList';
import RunDetail from '@/components/runs/RunDetail';
import { useRunStore } from '@/stores/useRunStore';
import { useSse } from '@/hooks/useSse';
import { useUiStore } from '@/stores/useUiStore';
import { useIsMobile } from '@/hooks/useIsMobile';

export default function RunsPage() {
  const activeRunId = useRunStore((s) => s.activeRunId);
  const activeRun = useRunStore((s) => s.activeRun);
  useSse(activeRunId);
  const isMobile = useIsMobile();

  // Reset tab when run changes
  const setActiveTab = useUiStore((s) => s.setActiveTab);

  // Mobile: show either the list or the detail, not both
  const mobileShowDetail = isMobile && !!activeRunId;

  return (
    <div className="flex h-full overflow-hidden">
      {/* Left panel — run list */}
      {(!isMobile || !mobileShowDetail) && (
        <div className="relative w-full md:w-[55%] lg:w-[45%] flex-shrink-0 border-r border-white/[0.06] overflow-hidden">
          <RunList />
        </div>
      )}

      {/* Right panel — run detail */}
      {(!isMobile || mobileShowDetail) && (
        <div className="flex-1 overflow-hidden">
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
