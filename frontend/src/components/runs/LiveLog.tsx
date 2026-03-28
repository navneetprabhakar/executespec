import { useEffect, useRef } from 'react';
import { useSseStore, LogEntry } from '@/stores/useSseStore';
import { cn } from '@/lib/utils';
import {
  CheckCircle2, XCircle, Clock, Zap, GitCommit,
  FilePlus2, FileEdit, Terminal, PauseCircle, PlayCircle,
  DollarSign, RotateCcw, Rocket, AlertTriangle,
} from 'lucide-react';

// ── Milestone detection ───────────────────────────────────────────────────────

const MILESTONE_TYPES = new Set([
  'RUN_STATUS', 'TASK_STATUS', 'GATE_OPENED', 'GATE_RESOLVED',
  'COMPLETE', 'COST_UPDATE', 'QUEUED', 'RECOVERY_ATTEMPTED',
  'RUN_RESTARTED', 'DEPLOY_STARTED', 'DEPLOY_BUILDING', 'DEPLOY_PUSHING',
  'DEPLOY_LIVE', 'DEPLOY_FAILED',
]);

type FileOp = { kind: 'write' | 'edit' | 'commit' | 'command'; path?: string };

/** Parse a log message for known file / command tool-result markers. */
function detectFileOp(msg: string): FileOp | null {
  if (/\[(write_file|writeFile)\s+result\]/i.test(msg)) {
    // Try to pull the file path from the output ("to src/main/java/Foo.java")
    const m = msg.match(/(?:\bto\b|\bfile:\b)\s+([\w./\\-]+\.\w+)/i);
    return { kind: 'write', path: m?.[1] };
  }
  if (/\[(edit_file|editFile)\s+result\]/i.test(msg)) {
    const m = msg.match(/(?:\bin\b|\bfile:\b)\s+([\w./\\-]+\.\w+)/i);
    return { kind: 'edit', path: m?.[1] };
  }
  if (/\[(commit|git_commit)\s+result\]/i.test(msg)) {
    return { kind: 'commit' };
  }
  if (/\[(run|run_command|run_build|run_tests)\s+result\]/i.test(msg)) {
    return { kind: 'command' };
  }
  return null;
}

/** Convert taskId like "task-1-project_setup" → "Project Setup" */
function humanizeTaskId(taskId: string): string {
  const parts = String(taskId).split('-');
  // taskId format: "task-N-name_part" — take everything after index 1
  const nameIdx = parts.findIndex((p) => isNaN(Number(p)) && p !== 'task' && p !== 'worker');
  const name = parts.slice(nameIdx >= 0 ? nameIdx : 2).join(' ');
  return name
    ? name.replace(/_/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase())
    : String(taskId);
}

// ── Formatters ────────────────────────────────────────────────────────────────

function formatTimestamp(ts: number): string {
  return new Date(ts).toISOString().split('T')[1].slice(0, 8);
}

function formatLogMessage(data: Record<string, unknown>): string {
  return String(data.message ?? data.payload ?? '');
}

// ── Sub-components ────────────────────────────────────────────────────────────

/** Full-width banner for pipeline milestones (task transitions, gates, etc.) */
function MilestoneBanner({ entry }: { entry: LogEntry }) {
  const { type, data, timestamp } = entry;

  let icon = <Clock className="h-3.5 w-3.5" />;
  let text = '';
  let colorClass = 'border-zinc-600 text-zinc-300 bg-white/[0.03]';

  // Task status
  if (type === 'TASK_STATUS') {
    const status = String(data.status ?? data.payload ?? '');
    const label = humanizeTaskId(String(data.taskId ?? ''));
    if (status === 'IN_PROGRESS' || status === 'RUNNING') {
      icon = <Zap className="h-3.5 w-3.5" />;
      text = `Task started: ${label}`;
      colorClass = 'border-accent-cyan/50 text-accent-cyan bg-accent-cyan/[0.06]';
    } else if (status === 'COMPLETE' || status === 'COMPLETED') {
      icon = <CheckCircle2 className="h-3.5 w-3.5" />;
      text = `Task done: ${label}`;
      colorClass = 'border-accent-emerald/50 text-accent-emerald bg-accent-emerald/[0.06]';
    } else if (status === 'FAILED') {
      icon = <XCircle className="h-3.5 w-3.5" />;
      text = `Task failed: ${label}`;
      colorClass = 'border-accent-rose/50 text-accent-rose bg-accent-rose/[0.06]';
    } else if (status === 'PARTIAL') {
      icon = <AlertTriangle className="h-3.5 w-3.5" />;
      text = `Task partial: ${label}`;
      colorClass = 'border-accent-amber/50 text-accent-amber bg-accent-amber/[0.06]';
    } else {
      icon = <Clock className="h-3.5 w-3.5" />;
      text = `Task ${status.toLowerCase()}: ${label}`;
      colorClass = 'border-zinc-600 text-zinc-400 bg-white/[0.03]';
    }
  }

  // Run status
  else if (type === 'RUN_STATUS') {
    const status = String(data.status ?? data.payload ?? '');
    icon = <Rocket className="h-3.5 w-3.5" />;
    text = `Run → ${status}`;
    colorClass = 'border-accent-violet/50 text-accent-violet bg-accent-violet/[0.06]';
  }

  // Gates
  else if (type === 'GATE_OPENED') {
    icon = <PauseCircle className="h-3.5 w-3.5" />;
    text = `Waiting for ${String(data.gateType ?? data.payload ?? 'approval')} approval`;
    colorClass = 'border-accent-amber/50 text-accent-amber bg-accent-amber/[0.06]';
  } else if (type === 'GATE_RESOLVED') {
    const decision = String(data.decision ?? data.payload ?? '');
    const approved = decision === 'APPROVED';
    icon = approved ? <PlayCircle className="h-3.5 w-3.5" /> : <XCircle className="h-3.5 w-3.5" />;
    text = `Gate ${decision.toLowerCase()}: ${String(data.gateType ?? '')}`;
    colorClass = approved
      ? 'border-accent-emerald/50 text-accent-emerald bg-accent-emerald/[0.06]'
      : 'border-accent-rose/50 text-accent-rose bg-accent-rose/[0.06]';
  }

  // Cost update
  else if (type === 'COST_UPDATE') {
    icon = <DollarSign className="h-3.5 w-3.5" />;
    const cost = Number(data.totalCost ?? data.payload ?? 0);
    const tokens = data.totalTokens ?? '';
    text = `Cost update: $${cost.toFixed(4)}${tokens ? ` · ${tokens} tokens` : ''}`;
    colorClass = 'border-accent-emerald/30 text-accent-emerald/70 bg-accent-emerald/[0.04]';
  }

  // Complete
  else if (type === 'COMPLETE') {
    icon = <CheckCircle2 className="h-3.5 w-3.5" />;
    text = `Pipeline complete`;
    colorClass = 'border-accent-emerald/50 text-accent-emerald bg-accent-emerald/[0.08]';
  }

  // Recovery
  else if (type === 'RECOVERY_ATTEMPTED') {
    icon = <RotateCcw className="h-3.5 w-3.5" />;
    text = `Recovery: ${String(data.strategy ?? data.payload ?? '')}`;
    colorClass = 'border-accent-amber/50 text-accent-amber bg-accent-amber/[0.06]';
  }

  // Deploy events
  else if (type === 'DEPLOY_STARTED') {
    icon = <Rocket className="h-3.5 w-3.5" />;
    text = 'Deployment started';
    colorClass = 'border-accent-cyan/50 text-accent-cyan bg-accent-cyan/[0.06]';
  } else if (type === 'DEPLOY_LIVE') {
    icon = <CheckCircle2 className="h-3.5 w-3.5" />;
    text = `Live at ${String(data.serviceUrl ?? data.payload ?? '')}`;
    colorClass = 'border-accent-emerald/50 text-accent-emerald bg-accent-emerald/[0.08]';
  } else if (type === 'DEPLOY_FAILED') {
    icon = <XCircle className="h-3.5 w-3.5" />;
    text = `Deploy failed: ${String(data.error ?? data.payload ?? '')}`;
    colorClass = 'border-accent-rose/50 text-accent-rose bg-accent-rose/[0.06]';
  }

  if (!text) return null;

  return (
    <div className={cn(
      'flex items-center gap-2.5 px-3 py-1.5 rounded-sm border',
      'text-xs font-medium my-1',
      colorClass,
    )}>
      {icon}
      <span className="flex-1">{text}</span>
      <span className="text-[10px] opacity-50 font-mono">{formatTimestamp(timestamp)}</span>
    </div>
  );
}

/** Compact row for detected file / command operations inside log messages. */
function FileOpRow({ entry, op }: { entry: LogEntry; op: FileOp }) {
  const { timestamp } = entry;

  const config = {
    write:   { icon: <FilePlus2 className="h-3 w-3" />, label: 'Created', color: 'text-accent-violet/80' },
    edit:    { icon: <FileEdit  className="h-3 w-3" />, label: 'Edited',  color: 'text-accent-cyan/80'   },
    commit:  { icon: <GitCommit className="h-3 w-3" />, label: 'Committed to Git', color: 'text-accent-emerald/80' },
    command: { icon: <Terminal  className="h-3 w-3" />, label: 'Command run', color: 'text-zinc-400' },
  }[op.kind];

  return (
    <div className="flex items-center gap-2 px-2 py-0.5 text-[11px]">
      <span className="text-zinc-700 flex-shrink-0 font-mono">{formatTimestamp(timestamp)}</span>
      <span className={cn('flex items-center gap-1.5 flex-shrink-0', config.color)}>
        {config.icon}
        <span className="font-medium">{config.label}</span>
      </span>
      {op.path && (
        <span className="text-zinc-400 font-mono truncate">{op.path}</span>
      )}
    </div>
  );
}

// ── Main component ────────────────────────────────────────────────────────────

export default function LiveLog() {
  const { entries, status } = useSseStore();
  const bottomRef   = useRef<HTMLDivElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const isPausedRef  = useRef(false);

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
        className="flex-1 overflow-y-auto terminal-log px-3 py-3 min-h-0"
      >
        {entries.length === 0 && (
          <p className="text-zinc-600 italic text-xs px-2">Waiting for events…</p>
        )}

        {entries.map((entry) => {
          // ── Milestone events → banner ───────────────────────────────────
          if (MILESTONE_TYPES.has(entry.type)) {
            return <MilestoneBanner key={entry.id} entry={entry} />;
          }

          // ── Log entries: show file ops + meaningful harness messages ────
          if (entry.type === 'LOG') {
            const msg = formatLogMessage(entry.data);
            const op  = detectFileOp(msg);
            if (op) return <FileOpRow key={entry.id} entry={entry} op={op} />;

            // Filter out noisy internal worker logs (tool dispatch, LLM calls, iterations)
            const isInternalWorkerNoise =
              /TOOL_DISPATCHED/i.test(msg)
              || /LLM_REQUEST_SENT/i.test(msg)
              || /\bIteration\s+\d+\s*\(/i.test(msg)
              || /STALL_TRUNCATION/i.test(msg)
              || /COMPACTION_TRIGGERED/i.test(msg);
            if (isInternalWorkerNoise) return null;

            // Show harness-level messages (emoji-prefixed or from known agents)
            const isHarnessMsg = /^[✅⚠️❌🔧]/.test(msg)
              || msg.startsWith('Merging')
              || msg.startsWith('Simple request')
              || msg.startsWith('Reviewer')
              || msg.startsWith('Compile')
              || /^\[[\w-]+\]/.test(msg);

            if (isHarnessMsg) {
              return (
                <div key={entry.id} className="flex gap-2 px-2 py-0.5 text-[11px]">
                  <span className="text-zinc-700 flex-shrink-0 font-mono">{formatTimestamp(entry.timestamp)}</span>
                  <span className="text-zinc-300">{msg.length > 200 ? msg.slice(0, 200) + '…' : msg}</span>
                </div>
              );
            }

            // Skip verbose tool-result log lines
            return null;
          }

          // ── Fallback for unknown types — show as readable text ─────────
          const fallbackMsg = String(entry.data?.payload ?? entry.data?.message ?? '');
          if (!fallbackMsg) return null;
          return (
            <div key={entry.id} className="flex gap-2 px-2 py-[1px] text-[11px] opacity-50">
              <span className="text-zinc-700 font-mono">{formatTimestamp(entry.timestamp)}</span>
              <span className="text-zinc-500">[{entry.type}]</span>
              <span className="text-zinc-400 truncate">{fallbackMsg.slice(0, 160)}</span>
            </div>
          );
        })}

        <div ref={bottomRef} />
      </div>

      {/* Status bar */}
      <div className="flex items-center gap-2 px-4 py-2 border-t border-white/[0.06] bg-surface-1 flex-shrink-0">
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
          <span className="text-xs text-accent-amber">Reconnecting…</span>
        ) : (
          <span className="text-xs text-zinc-600">Disconnected · {entries.length} events</span>
        )}
      </div>
    </div>
  );
}
