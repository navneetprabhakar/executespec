import { useCallback, useEffect, useRef, useState } from 'react';
import * as Tabs from '@radix-ui/react-tabs';
import { useUiStore } from '@/stores/useUiStore';
import { useRunStore } from '@/stores/useRunStore';
import {
  getFiles, getFile,
  getOpenApiSpec,
  getPreviewStatus, startPreview, stopPreview,
  LocalPreviewStatus,
} from '@/lib/api';
import type { RunDetail } from '@/lib/api';
import GlowDot from '@/components/shared/GlowDot';
import TaskCard from './TaskCard';
import LiveLog from './LiveLog';
import RunConversation from './RunConversation';
import TaskGraph from './TaskGraph';
import { PIPELINE_STEPS } from '@/lib/constants';
import { fmtTokens, relativeTime, cn } from '@/lib/utils';
import {
  ChevronDown, ChevronRight,
  Folder, FolderOpen,
  FileCode2, FileText, File,
  Play, Square, ExternalLink, RefreshCw,
  MessageSquare,
} from 'lucide-react';

const BASE_TABS = [
  { value: 'overview',  label: 'Overview' },
  { value: 'tasks',     label: 'Tasks' },
  { value: 'log',       label: 'Live Log' },
  { value: 'plan',      label: 'Plan' },
  { value: 'review',    label: 'Review' },
  { value: 'code',      label: 'Code' },
  { value: 'preview',   label: 'Preview' },
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

  /** The Chat tab is only shown for terminal (COMPLETE / PARTIAL) runs. */
  const showChatTab = activeRun.status === 'COMPLETE' || activeRun.status === 'PARTIAL';
  const TABS = [
    ...BASE_TABS,
    { value: 'graph', label: 'Graph' },
    ...(showChatTab ? [{ value: 'chat', label: 'Chat' }] : []),
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
          <CodeTab runId={activeRun.runId} files={files} filesLoading={filesLoading} />
        </Tabs.Content>

        <Tabs.Content value="preview" className="h-full overflow-hidden">
          <PreviewTab runId={activeRun.runId} files={files} filesLoading={filesLoading} />
        </Tabs.Content>

        <Tabs.Content value="graph" className="h-full overflow-auto p-4">
          <TaskGraph runId={activeRun.runId} />
        </Tabs.Content>

        {showChatTab && (
          <Tabs.Content value="chat" className="h-full overflow-hidden">
            <RunConversation runId={activeRun.runId} />
          </Tabs.Content>
        )}
      </div>
    </Tabs.Root>
  );
}

// ── Overview tab ─────────────────────────────────────────────────────────────

function OverviewTab({ run }: { run: RunDetail }) {
  // STUCK occurs during the WORKING phase — map it there for pipeline step display
  const displayStatus = run.status === 'STUCK' ? 'WORKING' : run.status;
  const stepIndex = PIPELINE_STEPS.indexOf(displayStatus);
  const totalTokens = (run.totalInputTokens || 0) + (run.totalOutputTokens || 0);

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

      {/* Metadata */}
      <div className="grid grid-cols-2 gap-3 pt-2 border-t border-white/[0.06]">
        {[
          { label: 'Planner', value: run.plannerModel },
          { label: 'Worker', value: run.workerModel },
          { label: 'Reviewer', value: run.reviewerModel },
          { label: 'Tokens', value: totalTokens > 0 ? fmtTokens(totalTokens) : '—' },
          { label: 'Started', value: relativeTime(run.startedAt) },
          { label: 'Tasks', value: String(run.tasks.length) },
        ].map(({ label, value }) => (
          <div key={label}>
            <p className="text-xs text-zinc-600">{label}</p>
            <p className="text-sm text-zinc-200 mt-0.5 font-mono">{value}</p>
          </div>
        ))}
      </div>
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
}

function TreeNodeView({ node, selected, onSelect, depth = 0 }: TreeNodeViewProps) {
  const [open, setOpen] = useState(depth < 2); // auto-expand first 2 levels

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
            />
          ))}
        </div>
      )}
    </div>
  );
}

// ── Code tab ──────────────────────────────────────────────────────────────────

function CodeTab({
  runId,
  files,
  filesLoading,
}: {
  runId: string;
  files: string[];
  filesLoading: boolean;
}) {
  const [selected, setSelected] = useState<string | null>(null);
  const [content, setContent] = useState<string>('');
  const [loadingContent, setLoadingContent] = useState(false);

  // Reset selection when run changes
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

  if (filesLoading) {
    return <div className="p-4 text-sm text-zinc-500">Loading files…</div>;
  }

  if (files.length === 0) {
    return (
      <div className="p-4 text-sm text-zinc-500">
        No files generated yet. Files appear after the run completes.
      </div>
    );
  }

  const tree = buildFileTree(files);

  return (
    <div className="flex h-full">
      {/* File tree sidebar */}
      <div className="w-56 flex-shrink-0 border-r border-white/[0.06] overflow-y-auto py-2">
        {tree.map((node) => (
          <TreeNodeView
            key={node.name}
            node={node}
            selected={selected}
            onSelect={openFile}
          />
        ))}
      </div>

      {/* Content pane */}
      <div className="flex-1 overflow-auto">
        {selected ? (
          <>
            <div className="px-4 py-2 border-b border-white/[0.06] flex items-center gap-2">
              <FileTypeIcon name={selected.split('/').pop()!} />
              <span className="text-xs font-mono text-zinc-400 truncate">{selected}</span>
            </div>
            {loadingContent ? (
              <div className="p-4 text-sm text-zinc-500">Loading…</div>
            ) : (
              <pre className="p-4 text-xs font-mono text-zinc-300 whitespace-pre-wrap break-all leading-relaxed">
                {content}
              </pre>
            )}
          </>
        ) : (
          <div className="p-4 text-sm text-zinc-500">Select a file to view its contents.</div>
        )}
      </div>
    </div>
  );
}

// ── Preview tab ───────────────────────────────────────────────────────────────

type ProjectType = 'FULLSTACK' | 'SPRING_BOOT' | 'STATIC' | 'PYTHON' | 'GO' | 'RUBY' | 'PHP';

function PreviewTab({
  runId,
  files,
  filesLoading,
}: {
  runId: string;
  files: string[];
  filesLoading: boolean;
}) {
  if (filesLoading) {
    return <div className="p-4 text-sm text-zinc-500">Detecting project type…</div>;
  }

  if (files.length === 0) {
    return (
      <div className="p-4 text-sm text-zinc-500">
        Files not available yet. Preview is available after the run completes.
      </div>
    );
  }

  // Use .some() so nested paths (e.g. backend/pom.xml) are detected consistently.
  const hasJava   = files.some(f => f === 'pom.xml'          || f.endsWith('/pom.xml'));
  const hasNode   = files.some(f => f === 'package.json'     || f.endsWith('/package.json'));
  const hasPython = files.some(f =>
    f === 'requirements.txt' || f.endsWith('/requirements.txt') ||
    f === 'setup.py'         || f === 'pyproject.toml' || f === 'Pipfile'
  );
  const hasGo   = files.some(f => f === 'go.mod'        || f.endsWith('/go.mod'));
  const hasRuby = files.some(f => f === 'Gemfile'       || f.endsWith('/Gemfile'));
  const hasPhp  = files.some(f => f === 'composer.json' || f.endsWith('/composer.json'));

  // Backend-only Java → show OpenAPI viewer instead of Docker preview.
  if (hasJava && !hasNode) {
    return <OpenApiViewer runId={runId} />;
  }

  let projectType: ProjectType | null = null;
  if (hasJava && hasNode) projectType = 'FULLSTACK';
  else if (hasJava)       projectType = 'SPRING_BOOT';
  else if (hasNode)       projectType = 'STATIC';
  else if (hasPython)     projectType = 'PYTHON';
  else if (hasGo)         projectType = 'GO';
  else if (hasRuby)       projectType = 'RUBY';
  else if (hasPhp)        projectType = 'PHP';

  if (projectType) {
    return <DockerPreview runId={runId} projectType={projectType} />;
  }

  // Files exist but none are a recognisable project entrypoint.
  // Most common cause: workers were blocked/failed before writing any source files —
  // the workspace only contains init artefacts like CODEBASE_CONTEXT.md.
  const nonInitFiles = files.filter(f => f !== 'CODEBASE_CONTEXT.md' && !f.startsWith('.agent-context/'));
  return (
    <div className="p-4 space-y-1 text-sm text-zinc-500">
      <p className="font-medium text-zinc-400">No deployable project detected</p>
      {nonInitFiles.length === 0 ? (
        <p>Workers did not write any source files — the run may have failed before starting. Check the Log tab for errors.</p>
      ) : (
        <p>
          {nonInitFiles.length} file{nonInitFiles.length !== 1 ? 's' : ''} generated but no recognised
          project entrypoint found (<code className="font-mono">pom.xml</code>,{' '}
          <code className="font-mono">package.json</code>,{' '}
          <code className="font-mono">requirements.txt</code>,{' '}
          <code className="font-mono">go.mod</code>,{' '}
          <code className="font-mono">Gemfile</code>, or{' '}
          <code className="font-mono">composer.json</code>).
        </p>
      )}
    </div>
  );
}

// ── OpenAPI viewer (backend-only projects) ────────────────────────────────────

interface Endpoint {
  method: string;
  path: string;
  summary: string;
}

function parseOpenApiEndpoints(yaml: string): Endpoint[] {
  const endpoints: Endpoint[] = [];
  const lines = yaml.split('\n');
  const HTTP_METHODS = new Set(['get', 'post', 'put', 'delete', 'patch', 'head', 'options']);

  let inPaths = false;
  let currentPath = '';
  let currentMethod = '';

  for (const line of lines) {
    if (line === 'paths:') { inPaths = true; continue; }
    // Leave paths block when a new top-level key starts
    if (inPaths && /^\S/.test(line) && !line.startsWith(' ')) { inPaths = false; continue; }
    if (!inPaths) continue;

    const pathMatch = line.match(/^  (\/[^:]+):\s*$/);
    if (pathMatch) { currentPath = pathMatch[1]; currentMethod = ''; continue; }

    const methodMatch = line.match(/^    ([a-z]+):\s*$/);
    if (methodMatch && HTTP_METHODS.has(methodMatch[1]) && currentPath) {
      currentMethod = methodMatch[1].toUpperCase();
      continue;
    }

    const summaryMatch = line.match(/^      summary:\s*(.+)/);
    if (summaryMatch && currentPath && currentMethod) {
      endpoints.push({ method: currentMethod, path: currentPath, summary: summaryMatch[1].trim() });
      currentMethod = '';
    }
  }

  return endpoints;
}

const METHOD_COLORS: Record<string, string> = {
  GET:    'bg-accent-emerald/15 text-accent-emerald',
  POST:   'bg-accent-cyan/15 text-accent-cyan',
  PUT:    'bg-accent-amber/15 text-accent-amber',
  PATCH:  'bg-accent-amber/15 text-accent-amber',
  DELETE: 'bg-accent-rose/15 text-accent-rose',
};

function OpenApiViewer({ runId }: { runId: string }) {
  const [yaml, setYaml] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [notFound, setNotFound] = useState(false);
  const [rawExpanded, setRawExpanded] = useState(false);

  useEffect(() => {
    setLoading(true);
    setYaml(null);
    setNotFound(false);
    getOpenApiSpec(runId)
      .then(setYaml)
      .catch((e: Error) => {
        if (e.message.startsWith('404')) setNotFound(true);
      })
      .finally(() => setLoading(false));
  }, [runId]);

  if (loading) {
    return <div className="p-4 text-sm text-zinc-500">Loading API spec…</div>;
  }

  if (notFound || !yaml) {
    return (
      <div className="p-4 space-y-2">
        <p className="text-sm font-medium text-zinc-300">No OpenAPI spec found</p>
        <p className="text-xs text-zinc-500 leading-relaxed">
          The API spec is auto-generated by the API Test Suite agent after the run completes
          (written to <code className="font-mono">api-docs/openapi.yaml</code> in the workspace).
        </p>
        <p className="text-xs text-zinc-600">
          If the run is complete and you still see this message, the spec may not have been generated.
        </p>
      </div>
    );
  }

  const endpoints = parseOpenApiEndpoints(yaml);

  // Try to extract title + version from yaml
  const titleMatch = yaml.match(/^  title:\s*(.+)/m);
  const versionMatch = yaml.match(/^  version:\s*(.+)/m);
  const title = titleMatch ? titleMatch[1].trim() : 'API Documentation';
  const version = versionMatch ? versionMatch[1].trim() : '';

  return (
    <div className="h-full overflow-y-auto p-4 space-y-4">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h3 className="text-sm font-semibold text-zinc-200">{title}</h3>
          {version && <p className="text-xs text-zinc-500 mt-0.5">v{version}</p>}
        </div>
        <span className="text-xs text-zinc-600">{endpoints.length} endpoint{endpoints.length !== 1 ? 's' : ''}</span>
      </div>

      {/* Endpoint list */}
      {endpoints.length > 0 ? (
        <div className="space-y-1.5">
          {endpoints.map((ep, i) => (
            <div
              key={i}
              className="flex items-center gap-3 px-3 py-2.5 rounded-lg bg-white/[0.02] border border-white/[0.06]"
            >
              <span className={cn(
                'text-[10px] font-bold px-1.5 py-0.5 rounded font-mono flex-shrink-0 w-14 text-center',
                METHOD_COLORS[ep.method] ?? 'bg-zinc-500/15 text-zinc-400'
              )}>
                {ep.method}
              </span>
              <code className="text-xs font-mono text-zinc-300 flex-1 truncate">{ep.path}</code>
              {ep.summary && (
                <span className="text-xs text-zinc-500 flex-shrink-0 max-w-[160px] truncate">
                  {ep.summary}
                </span>
              )}
            </div>
          ))}
        </div>
      ) : (
        <p className="text-xs text-zinc-500">No endpoints found in spec. Check the raw YAML below.</p>
      )}

      {/* Raw YAML collapsible */}
      <div className="border border-white/[0.06] rounded-lg overflow-hidden">
        <button
          onClick={() => setRawExpanded(!rawExpanded)}
          className="w-full flex items-center justify-between px-3 py-2 text-xs text-zinc-500 hover:text-zinc-300 hover:bg-white/[0.02] transition-colors"
        >
          <span>Raw OpenAPI YAML</span>
          <ChevronDown className={cn('h-3 w-3 transition-transform', rawExpanded && 'rotate-180')} />
        </button>
        {rawExpanded && (
          <pre className="p-3 text-[11px] font-mono text-zinc-400 whitespace-pre-wrap break-all leading-relaxed border-t border-white/[0.06] max-h-96 overflow-y-auto">
            {yaml}
          </pre>
        )}
      </div>
    </div>
  );
}

// ── Docker preview (full-stack / frontend projects) ───────────────────────────

const PROJECT_LABELS: Record<ProjectType, string> = {
  FULLSTACK:   'Full-stack (Spring Boot + frontend)',
  SPRING_BOOT: 'Spring Boot',
  STATIC:      'Frontend (static)',
  PYTHON:      'Python',
  GO:          'Go',
  RUBY:        'Ruby',
  PHP:         'PHP',
};

function DockerPreview({
  runId,
  projectType,
}: {
  runId: string;
  projectType: ProjectType;
}) {
  const [status, setStatus] = useState<LocalPreviewStatus | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const pollRef = useRef<ReturnType<typeof setInterval>>();

  const projectLabel = PROJECT_LABELS[projectType];

  const fetchStatus = useCallback(async () => {
    try {
      const s = await getPreviewStatus(runId);
      setStatus(s);
      // Stop polling once not building
      if (s.status !== 'BUILDING' && pollRef.current) {
        clearInterval(pollRef.current);
        pollRef.current = undefined;
      }
    } catch {}
  }, [runId]);

  useEffect(() => {
    fetchStatus();
    return () => {
      if (pollRef.current) clearInterval(pollRef.current);
    };
  }, [runId, fetchStatus]);

  const handleStart = async () => {
    setLoading(true);
    setError(null);
    try {
      const s = await startPreview(runId);
      setStatus(s);
      // Start polling while building
      if (pollRef.current) clearInterval(pollRef.current);
      pollRef.current = setInterval(fetchStatus, 4000);
    } catch (e: any) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  };

  const handleStop = async () => {
    setLoading(true);
    try {
      await stopPreview(runId);
      if (pollRef.current) clearInterval(pollRef.current);
      await fetchStatus();
    } catch (e: any) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  };

  const isIdle     = !status || status.status === 'IDLE' || status.status === 'STOPPED';
  const isBuilding = status?.status === 'BUILDING';
  const isRunning  = status?.status === 'RUNNING';
  const isFailed   = status?.status === 'FAILED';

  return (
    <div className="h-full flex flex-col">
      {/* Control bar */}
      <div className="flex items-center gap-3 px-4 py-3 border-b border-white/[0.06] flex-shrink-0">
        <div className="flex-1 min-w-0">
          <p className="text-xs text-zinc-500">{projectLabel}</p>
          {isRunning && status?.url && (
            <a
              href={status.url}
              target="_blank"
              rel="noopener noreferrer"
              className="flex items-center gap-1 text-xs text-accent-emerald hover:underline mt-0.5"
            >
              <ExternalLink className="h-3 w-3" />
              {status.url}
            </a>
          )}
          {isBuilding && (
            <p className="text-xs text-accent-amber mt-0.5 animate-pulse">
              Building Docker image… this may take a few minutes.
            </p>
          )}
          {isFailed && (
            <p className="text-xs text-accent-rose mt-0.5 truncate">
              {status?.errorMessage ?? 'Build failed'}
            </p>
          )}
        </div>

        <div className="flex items-center gap-2 flex-shrink-0">
          {isBuilding && (
            <span className="h-4 w-4 rounded-full border-2 border-accent-amber/30 border-t-accent-amber animate-spin" />
          )}
          {!isBuilding && (
            <button
              onClick={() => fetchStatus()}
              className="p-1.5 rounded text-zinc-500 hover:text-zinc-300 hover:bg-white/[0.04] transition-colors"
              title="Refresh status"
            >
              <RefreshCw className="h-3.5 w-3.5" />
            </button>
          )}
          {(isIdle || isFailed) && (
            <button
              onClick={handleStart}
              disabled={loading}
              className={cn(
                'flex items-center gap-1.5 px-3 py-1.5 rounded text-xs font-medium transition-all',
                'bg-accent-emerald/15 text-accent-emerald hover:bg-accent-emerald/25 disabled:opacity-40'
              )}
            >
              {loading
                ? <span className="h-3 w-3 rounded-full border-2 border-accent-emerald/30 border-t-accent-emerald animate-spin" />
                : <Play className="h-3 w-3" />}
              {isFailed ? 'Retry' : 'Start Preview'}
            </button>
          )}
          {isRunning && (
            <button
              onClick={handleStop}
              disabled={loading}
              className={cn(
                'flex items-center gap-1.5 px-3 py-1.5 rounded text-xs font-medium transition-all',
                'bg-accent-rose/15 text-accent-rose hover:bg-accent-rose/25 disabled:opacity-40'
              )}
            >
              <Square className="h-3 w-3" />
              Stop
            </button>
          )}
        </div>
      </div>

      {/* Error detail */}
      {error && (
        <div className="mx-4 mt-3 p-3 rounded-lg bg-accent-rose/10 border border-accent-rose/20 text-xs text-accent-rose">
          {error}
        </div>
      )}

      {/* Preview iframe */}
      {isRunning && status?.url ? (
        <div className="flex-1 overflow-hidden">
          <iframe
            src={status.url}
            className="w-full h-full border-0"
            title="App preview"
            sandbox="allow-scripts allow-same-origin allow-forms allow-popups"
          />
        </div>
      ) : (
        <div className="flex-1 flex items-center justify-center">
          <div className="text-center space-y-2 px-8">
            {isIdle && (
              <>
                <p className="text-sm text-zinc-400">Preview not started</p>
                <p className="text-xs text-zinc-600">
                  Click <strong className="text-zinc-400">Start Preview</strong> to build and run the project in Docker.
                  The container is started locally and shown in an iframe.
                </p>
                {(projectType === 'SPRING_BOOT' || projectType === 'FULLSTACK') && (
                  <p className="text-xs text-zinc-700">
                    Spring Boot builds may take 3–10 minutes on the first run (Maven downloads dependencies inside Docker).
                  </p>
                )}
                {projectType === 'PYTHON' && (
                  <p className="text-xs text-zinc-700">
                    Python builds typically take 1–3 minutes (pip installs dependencies inside Docker).
                    Make sure your app binds to <code className="font-mono">0.0.0.0:8080</code>.
                  </p>
                )}
                {projectType === 'GO' && (
                  <p className="text-xs text-zinc-700">
                    Go builds typically complete in 1–2 minutes (modules downloaded inside Docker).
                    Make sure your app binds to <code className="font-mono">0.0.0.0:8080</code>.
                  </p>
                )}
                {projectType === 'RUBY' && (
                  <p className="text-xs text-zinc-700">
                    Ruby builds typically take 2–5 minutes (bundler installs gems inside Docker).
                    Make sure your app binds to <code className="font-mono">0.0.0.0:8080</code>.
                  </p>
                )}
                {projectType === 'PHP' && (
                  <p className="text-xs text-zinc-700">
                    PHP builds typically take 1–3 minutes (composer installs packages inside Docker).
                    Make sure your app binds to <code className="font-mono">0.0.0.0:8080</code>.
                  </p>
                )}
              </>
            )}
            {isBuilding && (
              <p className="text-sm text-zinc-400">Building…</p>
            )}
            {isFailed && (
              <div className="space-y-1">
                <p className="text-sm text-accent-rose">Build failed</p>
                <p className="text-xs text-zinc-500 max-w-sm break-all">{status?.errorMessage}</p>
              </div>
            )}
            {status?.status === 'STOPPED' && (
              <p className="text-sm text-zinc-500">Container stopped.</p>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
