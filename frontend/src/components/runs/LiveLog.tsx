import { useEffect, useRef } from 'react';
import { useSseStore, LogEntry } from '@/stores/useSseStore';
import { cn } from '@/lib/utils';
import {
  CheckCircle2, XCircle, Clock, Zap, GitCommit,
  FilePlus2, FileEdit, Terminal, PauseCircle, PlayCircle,
  DollarSign, RotateCcw, Rocket, AlertTriangle,
  Search, Bug, Wrench, FlaskConical, Eye, MessageSquare,
  ArrowUpCircle, Globe, FileCode, Shield,
} from 'lucide-react';

// ── Milestone detection ───────────────────────────────────────────────────────

const MILESTONE_TYPES = new Set([
  'RUN_STATUS', 'TASK_STATUS', 'GATE_OPENED', 'GATE_RESOLVED',
  'COMPLETE', 'COST_UPDATE', 'QUEUED', 'RECOVERY_ATTEMPTED',
  'RUN_RESTARTED',
  // Deploy events
  'DEPLOY_STARTED', 'DEPLOY_BUILDING', 'DEPLOY_PUSHING', 'DEPLOY_LIVE', 'DEPLOY_FAILED',
  // Deploy Engine events (user's GCP)
  'ENGINE_DEPLOY_STARTED', 'ENGINE_PHASE', 'ENGINE_BUILDING', 'ENGINE_DEPLOYING',
  'ENGINE_DEPLOY_LIVE', 'ENGINE_DEPLOY_FAILED',
  // Review V2 events
  'REVIEW_STARTED', 'REVIEW_ISSUE_FOUND', 'REVIEW_AUTO_FIX_STARTED',
  'REVIEW_AUTO_FIX_RESULT', 'REVIEW_COMPLETE',
  // Test gen pipeline events
  'TEST_GEN_STARTED', 'TEST_PLANNING_STARTED', 'TEST_PLANNING_COMPLETED',
  'TEST_GENERATION_STARTED', 'TEST_COMPILATION_STARTED', 'TEST_COMPILATION_PASSED',
  'TEST_COMPILATION_FAILED', 'TEST_EXECUTION_STARTED', 'TEST_EXECUTION_SKIPPED',
  'TEST_REPORT_READY', 'TEST_PIPELINE_FAILED',
  // Context & validation events
  'CONTEXT_EXTRACTED', 'CLARIFICATION_NEEDED', 'CLARIFICATION_RESOLVED',
  'MODEL_ESCALATED', 'API_SPEC_READY',
  // Preview lifecycle events
  'PREVIEW_ANALYZING', 'PREVIEW_BUILDING', 'PREVIEW_STARTING',
  'PREVIEW_READY', 'PREVIEW_STOPPED', 'PREVIEW_TIMED_OUT', 'PREVIEW_FAILED',
  'VALIDATION_STARTED', 'VALIDATION_PASSED', 'VALIDATION_FAILED',
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
  let colorClass = 'border-[var(--gray-400)] text-secondary bg-[var(--gray-200)]';

  // Task status
  if (type === 'TASK_STATUS') {
    const status = String(data.status ?? data.payload ?? '');
    const label = humanizeTaskId(String(data.taskId ?? ''));
    if (status === 'IN_PROGRESS' || status === 'RUNNING') {
      icon = <Zap className="h-3.5 w-3.5" />;
      text = `Task started: ${label}`;
      colorClass = 'border-accent-blue/50 text-secondary bg-[var(--gray-500)]/[0.06]';
    } else if (status === 'COMPLETE' || status === 'COMPLETED') {
      icon = <CheckCircle2 className="h-3.5 w-3.5" />;
      text = `Task done: ${label}`;
      colorClass = 'border-accent-green/50 text-accent-green bg-accent-green/[0.06]';
    } else if (status === 'FAILED') {
      icon = <XCircle className="h-3.5 w-3.5" />;
      text = `Task failed: ${label}`;
      colorClass = 'border-accent-red/50 text-accent-red bg-accent-red/[0.06]';
    } else if (status === 'PARTIAL') {
      icon = <AlertTriangle className="h-3.5 w-3.5" />;
      text = `Task partial: ${label}`;
      colorClass = 'border-accent-amber/50 text-accent-amber bg-accent-amber/[0.06]';
    } else {
      icon = <Clock className="h-3.5 w-3.5" />;
      text = `Task ${status.toLowerCase()}: ${label}`;
      colorClass = 'border-[var(--gray-400)] text-secondary bg-[var(--gray-200)]';
    }
  }

  // Run status
  else if (type === 'RUN_STATUS') {
    const status = String(data.status ?? data.payload ?? '');
    if (status === 'PARTIAL') {
      icon = <AlertTriangle className="h-3.5 w-3.5" />;
      text = 'Run partial — some tasks need manual retry';
      colorClass = 'border-accent-amber/50 text-accent-amber bg-accent-amber/[0.06]';
    } else if (status === 'FAILED') {
      icon = <XCircle className="h-3.5 w-3.5" />;
      text = 'Run failed — check tasks for retry';
      colorClass = 'border-accent-red/50 text-accent-red bg-accent-red/[0.06]';
    } else if (status === 'COMPLETE') {
      icon = <CheckCircle2 className="h-3.5 w-3.5" />;
      text = 'Run completed successfully';
      colorClass = 'border-accent-green/50 text-accent-green bg-accent-green/[0.08]';
    } else {
      icon = <Rocket className="h-3.5 w-3.5" />;
      text = `Run → ${status}`;
      colorClass = 'border-accent-blue/50 text-accent-blue bg-accent-blue/[0.06]';
    }
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
      ? 'border-accent-green/50 text-accent-green bg-accent-green/[0.06]'
      : 'border-accent-red/50 text-accent-red bg-accent-red/[0.06]';
  }

  // Cost update
  else if (type === 'COST_UPDATE') {
    icon = <DollarSign className="h-3.5 w-3.5" />;
    const cost = Number(data.totalCost ?? data.payload ?? 0);
    const tokens = data.totalTokens ?? '';
    text = `Cost update: $${cost.toFixed(4)}${tokens ? ` · ${tokens} tokens` : ''}`;
    colorClass = 'border-accent-green/30 text-accent-green/70 bg-accent-green/[0.04]';
  }

  // Complete
  else if (type === 'COMPLETE') {
    icon = <CheckCircle2 className="h-3.5 w-3.5" />;
    text = `Pipeline complete`;
    colorClass = 'border-accent-green/50 text-accent-green bg-accent-green/[0.08]';
  }

  // Recovery
  else if (type === 'RECOVERY_ATTEMPTED') {
    icon = <RotateCcw className="h-3.5 w-3.5" />;
    text = `Recovery: ${String(data.strategy ?? data.payload ?? '')}`;
    colorClass = 'border-accent-amber/50 text-accent-amber bg-accent-amber/[0.06]';
  }

  // Deploy events
  else if (type === 'DEPLOY_STARTED' || type === 'ENGINE_DEPLOY_STARTED') {
    icon = <Rocket className="h-3.5 w-3.5" />;
    text = 'Deployment started';
    colorClass = 'border-accent-blue/50 text-secondary bg-[var(--gray-500)]/[0.06]';
  } else if (type === 'DEPLOY_BUILDING' || type === 'ENGINE_BUILDING') {
    icon = <Rocket className="h-3.5 w-3.5" />;
    text = 'Building container image…';
    colorClass = 'border-accent-blue/40 text-secondary/80 bg-[var(--gray-500)]/[0.04]';
  } else if (type === 'DEPLOY_PUSHING' || type === 'ENGINE_DEPLOYING') {
    icon = <Rocket className="h-3.5 w-3.5" />;
    text = type.includes('PUSHING') ? 'Pushing image…' : 'Deploying service…';
    colorClass = 'border-accent-blue/40 text-secondary/80 bg-[var(--gray-500)]/[0.04]';
  } else if (type === 'DEPLOY_LIVE' || type === 'ENGINE_DEPLOY_LIVE') {
    icon = <CheckCircle2 className="h-3.5 w-3.5" />;
    const url = String(data.payload ?? '').split('|').pop();
    text = `Live at ${url}`;
    colorClass = 'border-accent-green/50 text-accent-green bg-accent-green/[0.08]';
  } else if (type === 'DEPLOY_FAILED' || type === 'ENGINE_DEPLOY_FAILED') {
    icon = <XCircle className="h-3.5 w-3.5" />;
    const err = String(data.payload ?? '').split('|').pop();
    text = `Deploy failed: ${err}`;
    colorClass = 'border-accent-red/50 text-accent-red bg-accent-red/[0.06]';
  } else if (type === 'ENGINE_PHASE') {
    icon = <Rocket className="h-3.5 w-3.5" />;
    const parts = String(data.payload ?? '').split('|');
    text = `Deploy: ${parts[1] ?? parts[0] ?? ''}`;
    colorClass = 'border-accent-blue/40 text-secondary/80 bg-[var(--gray-500)]/[0.04]';
  }

  // ── Review V2 events ────────────────────────────────────────────────────
  else if (type === 'REVIEW_STARTED') {
    icon = <Eye className="h-3.5 w-3.5" />;
    text = `Code review started (${String(data.payload ?? '')})`;
    colorClass = 'border-accent-blue/50 text-accent-blue bg-accent-blue/[0.06]';
  } else if (type === 'REVIEW_ISSUE_FOUND') {
    const parts = String(data.payload ?? '').split('|');
    const severity = parts[1] ?? 'UNKNOWN';
    const desc = parts[2] ?? parts[0] ?? '';
    const isCritical = severity === 'CRITICAL';
    icon = isCritical ? <Bug className="h-3.5 w-3.5" /> : <AlertTriangle className="h-3.5 w-3.5" />;
    text = `Issue: ${desc}`;
    colorClass = isCritical
      ? 'border-accent-red/40 text-accent-red/80 bg-accent-red/[0.04]'
      : 'border-accent-amber/40 text-accent-amber/80 bg-accent-amber/[0.04]';
  } else if (type === 'REVIEW_AUTO_FIX_STARTED') {
    icon = <Wrench className="h-3.5 w-3.5" />;
    text = `Auto-fixing ${String(data.payload ?? '')}`;
    colorClass = 'border-accent-blue/40 text-secondary/80 bg-[var(--gray-500)]/[0.04]';
  } else if (type === 'REVIEW_AUTO_FIX_RESULT') {
    const parts = String(data.payload ?? '').split('|');
    const success = parts[1] === 'PASS';
    icon = success ? <CheckCircle2 className="h-3.5 w-3.5" /> : <XCircle className="h-3.5 w-3.5" />;
    text = `Auto-fix ${parts[0]}: ${success ? 'succeeded' : 'failed'}`;
    colorClass = success
      ? 'border-accent-green/40 text-accent-green/80 bg-accent-green/[0.04]'
      : 'border-accent-red/40 text-accent-red/80 bg-accent-red/[0.04]';
  } else if (type === 'REVIEW_COMPLETE') {
    const parts = String(data.payload ?? '').split('|');
    const verdict = parts[0] ?? '';
    const isGood = verdict === 'APPROVED' || verdict === 'APPROVED_WITH_NOTES';
    icon = isGood ? <CheckCircle2 className="h-3.5 w-3.5" /> : <XCircle className="h-3.5 w-3.5" />;
    text = `Review: ${verdict.replace(/_/g, ' ').toLowerCase()}`;
    colorClass = isGood
      ? 'border-accent-green/50 text-accent-green bg-accent-green/[0.06]'
      : 'border-accent-red/50 text-accent-red bg-accent-red/[0.06]';
  }

  // ── Test generation pipeline events ─────────────────────────────────────
  else if (type === 'TEST_GEN_STARTED') {
    icon = <FlaskConical className="h-3.5 w-3.5" />;
    text = 'Test generation started';
    colorClass = 'border-accent-blue/50 text-accent-blue bg-accent-blue/[0.06]';
  } else if (type === 'TEST_PLANNING_STARTED') {
    icon = <FlaskConical className="h-3.5 w-3.5" />;
    text = 'Planning test strategy…';
    colorClass = 'border-accent-blue/40 text-accent-blue/80 bg-accent-blue/[0.04]';
  } else if (type === 'TEST_PLANNING_COMPLETED') {
    icon = <FlaskConical className="h-3.5 w-3.5" />;
    text = `Test plan ready: ${String(data.payload ?? '?')} test tasks`;
    colorClass = 'border-accent-blue/40 text-accent-blue/80 bg-accent-blue/[0.04]';
  } else if (type === 'TEST_GENERATION_STARTED') {
    icon = <FileCode className="h-3.5 w-3.5" />;
    text = `Generating ${String(data.payload ?? '?')} test suites…`;
    colorClass = 'border-accent-blue/40 text-secondary/80 bg-[var(--gray-500)]/[0.04]';
  } else if (type === 'TEST_COMPILATION_STARTED') {
    icon = <FlaskConical className="h-3.5 w-3.5" />;
    text = 'Compiling tests…';
    colorClass = 'border-accent-blue/40 text-secondary/80 bg-[var(--gray-500)]/[0.04]';
  } else if (type === 'TEST_COMPILATION_PASSED') {
    icon = <CheckCircle2 className="h-3.5 w-3.5" />;
    text = 'Tests compiled successfully';
    colorClass = 'border-accent-green/40 text-accent-green/80 bg-accent-green/[0.04]';
  } else if (type === 'TEST_COMPILATION_FAILED') {
    icon = <XCircle className="h-3.5 w-3.5" />;
    text = `Test compilation failed: ${String(data.payload ?? '').slice(0, 100)}`;
    colorClass = 'border-accent-red/40 text-accent-red/80 bg-accent-red/[0.04]';
  } else if (type === 'TEST_EXECUTION_STARTED') {
    icon = <FlaskConical className="h-3.5 w-3.5" />;
    text = 'Running tests against preview…';
    colorClass = 'border-accent-blue/50 text-secondary bg-[var(--gray-500)]/[0.06]';
  } else if (type === 'TEST_EXECUTION_SKIPPED') {
    icon = <AlertTriangle className="h-3.5 w-3.5" />;
    text = `Test execution skipped: ${String(data.payload ?? '')}`;
    colorClass = 'border-accent-amber/40 text-accent-amber/80 bg-accent-amber/[0.04]';
  } else if (type === 'TEST_REPORT_READY') {
    icon = <CheckCircle2 className="h-3.5 w-3.5" />;
    text = 'Test report ready';
    colorClass = 'border-accent-green/50 text-accent-green bg-accent-green/[0.08]';
  } else if (type === 'TEST_PIPELINE_FAILED') {
    icon = <XCircle className="h-3.5 w-3.5" />;
    text = `Test pipeline failed: ${String(data.payload ?? '').slice(0, 100)}`;
    colorClass = 'border-accent-red/50 text-accent-red bg-accent-red/[0.06]';
  }

  // ── Context & validation events ─────────────────────────────────────────
  else if (type === 'CONTEXT_EXTRACTED') {
    icon = <Search className="h-3.5 w-3.5" />;
    text = `Context: ${String(data.payload ?? '').slice(0, 120)}`;
    colorClass = 'border-[var(--gray-400)] text-secondary bg-[var(--gray-200)]';
  } else if (type === 'CLARIFICATION_NEEDED') {
    icon = <MessageSquare className="h-3.5 w-3.5" />;
    text = 'Clarification needed from user';
    colorClass = 'border-accent-amber/50 text-accent-amber bg-accent-amber/[0.06]';
  } else if (type === 'CLARIFICATION_RESOLVED') {
    icon = <CheckCircle2 className="h-3.5 w-3.5" />;
    text = 'Clarification resolved';
    colorClass = 'border-accent-green/40 text-accent-green/80 bg-accent-green/[0.04]';
  } else if (type === 'MODEL_ESCALATED') {
    const parts = String(data.payload ?? '').split('|');
    icon = <ArrowUpCircle className="h-3.5 w-3.5" />;
    text = `Model escalated: ${parts[0]} → ${parts[1] ?? ''}`;
    colorClass = 'border-accent-amber/50 text-accent-amber bg-accent-amber/[0.06]';
  } else if (type === 'API_SPEC_READY') {
    icon = <FileCode className="h-3.5 w-3.5" />;
    text = 'API spec generated';
    colorClass = 'border-accent-blue/40 text-secondary/80 bg-[var(--gray-500)]/[0.04]';
  } else if (type === 'PREVIEW_ANALYZING') {
    icon = <Search className="h-3.5 w-3.5" />;
    text = `Analyzing project: ${String(data.payload ?? '').slice(0, 100)}`;
    colorClass = 'border-[var(--gray-400)] text-secondary bg-[var(--gray-200)]';
  } else if (type === 'PREVIEW_BUILDING') {
    icon = <Rocket className="h-3.5 w-3.5" />;
    text = `Building preview: ${String(data.payload ?? '').slice(0, 100)}`;
    colorClass = 'border-accent-blue/40 text-secondary/80 bg-[var(--gray-500)]/[0.04]';
  } else if (type === 'PREVIEW_STARTING') {
    icon = <Rocket className="h-3.5 w-3.5" />;
    text = `Starting preview: ${String(data.payload ?? '').slice(0, 100)}`;
    colorClass = 'border-accent-blue/50 text-secondary bg-[var(--gray-500)]/[0.06]';
  } else if (type === 'PREVIEW_READY') {
    icon = <Globe className="h-3.5 w-3.5" />;
    text = `Preview ready${data.payload ? ': ' + String(data.payload).slice(0, 80) : ''}`;
    colorClass = 'border-accent-green/50 text-accent-green bg-accent-green/[0.08]';
  } else if (type === 'PREVIEW_STOPPED') {
    icon = <PauseCircle className="h-3.5 w-3.5" />;
    text = 'Preview stopped';
    colorClass = 'border-[var(--gray-400)] text-secondary bg-[var(--gray-200)]';
  } else if (type === 'PREVIEW_TIMED_OUT') {
    icon = <Clock className="h-3.5 w-3.5" />;
    text = `Preview timed out${data.payload ? ': ' + String(data.payload).slice(0, 80) : ''}`;
    colorClass = 'border-accent-amber/40 text-accent-amber/80 bg-accent-amber/[0.04]';
  } else if (type === 'PREVIEW_FAILED') {
    icon = <XCircle className="h-3.5 w-3.5" />;
    text = `Preview failed${data.payload ? ': ' + String(data.payload).slice(0, 100) : ''}`;
    colorClass = 'border-accent-red/50 text-accent-red bg-accent-red/[0.06]';
  } else if (type === 'VALIDATION_STARTED') {
    icon = <Shield className="h-3.5 w-3.5" />;
    text = `Validating ${String(data.payload ?? '')}`;
    colorClass = 'border-[var(--gray-400)] text-secondary bg-[var(--gray-200)]';
  } else if (type === 'VALIDATION_PASSED') {
    icon = <CheckCircle2 className="h-3.5 w-3.5" />;
    text = 'Validation passed';
    colorClass = 'border-accent-green/40 text-accent-green/80 bg-accent-green/[0.04]';
  } else if (type === 'VALIDATION_FAILED') {
    icon = <XCircle className="h-3.5 w-3.5" />;
    text = `Validation failed: ${String(data.payload ?? '').slice(0, 100)}`;
    colorClass = 'border-accent-red/40 text-accent-red/80 bg-accent-red/[0.04]';
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
    write:   { icon: <FilePlus2 className="h-3 w-3" />, label: 'Created', color: 'text-accent-blue/80' },
    edit:    { icon: <FileEdit  className="h-3 w-3" />, label: 'Edited',  color: 'text-secondary/80'   },
    commit:  { icon: <GitCommit className="h-3 w-3" />, label: 'Committed to Git', color: 'text-accent-green/80' },
    command: { icon: <Terminal  className="h-3 w-3" />, label: 'Command run', color: 'text-secondary' },
  }[op.kind];

  return (
    <div className="flex items-center gap-2 px-2 py-0.5 text-[11px]">
      <span className="text-[var(--text-muted)] flex-shrink-0 font-mono">{formatTimestamp(timestamp)}</span>
      <span className={cn('flex items-center gap-1.5 flex-shrink-0', config.color)}>
        {config.icon}
        <span className="font-medium">{config.label}</span>
      </span>
      {op.path && (
        <span className="text-secondary font-mono truncate">{op.path}</span>
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
          <p className="text-muted italic text-xs px-2">Waiting for events…</p>
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
            const taskId = String(entry.data.taskId ?? '');
            const isHarnessMsg = /^[✅⚠️❌🔧]/.test(msg)
              || taskId === 'harness'
              || taskId === 'planner'
              || taskId === 'compiler'
              || taskId === 'reviewer'
              || taskId === 'report'
              || taskId === 'preview-agent'
              || msg.startsWith('Merging')
              || msg.startsWith('Simple request')
              || msg.startsWith('Reviewer')
              || msg.startsWith('Compile')
              || msg.startsWith('Background')
              || msg.startsWith('Code ready')
              || msg.startsWith('Plan')
              || /^\[[\w-]+\]/.test(msg);

            if (isHarnessMsg) {
              // Determine icon and color based on taskId or prefix
              let logIcon = <Rocket className="h-3.5 w-3.5" />;
              let bannerColor = 'border-[var(--gray-400)] text-secondary bg-[var(--gray-200)]';

              if (/^✅/.test(msg) || msg.includes('successfully') || msg.includes('passed')) {
                logIcon = <CheckCircle2 className="h-3.5 w-3.5" />;
                bannerColor = 'border-accent-green/50 text-accent-green bg-accent-green/[0.06]';
              } else if (/^⚠️/.test(msg) || msg.includes('WARNING')) {
                logIcon = <AlertTriangle className="h-3.5 w-3.5" />;
                bannerColor = 'border-accent-amber/50 text-accent-amber bg-accent-amber/[0.06]';
              } else if (/^❌/.test(msg) || msg.includes('failed') || msg.includes('Error')) {
                logIcon = <XCircle className="h-3.5 w-3.5" />;
                bannerColor = 'border-accent-red/50 text-accent-red bg-accent-red/[0.06]';
              } else if (/^🔧/.test(msg) || taskId === 'reviewer' && msg.includes('fix')) {
                logIcon = <Wrench className="h-3.5 w-3.5" />;
                bannerColor = 'border-accent-blue/40 text-secondary/80 bg-[var(--gray-500)]/[0.04]';
              } else if (taskId === 'planner' || /^Plan/i.test(msg)) {
                logIcon = <Zap className="h-3.5 w-3.5" />;
                bannerColor = 'border-accent-planner/50 text-accent-planner bg-accent-planner/[0.06]';
              } else if (taskId === 'harness' || /^Merging|^\[harness\]/i.test(msg) || /^Background/i.test(msg)) {
                logIcon = <GitCommit className="h-3.5 w-3.5" />;
                bannerColor = 'border-accent-harness/50 text-accent-harness bg-accent-harness/[0.06]';
              } else if (taskId === 'compiler' || /^Compile|^Compiling/i.test(msg)) {
                logIcon = <Terminal className="h-3.5 w-3.5" />;
                bannerColor = 'border-accent-blue/50 text-accent-blue bg-accent-blue/[0.06]';
              } else if (taskId === 'reviewer' || /^Reviewer|^\[reviewer\]/i.test(msg)) {
                logIcon = <Eye className="h-3.5 w-3.5" />;
                bannerColor = 'border-accent-blue/40 text-secondary/80 bg-[var(--gray-500)]/[0.04]';
              } else if (taskId === 'preview-agent') {
                logIcon = <Globe className="h-3.5 w-3.5" />;
                bannerColor = 'border-accent-blue/40 text-secondary/80 bg-[var(--gray-500)]/[0.04]';
              } else if (/^Simple request/i.test(msg)) {
                logIcon = <Zap className="h-3.5 w-3.5" />;
                bannerColor = 'border-accent-blue/50 text-accent-blue bg-accent-blue/[0.06]';
              }

              // Strip emoji prefix / tag for cleaner display
              const cleanMsg = msg.replace(/^[✅⚠️❌🔧]\s*/, '').replace(/^\[[\w-]+\]\s*/, '');

              return (
                <div key={entry.id} className={cn(
                  'flex items-center gap-2.5 px-3 py-1.5 rounded-sm border',
                  'text-xs font-medium my-1',
                  bannerColor,
                )}>
                  {logIcon}
                  <span className="flex-1">{cleanMsg.length > 200 ? cleanMsg.slice(0, 200) + '…' : cleanMsg}</span>
                  <span className="text-[10px] opacity-50 font-mono">{formatTimestamp(entry.timestamp)}</span>
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
              <span className="text-[var(--text-muted)] font-mono">{formatTimestamp(entry.timestamp)}</span>
              <span className="text-muted">[{entry.type}]</span>
              <span className="text-secondary truncate">{fallbackMsg.slice(0, 160)}</span>
            </div>
          );
        })}

        <div ref={bottomRef} />
      </div>

      {/* Status bar */}
      <div className="flex items-center gap-2 px-4 py-2 border-t border-[var(--border-default)] bg-[var(--gray-100)] flex-shrink-0">
        {status === 'CONNECTED' ? (
          <>
            <span className="relative flex h-1.5 w-1.5">
              <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-accent-green opacity-75" />
              <span className="relative inline-flex rounded-full h-1.5 w-1.5 bg-accent-green" />
            </span>
            <span className="text-xs text-muted">Streaming</span>
            <span className="animate-pulse text-accent-green text-xs">▋</span>
          </>
        ) : status === 'RECONNECTING' ? (
          <span className="text-xs text-accent-amber">Reconnecting…</span>
        ) : (
          <span className="text-xs text-muted">Disconnected · {entries.length} events</span>
        )}
      </div>
    </div>
  );
}
