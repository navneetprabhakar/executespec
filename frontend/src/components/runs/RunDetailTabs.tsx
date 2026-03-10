import { useCallback, useEffect, useRef, useState } from 'react';
import * as Tabs from '@radix-ui/react-tabs';
import { useUiStore } from '@/stores/useUiStore';
import { useRunStore } from '@/stores/useRunStore';
import { useAuthStore, selectIsSuperAdmin } from '@/stores/useAuthStore';
import {
  getFiles, getFile,
  getOpenApiSpec,
} from '@/lib/api';
import type { RunDetail } from '@/lib/api';
import GlowDot from '@/components/shared/GlowDot';
import TaskCard from './TaskCard';
import LiveLog from './LiveLog';
import RunConversation from './RunConversation';
import TaskGraph from './TaskGraph';
import PreviewTab from './PreviewTab';
import { PIPELINE_STEPS } from '@/lib/constants';
import { fmtTokens, relativeTime, cn } from '@/lib/utils';
import {
  ChevronDown, ChevronRight,
  Folder, FolderOpen,
  FileCode2, FileText, File,
  MessageSquare,
  ChevronsDownUp, ChevronsUpDown, Copy, Check,
  Clock, Coins,
} from 'lucide-react';
import hljs from 'highlight.js';

const BASE_TABS = [
  { value: 'overview',  label: 'Overview' },
  { value: 'plan',      label: 'Plan' },
  { value: 'tasks',     label: 'Tasks' },
  { value: 'graph',     label: 'Graph' },
  { value: 'log',       label: 'Live Log' },
  { value: 'code',      label: 'Code' },
  { value: 'review',    label: 'Review' },
];

export default function RunDetailTabs() {
  const { activeTab, setActiveTab } = useUiStore();
  const activeRun = useRunStore((s) => s.activeRun);

  // Hoist files so both Code and Preview tabs share them
  const [files, setFiles] = useState<string[]>([]);
  const [filesLoading, setFilesLoading] = useState(false);

  useEffect(() => {
    if (!activeRun) return;
    setFilesLoading(true);
    setFiles([]);
    getFiles(activeRun.runId)
      .then(setFiles)
      .catch(() => {})
      .finally(() => setFilesLoading(false));
  }, [activeRun?.runId]);
  // Note: live task updates are driven entirely by SSE (taskStatus events → fetchRun).
  // useSse also re-fetches on reconnect to catch any events missed during a gap.

  if (!activeRun) return null;

  /** Chat & Preview tabs are only shown for terminal (COMPLETE / PARTIAL) runs. */
  const showTerminalTabs = activeRun.status === 'COMPLETE' || activeRun.status === 'PARTIAL';
  const TABS = [
    ...BASE_TABS,
    ...(showTerminalTabs ? [
      { value: 'preview', label: 'Preview' },
      { value: 'chat', label: 'Chat' },
    ] : []),
  ];

  return (
    <Tabs.Root value={activeTab} onValueChange={setActiveTab} className="flex flex-col h-full">
      <Tabs.List className="flex gap-0 border-b border-white/[0.06] flex-shrink-0 px-4 overflow-x-auto">
        {TABS.map((tab) => (
          <Tabs.Trigger
            key={tab.value}
            value={tab.value}
            className={cn(
              'px-4 py-2.5 text-sm font-medium border-b-2 -mb-px transition-colors whitespace-nowrap',
              'data-[state=active]:border-accent-violet data-[state=active]:text-white',
              'data-[state=inactive]:border-transparent data-[state=inactive]:text-zinc-500',
              'hover:text-zinc-300'
            )}
          >
            {tab.value === 'chat' ? (
              <span className="flex items-center gap-1.5">
                <MessageSquare className="h-3 w-3" />
                {tab.label}
              </span>
            ) : tab.label}
          </Tabs.Trigger>
        ))}
      </Tabs.List>

      <div className="flex-1 overflow-hidden">
        <Tabs.Content value="overview" className="h-full overflow-y-auto p-4">
          <OverviewTab run={activeRun} />
        </Tabs.Content>

        <Tabs.Content value="tasks" className="h-full overflow-y-auto p-4 space-y-2">
          {activeRun.tasks.length === 0
            ? <p className="text-sm text-zinc-500">No tasks yet.</p>
            : activeRun.tasks.map((t) => (
                <TaskCard key={t.taskId} task={t} runId={activeRun.runId} runStatus={activeRun.status} />
              ))
          }
        </Tabs.Content>

        <Tabs.Content value="log" className="h-full">
          <LiveLog />
        </Tabs.Content>

        <Tabs.Content value="plan" className="h-full overflow-y-auto p-4">
          <PlanTab run={activeRun} />
        </Tabs.Content>

        <Tabs.Content value="review" className="h-full overflow-y-auto p-4">
          <ReviewTab run={activeRun} />
        </Tabs.Content>

        <Tabs.Content value="code" className="h-full overflow-hidden">
          <CodeTab runId={activeRun.runId} files={files} filesLoading={filesLoading} repoUrl={activeRun.repoUrl} status={activeRun.status} />
        </Tabs.Content>

        <Tabs.Content value="graph" className="h-full overflow-auto p-4">
          <TaskGraph runId={activeRun.runId} />
        </Tabs.Content>

        {showTerminalTabs && (
          <Tabs.Content value="preview" className="h-full overflow-hidden">
            <PreviewTab runId={activeRun.runId} />
          </Tabs.Content>
        )}

        {showTerminalTabs && (
          <Tabs.Content value="chat" className="h-full overflow-hidden">
            <RunConversation runId={activeRun.runId} />
          </Tabs.Content>
        )}
      </div>
    </Tabs.Root>
  );
}

// ── Overview tab ─────────────────────────────────────────────────────────────

/** Format duration in milliseconds to human-readable string. */
function fmtDuration(ms: number): string {
  if (ms < 1000) return `${ms}ms`;
  const s = Math.floor(ms / 1000);
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60);
  const rs = s % 60;
  if (m < 60) return `${m}m ${rs}s`;
  const h = Math.floor(m / 60);
  const rm = m % 60;
  return `${h}h ${rm}m`;
}

/** Aggregate token counts per model from task list. */
function tokensByModel(tasks: RunDetail['tasks']): { model: string; input: number; output: number }[] {
  const map = new Map<string, { input: number; output: number }>();
  for (const t of tasks) {
    const model = t.assignedModel || 'unknown';
    const entry = map.get(model) || { input: 0, output: 0 };
    entry.input += t.inputTokens || 0;
    entry.output += t.outputTokens || 0;
    map.set(model, entry);
  }
  return Array.from(map.entries())
    .map(([model, { input, output }]) => ({ model, input, output }))
    .sort((a, b) => (b.input + b.output) - (a.input + a.output));
}

function OverviewTab({ run }: { run: RunDetail }) {
  const isSuperAdmin = useAuthStore(selectIsSuperAdmin);

  // STUCK occurs during the WORKING phase — map it there for pipeline step display
  const displayStatus = run.status === 'STUCK' ? 'WORKING' : run.status;
  const stepIndex = PIPELINE_STEPS.indexOf(displayStatus);
  const totalTokens = (run.totalInputTokens || 0) + (run.totalOutputTokens || 0);

  // Compute run duration
  const startMs = run.startedAt ? new Date(run.startedAt).getTime() : 0;
  const endMs = run.completedAt ? new Date(run.completedAt).getTime() : Date.now();
  const durationMs = startMs ? endMs - startMs : 0;
  const isTerminal = ['COMPLETE', 'FAILED', 'CANCELLED', 'PARTIAL'].includes(run.status);

  return (
    <div className="space-y-6">
      {/* Stuck warning banner */}
      {run.status === 'STUCK' && (
        <div className="flex items-start gap-2 p-3 rounded-lg bg-accent-rose/10 border border-accent-rose/20">
          <span className="text-accent-rose text-sm flex-shrink-0">⚠</span>
          <div>
            <p className="text-sm font-medium text-accent-rose">Run is stuck</p>
            <p className="text-xs text-accent-rose/70 mt-0.5">
              No activity detected for 30+ minutes. Recovery has been triggered automatically.
            </p>
          </div>
        </div>
      )}

      {/* Pipeline timeline */}
      <div className="space-y-0">
        {PIPELINE_STEPS.map((step, i) => {
          const isDone = i < stepIndex;
          const isActive = step === displayStatus;
          return (
            <div key={step} className="flex items-start gap-3 relative">
              {/* Connector line */}
              {i < PIPELINE_STEPS.length - 1 && (
                <div className={cn(
                  'absolute left-[11px] top-6 w-0.5 h-5 -bottom-1',
                  isDone ? 'bg-accent-emerald/40' : 'bg-white/[0.06]'
                )} />
              )}
              <div className={cn(
                'relative z-10 h-6 w-6 rounded-full border flex items-center justify-center flex-shrink-0 mt-0.5',
                isDone ? 'border-accent-emerald bg-accent-emerald/20' :
                isActive ? 'border-accent-violet bg-accent-violet/20' :
                'border-white/[0.10] bg-transparent'
              )}>
                {isDone ? (
                  <span className="text-accent-emerald text-xs">✓</span>
                ) : isActive ? (
                  <span className="h-2 w-2 rounded-full bg-accent-violet animate-pulse" />
                ) : (
                  <span className="h-1.5 w-1.5 rounded-full bg-white/[0.15]" />
                )}
              </div>
              <div className="pb-5">
                <p className={cn(
                  'text-sm font-medium',
                  isDone ? 'text-accent-emerald' :
                  isActive ? 'text-white' : 'text-zinc-600'
                )}>
                  {step.replace(/_/g, ' ')}
                </p>
              </div>
            </div>
          );
        })}
      </div>

      {/* Run stats: time taken */}
      <div className="pt-2 border-t border-white/[0.06]">
        <div className="flex items-center gap-2 mb-3">
          <Clock className="h-3.5 w-3.5 text-zinc-500" />
          <span className="text-xs font-medium text-zinc-400 uppercase tracking-wider">Run Stats</span>
        </div>
        <div className="grid grid-cols-2 gap-3">
          <div>
            <p className="text-xs text-zinc-600">Time taken</p>
            <p className="text-sm text-zinc-200 mt-0.5 font-mono">
              {durationMs > 0
                ? `${fmtDuration(durationMs)}${!isTerminal ? ' (running)' : ''}`
                : '—'}
            </p>
          </div>
          <div>
            <p className="text-xs text-zinc-600">Started</p>
            <p className="text-sm text-zinc-200 mt-0.5 font-mono">{relativeTime(run.startedAt)}</p>
          </div>
          <div>
            <p className="text-xs text-zinc-600">Tasks</p>
            <p className="text-sm text-zinc-200 mt-0.5 font-mono">{String(run.tasks.length)}</p>
          </div>
          <div>
            <p className="text-xs text-zinc-600">Total tokens</p>
            <p className="text-sm text-zinc-200 mt-0.5 font-mono">
              {totalTokens > 0 ? fmtTokens(totalTokens) : '—'}
            </p>
          </div>
        </div>
      </div>

      {/* Super admin: cost + token breakdown by model */}
      {isSuperAdmin && (
        <div className="pt-3 border-t border-white/[0.06]">
          <div className="flex items-center gap-2 mb-3">
            <Coins className="h-3.5 w-3.5 text-accent-amber/70" />
            <span className="text-xs font-medium text-zinc-400 uppercase tracking-wider">Cost & Token Breakdown</span>
            <span className="text-[9px] text-zinc-600 bg-white/[0.04] px-1.5 py-0.5 rounded-full">Admin</span>
          </div>

          {/* Cost */}
          <div className="grid grid-cols-2 gap-3 mb-4">
            <div>
              <p className="text-xs text-zinc-600">Total cost</p>
              <p className="text-sm text-accent-emerald mt-0.5 font-mono font-medium">
                {run.actualCostUsd != null ? `$${run.actualCostUsd.toFixed(4)}` : '—'}
              </p>
            </div>
            <div>
              <p className="text-xs text-zinc-600">Key source</p>
              <p className="text-sm text-zinc-200 mt-0.5 font-mono">
                {run.keySource ?? '—'}
              </p>
            </div>
          </div>

          {/* Tokens by model */}
          {run.tasks.length > 0 && (
            <div>
              <p className="text-xs text-zinc-600 mb-2">Tokens by model</p>
              <div className="space-y-1.5">
                {tokensByModel(run.tasks).map(({ model, input, output }) => (
                  <div key={model} className="flex items-center gap-3 p-2 rounded bg-white/[0.02] border border-white/[0.04]">
                    <span className="text-xs font-mono text-zinc-300 flex-1 truncate">{model}</span>
                    <div className="flex items-center gap-3 flex-shrink-0 text-[11px] font-mono">
                      <span className="text-accent-cyan/70">
                        {fmtTokens(input)} in
                      </span>
                      <span className="text-accent-violet/70">
                        {fmtTokens(output)} out
                      </span>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      )}

      {/* Model assignment */}
      <div className="grid grid-cols-3 gap-3 pt-2 border-t border-white/[0.06]">
        {[
          { label: 'Planner', value: run.plannerModel },
          { label: 'Worker', value: run.workerModel },
          { label: 'Reviewer', value: run.reviewerModel },
        ].map(({ label, value }) => (
          <div key={label}>
            <p className="text-xs text-zinc-600">{label}</p>
            <p className="text-sm text-zinc-200 mt-0.5 font-mono truncate" title={value}>{value}</p>
          </div>
        ))}
      </div>

      {/* Goal — the user's original request */}
      {run.userRequest && (
        <div className="pt-3 border-t border-white/[0.06]">
          <p className="text-xs text-zinc-600 mb-1.5">Goal</p>
          <p className="text-sm text-zinc-300 leading-relaxed whitespace-pre-wrap line-clamp-8">
            {run.userRequest}
          </p>
        </div>
      )}
    </div>
  );
}

// ── Plan tab ──────────────────────────────────────────────────────────────────

function PlanTab({ run }: { run: RunDetail }) {
  if (!run.planJson) {
    return <p className="text-sm text-zinc-500">Plan not available yet.</p>;
  }

  let plan: any = null;
  try {
    plan = JSON.parse(run.planJson);
  } catch {
    return (
      <pre className="text-xs font-mono text-zinc-400 whitespace-pre-wrap break-all">
        {run.planJson}
      </pre>
    );
  }

  return (
    <div className="space-y-4">
      {plan.plan_summary && (
        <div className="p-3 rounded-lg bg-white/[0.02] border border-white/[0.06]">
          <p className="text-[10px] uppercase tracking-wider text-zinc-600 mb-1">Plan Summary</p>
          <p className="text-sm text-zinc-200 leading-relaxed">{plan.plan_summary}</p>
        </div>
      )}

      {Array.isArray(plan.tasks) && plan.tasks.length > 0 && (
        <div className="space-y-3">
          <h4 className="text-xs font-medium text-zinc-500 uppercase tracking-wider">
            Tasks ({plan.tasks.length})
          </h4>
          {plan.tasks.map((t: any, i: number) => (
            <PlanTaskCard key={t.task_id ?? i} task={t} index={i} />
          ))}
        </div>
      )}
    </div>
  );
}

// ── Plan task card (expandable) ───────────────────────────────────────────────

function PlanTaskCard({ task: t, index: i }: { task: any; index: number }) {
  const [objExpanded, setObjExpanded] = useState(false);
  const [critExpanded, setCritExpanded] = useState(false);
  const TRUNCATE = 220;

  const objective  = t.objective ?? '';
  const criteria   = t.acceptance_criteria ?? '';
  const objLong    = objective.length > TRUNCATE;
  const critLong   = criteria.length > TRUNCATE;

  const displayObj  = objExpanded  || !objLong  ? objective  : objective.slice(0, TRUNCATE) + '…';
  const displayCrit = critExpanded || !critLong ? criteria  : criteria.slice(0, TRUNCATE) + '…';

  return (
    <div className="rounded-lg bg-white/[0.02] border border-white/[0.06] overflow-hidden">
      {/* ── Header: index · title · complexity badge ── */}
      <div className="flex items-center justify-between gap-2 px-3 py-2.5 border-b border-white/[0.04]">
        <div className="flex items-center gap-2 min-w-0">
          <span className="text-[10px] text-zinc-600 flex-shrink-0 font-mono tabular-nums">
            #{String(i + 1).padStart(2, '0')}
          </span>
          <p className="text-sm font-medium text-zinc-200 leading-snug">{t.title}</p>
        </div>
        <span className={cn(
          'text-[10px] font-medium px-1.5 py-0.5 rounded flex-shrink-0',
          t.complexity === 'HIGH'   ? 'text-accent-rose bg-accent-rose/10' :
          t.complexity === 'MEDIUM' ? 'text-accent-amber bg-accent-amber/10' :
                                      'text-accent-emerald bg-accent-emerald/10'
        )}>
          {t.complexity ?? 'MEDIUM'}
        </span>
      </div>

      {/* ── Body ── */}
      <div className="px-3 py-2.5 space-y-3">

        {/* Objective */}
        {objective && (
          <div>
            <p className="text-[10px] uppercase tracking-wider text-zinc-600 mb-1">Objective</p>
            <p className="text-xs text-zinc-400 leading-relaxed whitespace-pre-wrap">{displayObj}</p>
            {objLong && (
              <button
                onClick={() => setObjExpanded(!objExpanded)}
                className="text-[10px] text-accent-violet hover:text-accent-violet/70 mt-1 transition-colors"
              >
                {objExpanded ? '▲ Show less' : '▼ Show more'}
              </button>
            )}
          </div>
        )}

        {/* Acceptance criteria */}
        {criteria && (
          <div>
            <p className="text-[10px] uppercase tracking-wider text-zinc-600 mb-1">Acceptance Criteria</p>
            <div className="bg-white/[0.02] border border-white/[0.04] rounded px-2 py-1.5">
              <p className="text-xs font-mono text-zinc-500 leading-relaxed whitespace-pre-wrap">{displayCrit}</p>
            </div>
            {critLong && (
              <button
                onClick={() => setCritExpanded(!critExpanded)}
                className="text-[10px] text-accent-violet hover:text-accent-violet/70 mt-1 transition-colors"
              >
                {critExpanded ? '▲ Show less' : '▼ Show more'}
              </button>
            )}
          </div>
        )}

        {/* Footer: model chip + depends_on chips */}
        <div className="flex items-center flex-wrap gap-2 pt-0.5">
          {t.assigned_model && (
            <span className="text-[10px] font-mono text-zinc-600 bg-white/[0.02] px-1.5 py-0.5 rounded border border-white/[0.04]">
              {t.assigned_model}
            </span>
          )}
          {Array.isArray(t.depends_on) && t.depends_on.length > 0 && (
            <div className="flex items-center gap-1 flex-wrap">
              <span className="text-[10px] text-zinc-600">needs:</span>
              {t.depends_on.map((dep: string) => (
                <span
                  key={dep}
                  className="text-[10px] font-mono px-1.5 py-0.5 rounded bg-accent-violet/10 text-accent-violet/70 border border-accent-violet/10"
                >
                  {dep}
                </span>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

// ── Review tab ────────────────────────────────────────────────────────────────

const VERDICT_COLORS: Record<string, string> = {
  APPROVE:  'text-accent-emerald',
  REJECT:   'text-accent-rose',
  PARTIAL:  'text-accent-amber',
};

function ReviewTab({ run }: { run: RunDetail }) {
  if (!run.reviewJson) {
    return <p className="text-sm text-zinc-500">Review not available yet.</p>;
  }

  let review: any = null;
  try {
    review = JSON.parse(run.reviewJson);
  } catch {
    return (
      <pre className="text-xs font-mono text-zinc-400 whitespace-pre-wrap break-all">
        {run.reviewJson}
      </pre>
    );
  }

  const verdict = review.recommendation ?? review.verdict;

  return (
    <div className="space-y-4">
      {/* Verdict + confidence */}
      <div className="flex items-center justify-between p-3 rounded-lg bg-white/[0.02] border border-white/[0.06]">
        <div>
          <p className="text-xs text-zinc-500 mb-1">Verdict</p>
          <p className={cn('text-sm font-semibold', VERDICT_COLORS[verdict] ?? 'text-zinc-300')}>
            {verdict ?? '—'}
          </p>
        </div>
        {review.overall_confidence != null && (
          <div className="text-right">
            <p className="text-xs text-zinc-500 mb-1">Confidence</p>
            <p className="text-sm text-zinc-300">
              {typeof review.overall_confidence === 'number'
                ? `${Math.round(review.overall_confidence * 100)}%`
                : review.overall_confidence}
            </p>
          </div>
        )}
      </div>

      {/* Summary */}
      {review.summary && (
        <div className="p-3 rounded-lg bg-white/[0.02] border border-white/[0.06]">
          <p className="text-xs text-zinc-500 mb-1">Summary</p>
          <p className="text-sm text-zinc-300">{review.summary}</p>
        </div>
      )}

      {/* Issues */}
      {Array.isArray(review.issues) && review.issues.length > 0 && (
        <div className="space-y-2">
          <h4 className="text-xs font-medium text-zinc-500 uppercase tracking-wider">Issues ({review.issues.length})</h4>
          {review.issues.map((issue: any, i: number) => (
            <div key={i} className="p-3 rounded-lg bg-white/[0.02] border border-white/[0.06]">
              <div className="flex items-center gap-2 mb-1">
                <span className={cn(
                  'text-xs px-1.5 py-0.5 rounded',
                  issue.severity === 'HIGH'    ? 'text-accent-rose bg-accent-rose/10' :
                  issue.severity === 'MEDIUM'  ? 'text-accent-amber bg-accent-amber/10' :
                  'text-zinc-400 bg-zinc-400/10'
                )}>
                  {issue.severity ?? 'LOW'}
                </span>
                <span className="text-xs text-zinc-500">{issue.category}</span>
              </div>
              <p className="text-sm text-zinc-300">{issue.description}</p>
              {issue.suggestion && (
                <p className="text-xs text-zinc-500 mt-1 italic">{issue.suggestion}</p>
              )}
            </div>
          ))}
        </div>
      )}

      {/* Post-merge suggestions */}
      {Array.isArray(review.post_merge_suggestions) && review.post_merge_suggestions.length > 0 && (
        <div className="space-y-1">
          <h4 className="text-xs font-medium text-zinc-500 uppercase tracking-wider">Post-merge Suggestions</h4>
          <ul className="space-y-1">
            {review.post_merge_suggestions.map((s: string, i: number) => (
              <li key={i} className="text-sm text-zinc-400 pl-3 border-l border-white/[0.06]">{s}</li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}

// ── File tree helpers ─────────────────────────────────────────────────────────

interface FileTreeNode {
  name: string;
  path: string;
  isFile: boolean;
  children: FileTreeNode[];
}

function buildFileTree(files: string[]): FileTreeNode[] {
  const root: Record<string, any> = {};

  for (const filePath of files) {
    const parts = filePath.split('/');
    let current = root;
    for (let i = 0; i < parts.length; i++) {
      const part = parts[i];
      const isLast = i === parts.length - 1;
      if (!current[part]) {
        current[part] = { _isFile: false, _path: '', _children: {} };
      }
      if (isLast) {
        current[part]._isFile = true;
        current[part]._path = filePath;
      }
      current = current[part]._children;
    }
  }

  function mapToNodes(obj: Record<string, any>, prefix = ''): FileTreeNode[] {
    return Object.entries(obj)
      .sort(([aName, aNode], [bName, bNode]) => {
        // Folders first, then files; alphabetical within each group
        if (!aNode._isFile && bNode._isFile) return -1;
        if (aNode._isFile && !bNode._isFile) return 1;
        return aName.localeCompare(bName);
      })
      .map(([name, node]) => {
        const path = prefix ? `${prefix}/${name}` : name;
        return {
          name,
          path: node._isFile ? node._path : path,
          isFile: !!node._isFile,
          children: mapToNodes(node._children, path),
        };
      });
  }

  return mapToNodes(root);
}

function FileTypeIcon({ name }: { name: string }) {
  const lower = name.toLowerCase();
  const ext = lower.includes('.') ? lower.split('.').pop()! : '';

  const codeExts = new Set(['java', 'kt', 'ts', 'tsx', 'js', 'jsx', 'py', 'go', 'rs', 'cpp', 'c', 'cs', 'rb', 'php', 'swift', 'scala']);
  const textExts = new Set(['md', 'txt', 'yaml', 'yml', 'json', 'xml', 'html', 'css', 'scss', 'sql', 'sh', 'bash', 'properties', 'env', 'conf', 'toml', 'gradle', 'gitignore', 'dockerignore']);

  const colorMap: Record<string, string> = {
    java: 'text-orange-400', kt: 'text-purple-400',
    ts: 'text-blue-400', tsx: 'text-blue-400',
    js: 'text-yellow-400', jsx: 'text-yellow-400',
    py: 'text-green-400', go: 'text-sky-400',
    rs: 'text-orange-500', sql: 'text-blue-500',
    json: 'text-yellow-500', xml: 'text-red-400',
    yml: 'text-purple-400', yaml: 'text-purple-400',
    html: 'text-orange-400', css: 'text-blue-400',
    scss: 'text-pink-400', md: 'text-zinc-400',
    sh: 'text-green-400', bash: 'text-green-400',
    gradle: 'text-green-500', properties: 'text-zinc-400',
  };

  const color = colorMap[ext] ?? 'text-zinc-500';

  if (lower === 'dockerfile' || lower === '.dockerfile') {
    return <span className="text-sky-400 font-mono text-[9px] font-bold leading-none">🐳</span>;
  }
  if (codeExts.has(ext)) {
    return <FileCode2 className={cn('h-3 w-3 flex-shrink-0', color)} />;
  }
  if (textExts.has(ext)) {
    return <FileText className={cn('h-3 w-3 flex-shrink-0', color)} />;
  }
  return <File className="h-3 w-3 flex-shrink-0 text-zinc-500" />;
}

interface TreeNodeViewProps {
  node: FileTreeNode;
  selected: string | null;
  onSelect: (path: string) => void;
  depth?: number;
  /** Increment to force-expand all folders; 0 = initial state */
  expandSignal?: number;
  /** Increment to force-collapse all folders; 0 = initial state */
  collapseSignal?: number;
}

function TreeNodeView({
  node, selected, onSelect,
  depth = 0, expandSignal = 0, collapseSignal = 0,
}: TreeNodeViewProps) {
  const [open, setOpen] = useState(depth < 2);

  // React to expand / collapse all signals
  useEffect(() => { if (expandSignal > 0) setOpen(true); }, [expandSignal]);
  useEffect(() => { if (collapseSignal > 0) setOpen(false); }, [collapseSignal]);

  const indent = depth * 12 + 8;

  if (node.isFile) {
    return (
      <button
        onClick={() => onSelect(node.path)}
        className={cn(
          'w-full text-left flex items-center gap-1.5 py-[3px] pr-2 rounded text-xs font-mono truncate transition-colors',
          selected === node.path
            ? 'bg-accent-violet/15 text-accent-violet'
            : 'text-zinc-400 hover:text-zinc-200 hover:bg-white/[0.04]'
        )}
        style={{ paddingLeft: `${indent}px` }}
        title={node.path}
      >
        <FileTypeIcon name={node.name} />
        <span className="truncate">{node.name}</span>
      </button>
    );
  }

  return (
    <div>
      <button
        onClick={() => setOpen(!open)}
        className="w-full text-left flex items-center gap-1.5 py-[3px] pr-2 rounded text-xs text-zinc-500 hover:text-zinc-300 hover:bg-white/[0.04] transition-colors"
        style={{ paddingLeft: `${indent}px` }}
      >
        <ChevronRight className={cn('h-3 w-3 flex-shrink-0 transition-transform', open && 'rotate-90')} />
        {open
          ? <FolderOpen className="h-3 w-3 flex-shrink-0 text-accent-amber/60" />
          : <Folder className="h-3 w-3 flex-shrink-0 text-accent-amber/60" />
        }
        <span>{node.name}</span>
      </button>
      {open && (
        <div>
          {node.children.map((child) => (
            <TreeNodeView
              key={child.name}
              node={child}
              selected={selected}
              onSelect={onSelect}
              depth={depth + 1}
              expandSignal={expandSignal}
              collapseSignal={collapseSignal}
            />
          ))}
        </div>
      )}
    </div>
  );
}

// ── highlight.js language map ─────────────────────────────────────────────────
const EXT_TO_LANG: Record<string, string> = {
  ts: 'typescript', tsx: 'typescript',
  js: 'javascript', jsx: 'javascript',
  java: 'java', kt: 'kotlin',
  py: 'python', go: 'go', rs: 'rust',
  rb: 'ruby', php: 'php', swift: 'swift',
  cs: 'csharp', cpp: 'cpp', c: 'c', scala: 'scala',
  css: 'css', scss: 'scss',
  html: 'xml', xml: 'xml',
  json: 'json', yaml: 'yaml', yml: 'yaml',
  md: 'markdown', sql: 'sql',
  sh: 'bash', bash: 'bash',
  dockerfile: 'dockerfile',
  gradle: 'gradle', toml: 'ini',
  properties: 'properties',
};

function getLanguage(filePath: string): string {
  const name = filePath.split('/').pop()?.toLowerCase() ?? '';
  if (name === 'dockerfile') return 'dockerfile';
  const ext = name.includes('.') ? name.split('.').pop()! : '';
  return EXT_TO_LANG[ext] ?? 'plaintext';
}

function highlightCode(code: string, language: string): string {
  try {
    if (language === 'plaintext') return escapeHtml(code);
    const result = hljs.highlight(code, { language, ignoreIllegals: true });
    return result.value;
  } catch {
    return escapeHtml(code);
  }
}

function escapeHtml(str: string): string {
  return str
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');
}

// ── IDE file viewer ───────────────────────────────────────────────────────────
function IDEFileViewer({
  filePath, content, loading,
}: { filePath: string | null; content: string; loading: boolean }) {
  const [copied, setCopied] = useState(false);

  const handleCopy = () => {
    navigator.clipboard.writeText(content).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    });
  };

  if (!filePath) {
    return (
      <div className="flex flex-col items-center justify-center h-full gap-3 text-zinc-600">
        <FileCode2 className="h-10 w-10 opacity-30" />
        <span className="text-sm">Select a file to view its contents</span>
      </div>
    );
  }

  const fileName = filePath.split('/').pop()!;
  const language = getLanguage(filePath);
  const highlightedHtml = loading ? '' : highlightCode(content, language);
  const lines = loading ? [] : highlightedHtml.split('\n');
  // Remove trailing empty line that split often adds
  if (lines.length > 0 && lines[lines.length - 1] === '') lines.pop();

  return (
    <div className="flex flex-col h-full overflow-hidden">
      {/* ── Tab bar (VS Code style) ── */}
      <div className="flex items-center border-b border-white/[0.06] bg-[#0d0d14] flex-shrink-0 overflow-x-auto">
        <div className="flex items-center gap-1.5 px-3 py-2 bg-[#13131f] border-r border-white/[0.06] border-t border-t-accent-violet/60 min-w-0">
          <FileTypeIcon name={fileName} />
          <span className="text-xs font-mono text-zinc-300 whitespace-nowrap">{fileName}</span>
        </div>
        {/* breadcrumb path */}
        <span className="text-[10px] font-mono text-zinc-600 px-3 truncate flex-1">
          {filePath.split('/').slice(0, -1).join(' / ')}
        </span>
        {/* copy button */}
        <button
          onClick={handleCopy}
          title="Copy file contents"
          className="flex items-center gap-1 px-3 py-2 text-xs text-zinc-500 hover:text-zinc-300 transition-colors flex-shrink-0"
        >
          {copied
            ? <><Check className="h-3 w-3 text-green-400" /><span className="text-green-400">Copied</span></>
            : <><Copy className="h-3 w-3" /><span>Copy</span></>}
        </button>
        {/* language badge */}
        <span className="px-3 py-2 text-[10px] font-mono text-zinc-600 flex-shrink-0">
          {language}
        </span>
      </div>

      {/* ── Code area ── */}
      <div className="flex-1 overflow-auto bg-[#0d0d14]">
        {loading ? (
          <div className="p-4 text-sm text-zinc-500 animate-pulse">Loading…</div>
        ) : (
          <table className="w-full border-collapse text-xs font-mono leading-[1.6]">
            <tbody>
              {lines.map((lineHtml, i) => (
                <tr key={i} className="group hover:bg-white/[0.025]">
                  {/* line number gutter */}
                  <td
                    className="select-none text-right pr-4 pl-4 text-zinc-600 group-hover:text-zinc-500 w-[1%] whitespace-nowrap align-top pt-0"
                    style={{ userSelect: 'none', minWidth: '2.8rem' }}
                  >
                    {i + 1}
                  </td>
                  {/* code line */}
                  <td
                    className="pl-2 pr-6 text-zinc-200 whitespace-pre align-top"
                    dangerouslySetInnerHTML={{ __html: lineHtml || '&nbsp;' }}
                  />
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}

// ── Code tab ──────────────────────────────────────────────────────────────────

function CodeTab({
  runId,
  files,
  filesLoading,
  repoUrl,
  status,
}: {
  runId: string;
  files: string[];
  filesLoading: boolean;
  repoUrl?: string | null;
  status?: string;
}) {
  const [selected, setSelected] = useState<string | null>(null);
  const [content, setContent] = useState<string>('');
  const [loadingContent, setLoadingContent] = useState(false);
  const [expandSignal, setExpandSignal] = useState(0);
  const [collapseSignal, setCollapseSignal] = useState(0);

  useEffect(() => {
    setSelected(null);
    setContent('');
  }, [runId]);

  const openFile = async (path: string) => {
    if (selected === path) return;
    setSelected(path);
    setLoadingContent(true);
    setContent('');
    try {
      const text = await getFile(runId, path);
      setContent(typeof text === 'string' ? text : JSON.stringify(text, null, 2));
    } catch (e: any) {
      setContent(`// Error loading file: ${e.message}`);
    }
    setLoadingContent(false);
  };

  // Convert GitHub clone URL to web URL for "View on GitHub" link
  const githubWebUrl = repoUrl
    ? repoUrl.replace(/\.git$/, '').replace('https://github.com/', 'https://github.com/')
    : null;

  if (filesLoading) {
    return <div className="p-4 text-sm text-zinc-500">Loading files…</div>;
  }

  if (files.length === 0) {
    const isTerminal = ['COMPLETE', 'PARTIAL', 'FAILED'].includes(status || '');
    return (
      <div className="p-4 space-y-3">
        <p className="text-sm text-zinc-500">
          {isTerminal
            ? 'Local workspace has been cleaned up.'
            : 'No files generated yet. Files appear after the run completes.'}
        </p>
        {isTerminal && githubWebUrl && (
          <a
            href={githubWebUrl}
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex items-center gap-1.5 text-sm text-accent-violet hover:text-accent-violet/80 transition-colors"
          >
            <svg className="h-4 w-4" viewBox="0 0 16 16" fill="currentColor"><path fillRule="evenodd" d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0016 8c0-4.42-3.58-8-8-8z"/></svg>
            View on GitHub
          </a>
        )}
        {isTerminal && !githubWebUrl && (
          <p className="text-xs text-zinc-600">
            Tip: Push to GitHub to preserve code access after workspace cleanup.
          </p>
        )}
      </div>
    );
  }

  const tree = buildFileTree(files);

  return (
    <div className="flex h-full overflow-hidden">
      {/* ── File tree sidebar ── */}
      <div className="w-56 flex-shrink-0 border-r border-white/[0.06] flex flex-col overflow-hidden">
        {/* Toolbar: Expand All / Collapse All / GitHub link */}
        <div className="flex items-center gap-1 px-2 py-1.5 border-b border-white/[0.06] flex-shrink-0">
          <span className="text-[10px] text-zinc-600 uppercase tracking-widest flex-1 pl-1">Explorer</span>
          {githubWebUrl && (
            <a
              href={githubWebUrl}
              target="_blank"
              rel="noopener noreferrer"
              title="View on GitHub"
              className="p-1 rounded text-zinc-600 hover:text-zinc-300 hover:bg-white/[0.06] transition-colors"
            >
              <svg className="h-3 w-3" viewBox="0 0 16 16" fill="currentColor"><path fillRule="evenodd" d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0016 8c0-4.42-3.58-8-8-8z"/></svg>
            </a>
          )}
          <button
            title="Expand all folders"
            onClick={() => setExpandSignal(s => s + 1)}
            className="p-1 rounded text-zinc-600 hover:text-zinc-300 hover:bg-white/[0.06] transition-colors"
          >
            <ChevronsUpDown className="h-3 w-3" />
          </button>
          <button
            title="Collapse all folders"
            onClick={() => setCollapseSignal(s => s + 1)}
            className="p-1 rounded text-zinc-600 hover:text-zinc-300 hover:bg-white/[0.06] transition-colors"
          >
            <ChevronsDownUp className="h-3 w-3" />
          </button>
        </div>

        {/* Tree nodes */}
        <div className="overflow-y-auto flex-1 py-1">
          {tree.map((node) => (
            <TreeNodeView
              key={node.name}
              node={node}
              selected={selected}
              onSelect={openFile}
              expandSignal={expandSignal}
              collapseSignal={collapseSignal}
            />
          ))}
        </div>
      </div>

      {/* ── IDE viewer pane ── */}
      <div className="flex-1 overflow-hidden">
        <IDEFileViewer
          filePath={selected}
          content={content}
          loading={loadingContent}
        />
      </div>
    </div>
  );
}

