import { useEffect, useRef } from 'react';
import { useSseStore } from '@/stores/useSseStore';
import { cn } from '@/lib/utils';

const TYPE_COLORS: Record<string, string> = {
  runStatus:         'text-accent-violet',
  taskStatus:        'text-accent-cyan',
  log:               'text-zinc-300',
  gateOpened:        'text-accent-amber',
  gateResolved:      'text-accent-emerald',
  costUpdate:        'text-accent-emerald',
  queued:            'text-zinc-500',
  complete:          'text-accent-emerald',
  recoveryAttempted: 'text-accent-amber',
  runRestarted:      'text-accent-violet',
  deployStarted:     'text-accent-cyan',
  deployLive:        'text-accent-emerald',
  deployFailed:      'text-accent-rose',
};

function formatEntry(type: string, data: Record<string, unknown>): string {
  if (type === 'log' && data.message) return String(data.message);
  if (type === 'runStatus') return `Run status → ${data.status}`;
  if (type === 'taskStatus') return `Task ${data.taskId ?? ''} → ${data.status}`;
  if (type === 'gateOpened') return `Gate opened: ${data.gateType ?? ''} — waiting for action`;
  if (type === 'gateResolved') return `Gate resolved: ${data.gateType ?? ''} → ${data.decision}`;
  if (type === 'costUpdate') return `Cost update: $${Number(data.totalCost ?? 0).toFixed(4)} (${data.totalTokens ?? 0} tokens)`;
  if (type === 'complete') return `Run complete`;
  if (type === 'recoveryAttempted') return `Recovery attempt: ${data.strategy}`;
  if (type === 'runRestarted') return `Run restarted`;
  if (type === 'deployStarted') return `Deployment started`;
  if (type === 'deployLive') return `Deployment live at ${data.serviceUrl}`;
  if (type === 'deployFailed') return `Deployment failed: ${data.error}`;
  return JSON.stringify(data);
}

export default function LiveLog() {
  const { entries, status } = useSseStore();
  const bottomRef = useRef<HTMLDivElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const isPausedRef = useRef(false);

  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;
    const onScroll = () => {
      const atBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 40;
      isPausedRef.current = !atBottom;
    };
    el.addEventListener('scroll', onScroll);
    return () => el.removeEventListener('scroll', onScroll);
  }, []);

  useEffect(() => {
    if (!isPausedRef.current) {
      bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
    }
  }, [entries]);

  return (
    <div className="flex flex-col h-full">
      <div
        ref={containerRef}
        className="flex-1 overflow-y-auto terminal-log p-4 space-y-0.5 text-xs"
      >
        {entries.length === 0 && (
          <p className="text-zinc-600 italic">Waiting for events...</p>
        )}
        {entries.map((entry) => (
          <div key={entry.id} className="flex gap-2">
            <span className="text-zinc-700 flex-shrink-0">
              {new Date(entry.timestamp).toISOString().split('T')[1].replace('Z', '')}
            </span>
            <span className={cn('flex-shrink-0 w-16 truncate', TYPE_COLORS[entry.type] ?? 'text-zinc-500')}>
              [{entry.type}]
            </span>
            <span className={cn(TYPE_COLORS[entry.type] ?? 'text-zinc-300')}>
              {formatEntry(entry.type, entry.data)}
            </span>
          </div>
        ))}
        <div ref={bottomRef} />
      </div>

      {/* Status bar */}
      <div className="flex items-center gap-2 px-4 py-2 border-t border-white/[0.06] bg-surface-1">
        {status === 'CONNECTED' ? (
          <>
            <span className="relative flex h-1.5 w-1.5">
              <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-accent-emerald opacity-75" />
              <span className="relative inline-flex rounded-full h-1.5 w-1.5 bg-accent-emerald" />
            </span>
            <span className="text-xs text-zinc-500">Streaming</span>
            <span className="animate-pulse text-accent-emerald text-xs">▋</span>
          </>
        ) : status === 'RECONNECTING' ? (
          <span className="text-xs text-accent-amber">Reconnecting...</span>
        ) : (
          <span className="text-xs text-zinc-600">Disconnected · {entries.length} events</span>
        )}
      </div>
    </div>
  );
}
