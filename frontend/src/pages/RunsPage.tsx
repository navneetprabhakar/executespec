import { useCallback, useRef } from 'react';
import RunList from '@/components/runs/RunList';
import RunDetail from '@/components/runs/RunDetail';
import { useRunStore } from '@/stores/useRunStore';
import { useSse } from '@/hooks/useSse';
import { useUiStore } from '@/stores/useUiStore';
import { useIsMobile } from '@/hooks/useIsMobile';
import { Sparkles } from 'lucide-react';
import { useViewMode } from '@/hooks/useViewMode';
import { cn } from '@/lib/utils';

const MIN_WIDTH = 260;
const MAX_WIDTH = 720;

export default function RunsPage() {
  const activeRunId = useRunStore((s) => s.activeRunId);
  const activeRun = useRunStore((s) => s.activeRun);
  useSse(activeRunId);
  const isMobile = useIsMobile();

  // Reset tab when run changes
  const setActiveTab = useUiStore((s) => s.setActiveTab);
  const runListCollapsed = useUiStore((s) => s.runListCollapsed);
  const setRunListCollapsed = useUiStore((s) => s.setRunListCollapsed);
  const runListWidth = useUiStore((s) => s.runListWidth);
  const setRunListWidth = useUiStore((s) => s.setRunListWidth);

  const { isVibe } = useViewMode();

  // Mobile: show either the list or the detail, not both
  const mobileShowDetail = isMobile && !!activeRunId;

  // ── Drag resize logic ──────────────────────────────────────────────────
  const dragging = useRef(false);

  const onPointerDown = useCallback((e: React.PointerEvent) => {
    e.preventDefault();
    dragging.current = true;
    const startX = e.clientX;
    const startW = runListWidth;

    const onMove = (ev: PointerEvent) => {
      if (!dragging.current) return;
      const delta = ev.clientX - startX;
      const newW = Math.min(MAX_WIDTH, Math.max(MIN_WIDTH, startW + delta));
      setRunListWidth(newW);
    };

    const onUp = () => {
      dragging.current = false;
      document.removeEventListener('pointermove', onMove);
      document.removeEventListener('pointerup', onUp);
      document.body.style.cursor = '';
      document.body.style.userSelect = '';
    };

    document.body.style.cursor = 'col-resize';
    document.body.style.userSelect = 'none';
    document.addEventListener('pointermove', onMove);
    document.addEventListener('pointerup', onUp);
  }, [runListWidth, setRunListWidth]);

  return (
    <div className="flex h-full overflow-hidden">
      {/* Left panel — run list (collapsible, resizable) */}
      {(!isMobile || !mobileShowDetail) && (
        <div
          className={cn(
            'relative flex-shrink-0 border-r border-[var(--border-default)] overflow-hidden transition-[width] duration-200',
            runListCollapsed && 'w-0 border-r-0'
          )}
          style={!isMobile && !runListCollapsed ? { width: runListWidth } : undefined}
        >
          <RunList />
        </div>
      )}

      {/* Resize handle */}
      {!isMobile && !runListCollapsed && (
        <div
          onPointerDown={onPointerDown}
          className="relative z-10 w-1 flex-shrink-0 cursor-col-resize group"
        >
          <div className="absolute inset-y-0 -left-0.5 w-2 group-hover:bg-accent-blue/20 transition-colors" />
        </div>
      )}

      {/* Right panel — run detail */}
      {(!isMobile || mobileShowDetail) && (
        <div className="flex-1 overflow-hidden relative min-w-0">
          {activeRun ? (
            <RunDetail />
          ) : (
            <div className="h-full flex flex-col items-center justify-center gap-3">
              {isVibe ? (
                <>
                  <Sparkles className="h-8 w-8 text-accent-blue/40" />
                  <p className="text-sm text-muted">What do you want to build?</p>
                  <p className="text-xs text-muted">Select a run or start a new one</p>
                </>
              ) : (
                <p className="text-sm text-muted">Select a run to view details</p>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
