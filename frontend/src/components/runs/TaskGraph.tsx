import { useEffect, useState, useRef, useCallback } from 'react';
import { getTaskGraph, TaskGraphNode } from '@/lib/api';
import { useIsMobile } from '@/hooks/useIsMobile';

interface Props {
  runId?: string;
  nodes?: TaskGraphNode[];
}

// ── Status colours — Geist design system ────────────────────────────────────
const STATUS_COLOR: Record<string, { bg: string; border: string; text: string; dot: string }> = {
  COMPLETE:  { bg: '#0a1a1a', border: '#0070F3', text: '#a1a1a1', dot: '#0070F3' },
  WORKING:   { bg: '#1a1a1a', border: '#f5a623', text: '#a1a1a1', dot: '#f5a623' },
  PENDING:   { bg: '#111111', border: '#333333', text: '#666666', dot: '#666666' },
  LOCKED:    { bg: '#111111', border: '#252525', text: '#666666', dot: '#555555' },
  PARTIAL:   { bg: '#1a0a08', border: '#e08b1a', text: '#a1a1a1', dot: '#e08b1a' },
  BLOCKED:   { bg: '#1a0a10', border: '#d44690', text: '#a1a1a1', dot: '#d44690' },
  FAILED:    { bg: '#1a0a0a', border: '#e5484d', text: '#a1a1a1', dot: '#e5484d' },
};

const DEFAULT_COLOR = { bg: '#111111', border: '#333333', text: '#666666', dot: '#555555' };

function statusColor(s: string) {
  const key = s?.toUpperCase();
  return STATUS_COLOR[key] ?? DEFAULT_COLOR;
}

// ── Layout constants ─────────────────────────────────────────────────────────
const NODE_W = 200;
const NODE_H = 72;
const COL_GAP = 80;
const ROW_GAP = 20;

// Subtask group constants
const SUBTASK_NODE_W = 164;
const SUBTASK_NODE_H = 36;
const SUBTASK_ROW_GAP = 6;
const GROUP_HEADER_H = 52;
const GROUP_PAD_X = 12;
const GROUP_PAD_BOTTOM = 10;
const GROUP_COLLAPSED_H = 64;

// ── Helpers ──────────────────────────────────────────────────────────────────

interface SubtaskGroup {
  parent: TaskGraphNode;
  subtasks: TaskGraphNode[];
}

function groupStatus(subtasks: TaskGraphNode[]): string {
  const statuses = subtasks.map(s => s.status?.toUpperCase());
  if (statuses.every(s => s === 'COMPLETE')) return 'COMPLETE';
  if (statuses.some(s => s === 'WORKING')) return 'WORKING';
  if (statuses.some(s => s === 'FAILED')) return 'FAILED';
  if (statuses.some(s => s === 'PARTIAL')) return 'PARTIAL';
  if (statuses.some(s => s === 'COMPLETE')) return 'PARTIAL';
  return 'PENDING';
}

/** Extract filename from subtask title like "Setup API [2/5: UserController.java]" */
function extractFileName(title: string): string {
  const match = title.match(/\[\d+\/\d+:\s*(.+?)\]$/);
  if (match) return match[1];
  // Fallback: last path segment
  const parts = title.split('/');
  const last = parts[parts.length - 1];
  return last.length > 24 ? last.slice(0, 23) + '\u2026' : last;
}

function expandedGroupHeight(subtaskCount: number): number {
  return GROUP_HEADER_H + subtaskCount * (SUBTASK_NODE_H + SUBTASK_ROW_GAP) + GROUP_PAD_BOTTOM;
}

// ── Pure renderer ────────────────────────────────────────────────────────────
export default function TaskGraph({ runId, nodes: externalNodes }: Props) {
  const isMobile = useIsMobile();
  const [internalNodes, setInternalNodes] = useState<TaskGraphNode[]>([]);
  const [loading, setLoading] = useState(!externalNodes);
  const [error, setError] = useState<string | null>(null);
  const svgRef = useRef<SVGSVGElement>(null);
  const [tooltip, setTooltip] = useState<{ x: number; y: number; node: TaskGraphNode; isGroup?: boolean; group?: SubtaskGroup } | null>(null);
  const [expandedGroups, setExpandedGroups] = useState<Set<string>>(new Set());

  const nodes = externalNodes ?? internalNodes;

  const toggleGroup = useCallback((parentId: string) => {
    setExpandedGroups(prev => {
      const next = new Set(prev);
      next.has(parentId) ? next.delete(parentId) : next.add(parentId);
      return next;
    });
  }, []);

  const load = useCallback(async () => {
    if (externalNodes || !runId) return;
    try {
      const data = await getTaskGraph(runId);
      setInternalNodes(data);
    } catch (e: any) {
      setError(e.message ?? 'Failed to load graph');
    } finally {
      setLoading(false);
    }
  }, [runId, externalNodes]);

  useEffect(() => { load(); }, [load]);

  // Auto-refresh while any task is WORKING
  useEffect(() => {
    if (externalNodes) return;
    const working = nodes.some(n => n.status === 'WORKING' || n.status === 'PENDING');
    if (!working) return;
    const id = setInterval(() => { if (!document.hidden) load(); }, 8000);
    return () => clearInterval(id);
  }, [nodes, load, externalNodes]);

  if (loading) return (
    <div className="flex items-center justify-center h-48 text-muted text-sm">
      Loading execution graph\u2026
    </div>
  );
  if (error) return (
    <div className="flex items-center justify-center h-48 text-accent-red text-sm">{error}</div>
  );
  if (nodes.length === 0) return (
    <div className="flex items-center justify-center h-48 text-muted text-sm">
      No tasks found.
    </div>
  );

  // ── Group subtasks under parents ──────────────────────────────────────────
  const subtaskGroupMap = new Map<string, SubtaskGroup>();
  const subtaskIds = new Set<string>();

  // Identify subtasks via parentTaskId field
  for (const n of nodes) {
    if (n.parentTaskId) {
      subtaskIds.add(n.taskId);
      const existing = subtaskGroupMap.get(n.parentTaskId);
      if (existing) {
        existing.subtasks.push(n);
      } else {
        const parent = nodes.find(p => p.taskId === n.parentTaskId);
        if (parent) {
          subtaskGroupMap.set(n.parentTaskId, { parent, subtasks: [n] });
        }
      }
    }
  }

  // Sort subtasks by index
  for (const group of subtaskGroupMap.values()) {
    group.subtasks.sort((a, b) => (a.subtaskIndex ?? 0) - (b.subtaskIndex ?? 0));
  }

  // IDs of parent nodes that have subtask groups
  const groupParentIds = new Set(subtaskGroupMap.keys());

  // Nodes that participate in layout (standalone + group parents, NOT subtasks)
  const layoutNodes = nodes.filter(n => !subtaskIds.has(n.taskId));

  // ── Mobile: vertical list view ──────────────────────────────────────────
  if (isMobile) {
    return (
      <div className="w-full rounded-sm border border-[var(--border-default)] bg-[#0a0a0f] p-3 space-y-2">
        {/* Legend */}
        <div className="flex flex-wrap gap-2 px-1 pb-2 text-xs border-b border-[var(--border-default)]">
          {Object.entries(STATUS_COLOR).map(([s, c]) => (
            <span key={s} className="flex items-center gap-1">
              <span className="w-2 h-2 rounded-full flex-shrink-0" style={{ background: c.dot }} />
              <span style={{ color: c.text }}>{s}</span>
            </span>
          ))}
        </div>

        {layoutNodes.map(n => {
          const group = subtaskGroupMap.get(n.taskId);
          const c = group ? statusColor(groupStatus(group.subtasks)) : statusColor(n.status);
          const cb = n.complexity?.toUpperCase();
          const isExpanded = expandedGroups.has(n.taskId);

          return (
            <div key={n.taskId}>
              {/* Task card */}
              <div
                className="flex items-center gap-2.5 px-3 py-2.5 rounded-sm border"
                style={{ borderColor: c.border, background: c.bg }}
                onClick={group ? () => toggleGroup(n.taskId) : undefined}
              >
                {/* Status dot */}
                <span
                  className="w-2.5 h-2.5 rounded-full flex-shrink-0"
                  style={{ background: c.dot }}
                />
                {/* Title */}
                <span className="flex-1 text-sm truncate" style={{ color: c.text }}>
                  {n.title}
                </span>
                {/* Complexity badge */}
                {cb && (
                  <span
                    className="text-[10px] font-mono font-medium px-1.5 py-0.5 rounded-sm flex-shrink-0"
                    style={{
                      background: cb === 'HIGH' ? 'rgba(239,68,68,0.15)' : cb === 'MEDIUM' ? 'rgba(234,179,8,0.15)' : 'rgba(99,102,241,0.15)',
                      color: cb === 'HIGH' ? '#fca5a5' : cb === 'MEDIUM' ? '#fde047' : '#a5b4fc',
                    }}
                  >
                    {cb}
                  </span>
                )}
                {/* Group chevron */}
                {group && (
                  <span className="text-xs flex-shrink-0" style={{ color: '#666' }}>
                    {isExpanded ? '\u25B4' : '\u25BE'}
                  </span>
                )}
                {/* Group progress */}
                {group && (
                  <span className="text-[10px] font-mono flex-shrink-0" style={{ color: c.text }}>
                    {group.subtasks.filter(s => s.status === 'COMPLETE').length}/{group.subtasks.length}
                  </span>
                )}
              </div>

              {/* Expanded subtasks */}
              {group && isExpanded && (
                <div className="ml-5 mt-1 space-y-1">
                  {group.subtasks.map(st => {
                    const stc = statusColor(st.status);
                    return (
                      <div
                        key={st.taskId}
                        className="flex items-center gap-2 px-2.5 py-1.5 rounded-sm border"
                        style={{ borderColor: stc.border, background: stc.bg }}
                      >
                        <span
                          className="w-2 h-2 rounded-full flex-shrink-0"
                          style={{ background: stc.dot }}
                        />
                        <span className="text-xs font-mono flex-shrink-0" style={{ color: stc.dot }}>
                          {st.subtaskIndex ?? '?'}/{st.subtaskTotal ?? '?'}
                        </span>
                        <span className="text-xs font-mono truncate" style={{ color: stc.text }}>
                          {extractFileName(st.title)}
                        </span>
                      </div>
                    );
                  })}
                </div>
              )}
            </div>
          );
        })}
      </div>
    );
  }

  // ── Recompute levels via Kahn's algorithm (layout nodes only) ─────────────
  const layoutNodeIds = new Set(layoutNodes.map(n => n.taskId));
  const relevantDeps: Record<string, string[]> = {};
  const inDegree: Record<string, number> = {};
  layoutNodes.forEach(n => {
    const deps = (n.dependsOn ?? []).filter(d => layoutNodeIds.has(d));
    relevantDeps[n.taskId] = deps;
    inDegree[n.taskId] = deps.length;
  });

  const localLevel: Record<string, number> = {};
  const queue: string[] = [];
  layoutNodes.forEach(n => {
    if (inDegree[n.taskId] === 0) {
      queue.push(n.taskId);
      localLevel[n.taskId] = 0;
    }
  });
  while (queue.length > 0) {
    const cur = queue.shift()!;
    layoutNodes.forEach(n => {
      if (relevantDeps[n.taskId]?.includes(cur)) {
        inDegree[n.taskId]--;
        localLevel[n.taskId] = Math.max(localLevel[n.taskId] ?? 0, localLevel[cur] + 1);
        if (inDegree[n.taskId] === 0) queue.push(n.taskId);
      }
    });
  }
  layoutNodes.forEach(n => { if (localLevel[n.taskId] === undefined) localLevel[n.taskId] = 0; });

  // ── Compute layout with variable heights ──────────────────────────────────
  const maxLevel = Math.max(0, ...layoutNodes.map(n => localLevel[n.taskId]));
  const byLevel: Record<number, TaskGraphNode[]> = {};
  for (let i = 0; i <= maxLevel; i++) byLevel[i] = [];
  layoutNodes.forEach(n => byLevel[localLevel[n.taskId]].push(n));

  // Compute height for each node (groups have variable height)
  const nodeHeight = (n: TaskGraphNode): number => {
    const group = subtaskGroupMap.get(n.taskId);
    if (!group) return NODE_H;
    return expandedGroups.has(n.taskId)
      ? expandedGroupHeight(group.subtasks.length)
      : GROUP_COLLAPSED_H;
  };

  // Assign pixel positions with variable heights
  const pos: Record<string, { x: number; y: number; h: number }> = {};
  for (let lvl = 0; lvl <= maxLevel; lvl++) {
    const col = byLevel[lvl];
    let yOffset = 16;
    col.forEach(n => {
      const h = nodeHeight(n);
      pos[n.taskId] = {
        x: lvl * (NODE_W + COL_GAP) + 16,
        y: yOffset,
        h,
      };
      yOffset += h + ROW_GAP;
    });
  }

  // SVG canvas size
  const svgW = (maxLevel + 1) * (NODE_W + COL_GAP) + 32;
  const maxColHeight = Math.max(16, ...Object.values(byLevel).map(col =>
    col.reduce((sum, n) => sum + nodeHeight(n) + ROW_GAP, 16)
  ));
  const svgH = maxColHeight + 16;

  // Build edges (only between layout nodes, not subtasks)
  const edges: { from: TaskGraphNode; to: TaskGraphNode }[] = [];
  layoutNodes.forEach(to => {
    (to.dependsOn ?? []).forEach(depId => {
      const from = layoutNodes.find(n => n.taskId === depId);
      if (from) edges.push({ from, to });
    });
  });

  // ── Badge helpers ─────────────────────────────────────────────────────────
  const complexityBadge = (c: string | null) => {
    switch (c?.toUpperCase()) {
      case 'HIGH':   return { bg: 'rgba(239,68,68,0.15)', text: '#fca5a5' };
      case 'MEDIUM': return { bg: 'rgba(234,179,8,0.15)', text: '#fde047' };
      default:       return { bg: 'rgba(99,102,241,0.15)', text: '#a5b4fc' };
    }
  };

  const sourceBadge = (taskId: string): { label: string; bg: string; text: string; border: string } | null => {
    if (taskId.includes('_cop')) return { label: 'COPILOT', bg: 'rgba(139,92,246,0.20)', text: '#c4b5fd', border: '#8B5CF6' };
    if (taskId.includes('_rev')) return { label: 'REVIEW',  bg: 'rgba(6,182,212,0.20)', text: '#67e8f9', border: '#06B6D4' };
    return null;
  };

  const displayTaskId = (taskId: string): string => {
    const stripped = taskId.replace(/^[a-f0-9]+_(?:cop|rev)\d+_/, '');
    return stripped.length > 22 ? stripped.slice(0, 21) + '\u2026' : stripped;
  };

  // ── Render ────────────────────────────────────────────────────────────────
  return (
    <div className="relative w-full overflow-auto rounded-sm border border-[var(--border-default)] bg-[#0a0a0f] p-2">
      {/* Legend */}
      <div className="flex flex-wrap gap-3 px-3 pt-2 pb-1 text-xs">
        {Object.entries(STATUS_COLOR).map(([s, c]) => (
          <span key={s} className="flex items-center gap-1.5">
            <span className="w-2 h-2 rounded-full" style={{ background: c.dot }} />
            <span style={{ color: c.text }}>{s}</span>
          </span>
        ))}
        <span className="border-l border-[var(--border-default)] pl-3 flex items-center gap-1.5">
          <span className="w-2 h-2 rounded-sm" style={{ background: '#8B5CF6' }} />
          <span style={{ color: '#c4b5fd' }}>COPILOT</span>
        </span>
        <span className="flex items-center gap-1.5">
          <span className="w-2 h-2 rounded-sm" style={{ background: '#06B6D4' }} />
          <span style={{ color: '#67e8f9' }}>REVIEW</span>
        </span>
      </div>

      <svg
        ref={svgRef}
        width={svgW}
        height={svgH}
        style={{ display: 'block', minWidth: '100%' }}
        onMouseLeave={() => setTooltip(null)}
      >
        <defs>
          <marker id="arrow" markerWidth="8" markerHeight="8"
            refX="7" refY="3" orient="auto" markerUnits="strokeWidth">
            <path d="M0,0 L0,6 L8,3 z" fill="#4b5563" />
          </marker>
          <filter id="glow">
            <feGaussianBlur stdDeviation="2.5" result="coloredBlur"/>
            <feMerge><feMergeNode in="coloredBlur"/><feMergeNode in="SourceGraphic"/></feMerge>
          </filter>
        </defs>

        {/* Edges */}
        {edges.map(({ from, to }, i) => {
          const fp = pos[from.taskId];
          const tp = pos[to.taskId];
          if (!fp || !tp) return null;
          // Anchor at vertical center of header area for groups, center for regular nodes
          const fromIsGroup = groupParentIds.has(from.taskId);
          const toIsGroup = groupParentIds.has(to.taskId);
          const fx = fp.x + NODE_W;
          const fy = fp.y + (fromIsGroup ? GROUP_HEADER_H / 2 : NODE_H / 2);
          const tx = tp.x;
          const ty = tp.y + (toIsGroup ? GROUP_HEADER_H / 2 : NODE_H / 2);
          const mx = (fx + tx) / 2;
          const active = from.status === 'COMPLETE';
          const fromIsCop = from.taskId.includes('_cop');
          const fromIsRev = from.taskId.includes('_rev');
          const activeEdgeColor = fromIsCop ? '#6d28d9' : fromIsRev ? '#0e7490' : '#374151';
          const inactiveEdgeColor = fromIsCop ? '#3b0764' : fromIsRev ? '#164e63' : '#1f2937';
          return (
            <path
              key={i}
              d={`M${fx},${fy} C${mx},${fy} ${mx},${ty} ${tx},${ty}`}
              fill="none"
              stroke={active ? activeEdgeColor : inactiveEdgeColor}
              strokeWidth={active ? 1.5 : 1}
              strokeDasharray={active ? undefined : '4 3'}
              markerEnd="url(#arrow)"
              opacity={active ? 0.8 : 0.4}
            />
          );
        })}

        {/* Nodes */}
        {layoutNodes.map(n => {
          const p = pos[n.taskId];
          if (!p) return null;
          const group = subtaskGroupMap.get(n.taskId);

          // ── Group node (parent with subtasks) ──
          if (group) {
            return renderGroupNode(n, group, p, expandedGroups.has(n.taskId));
          }

          // ── Standalone node (no subtasks) ──
          return renderStandaloneNode(n, p);
        })}

        {/* Tooltip */}
        {tooltip && renderTooltip()}
      </svg>
    </div>
  );

  // ── Standalone node renderer ──────────────────────────────────────────────
  function renderStandaloneNode(n: TaskGraphNode, p: { x: number; y: number }) {
    const c = statusColor(n.status);
    const cb = complexityBadge(n.complexity);
    const sb = sourceBadge(n.taskId);
    const isWorking = n.status === 'WORKING';
    return (
      <g key={n.taskId} transform={`translate(${p.x},${p.y})`} style={{ cursor: 'pointer' }}
        onMouseEnter={() => setTooltip({ x: p.x + NODE_W / 2, y: p.y, node: n })}
        onMouseLeave={() => setTooltip(null)}>
        {isWorking && (
          <rect x={-2} y={-2} width={NODE_W + 4} height={NODE_H + 4}
            rx={10} fill="none" stroke={c.border} strokeWidth={2} opacity={0.3}
            filter="url(#glow)" />
        )}
        <rect x={0} y={0} width={NODE_W} height={NODE_H}
          rx={8} fill={c.bg} stroke={sb ? sb.border : c.border} strokeWidth={1.5} />
        {sb && <rect x={0} y={4} width={3} height={NODE_H - 8} rx={1.5} fill={sb.border} />}

        {/* Status dot */}
        <circle cx={14} cy={14} r={4} fill={c.dot} />
        {isWorking && <circle cx={14} cy={14} r={4} fill={c.dot} opacity={0.4}>
          <animate attributeName="r" from="4" to="9" dur="1.4s" repeatCount="indefinite"/>
          <animate attributeName="opacity" from="0.4" to="0" dur="1.4s" repeatCount="indefinite"/>
        </circle>}

        {/* Source badge */}
        {sb && (
          <g transform={`translate(${NODE_W - 58}, 6)`}>
            <rect x={0} y={0} width={52} height={14} rx={4} fill={sb.bg} stroke={sb.border} strokeWidth={0.5} />
            <text x={26} y={7} fontSize={7.5} fill={sb.text} fontFamily="'Geist Mono', monospace"
              textAnchor="middle" dominantBaseline="middle" fontWeight="700">{sb.label}</text>
          </g>
        )}

        {/* Task ID */}
        <text x={24} y={16} fontSize={9} fill={c.dot} fontFamily="'Geist Mono', monospace" fontWeight="600"
          dominantBaseline="middle">{displayTaskId(n.taskId)}</text>

        {/* Title */}
        <text x={10} y={38} fontSize={10.5} fill={c.text} fontFamily="'Geist Sans', sans-serif"
          dominantBaseline="middle">{n.title.length > 26 ? n.title.slice(0, 25) + '\u2026' : n.title}</text>

        {/* Complexity badge */}
        {n.complexity && (
          <g transform={`translate(${NODE_W - 54}, ${NODE_H - 20})`}>
            <rect x={0} y={0} width={48} height={14} rx={4} fill={cb.bg} />
            <text x={24} y={7} fontSize={8.5} fill={cb.text} fontFamily="'Geist Mono', monospace"
              textAnchor="middle" dominantBaseline="middle" fontWeight="600">{n.complexity?.toUpperCase()}</text>
          </g>
        )}

        {/* Level badge */}
        <g transform={`translate(8, ${NODE_H - 20})`}>
          <text x={0} y={7} fontSize={8} fill="#4b5563" fontFamily="'Geist Mono', monospace"
            dominantBaseline="middle">L{localLevel[n.taskId]}</text>
        </g>
      </g>
    );
  }

  // ── Group node renderer ───────────────────────────────────────────────────
  function renderGroupNode(
    parent: TaskGraphNode,
    group: SubtaskGroup,
    p: { x: number; y: number; h: number },
    isExpanded: boolean,
  ) {
    const aggStatus = groupStatus(group.subtasks);
    const c = statusColor(aggStatus);
    const sb = sourceBadge(parent.taskId);
    const isWorking = aggStatus === 'WORKING';
    const completedCount = group.subtasks.filter(s => s.status === 'COMPLETE').length;
    const totalCount = group.subtasks.length;
    const progressPct = totalCount > 0 ? (completedCount / totalCount) * 100 : 0;

    return (
      <g key={parent.taskId} transform={`translate(${p.x},${p.y})`}>
        {/* Working glow */}
        {isWorking && (
          <rect x={-2} y={-2} width={NODE_W + 4} height={p.h + 4}
            rx={10} fill="none" stroke={c.border} strokeWidth={2} opacity={0.3}
            filter="url(#glow)" />
        )}

        {/* Group container */}
        <rect x={0} y={0} width={NODE_W} height={p.h}
          rx={8} fill={c.bg} stroke={sb ? sb.border : c.border} strokeWidth={1.5}
          strokeDasharray={isExpanded ? undefined : '6 3'} />
        {sb && <rect x={0} y={4} width={3} height={p.h - 8} rx={1.5} fill={sb.border} />}

        {/* ── Header area (clickable) ── */}
        <g style={{ cursor: 'pointer' }}
          onClick={() => toggleGroup(parent.taskId)}
          onMouseEnter={() => setTooltip({ x: p.x + NODE_W / 2, y: p.y, node: parent, isGroup: true, group })}
          onMouseLeave={() => setTooltip(null)}>
          {/* Clickable hit area */}
          <rect x={0} y={0} width={NODE_W} height={GROUP_HEADER_H} rx={8} fill="transparent" />

          {/* Status dot */}
          <circle cx={14} cy={14} r={4} fill={c.dot} />
          {isWorking && <circle cx={14} cy={14} r={4} fill={c.dot} opacity={0.4}>
            <animate attributeName="r" from="4" to="9" dur="1.4s" repeatCount="indefinite"/>
            <animate attributeName="opacity" from="0.4" to="0" dur="1.4s" repeatCount="indefinite"/>
          </circle>}

          {/* Source badge */}
          {sb && (
            <g transform={`translate(${NODE_W - 58}, 6)`}>
              <rect x={0} y={0} width={52} height={14} rx={4} fill={sb.bg} stroke={sb.border} strokeWidth={0.5} />
              <text x={26} y={7} fontSize={7.5} fill={sb.text} fontFamily="'Geist Mono', monospace"
                textAnchor="middle" dominantBaseline="middle" fontWeight="700">{sb.label}</text>
            </g>
          )}

          {/* Parent title */}
          <text x={24} y={16} fontSize={9} fill={c.dot} fontFamily="'Geist Mono', monospace" fontWeight="600"
            dominantBaseline="middle">{displayTaskId(parent.taskId)}</text>
          <text x={10} y={34} fontSize={10.5} fill={c.text} fontFamily="'Geist Sans', sans-serif"
            dominantBaseline="middle">
            {parent.title.length > 22 ? parent.title.slice(0, 21) + '\u2026' : parent.title}
          </text>

          {/* Progress bar + count */}
          <g transform={`translate(10, ${GROUP_HEADER_H - 14})`}>
            {/* Track */}
            <rect x={0} y={0} width={NODE_W - 56} height={5} rx={2.5}
              fill="rgba(255,255,255,0.06)" />
            {/* Fill */}
            <rect x={0} y={0} width={Math.max(0, (NODE_W - 56) * progressPct / 100)} height={5} rx={2.5}
              fill={c.dot} opacity={0.7} />
            {/* Count text */}
            <text x={NODE_W - 50} y={3} fontSize={8} fill={c.text}
              fontFamily="'Geist Mono', monospace" dominantBaseline="middle">
              {completedCount}/{totalCount}
            </text>
          </g>

          {/* Expand/collapse chevron */}
          <g transform={`translate(${NODE_W - 18}, ${GROUP_HEADER_H / 2 - 4})`}>
            <text fontSize={10} fill="#666" fontFamily="'Geist Sans', sans-serif"
              dominantBaseline="middle" style={{ userSelect: 'none' }}>
              {isExpanded ? '\u25B4' : '\u25BE'}
            </text>
          </g>
        </g>

        {/* ── Expanded subtask nodes ── */}
        {isExpanded && group.subtasks.map((st, i) => {
          const stc = statusColor(st.status);
          const stIsWorking = st.status === 'WORKING';
          const sy = GROUP_HEADER_H + i * (SUBTASK_NODE_H + SUBTASK_ROW_GAP);
          const sx = GROUP_PAD_X;
          const fileName = extractFileName(st.title);
          return (
            <g key={st.taskId} transform={`translate(${sx},${sy})`}
              onMouseEnter={() => setTooltip({
                x: p.x + sx + SUBTASK_NODE_W / 2, y: p.y + sy, node: st
              })}
              onMouseLeave={() => setTooltip(null)}>
              {/* Subtask body */}
              <rect x={0} y={0} width={SUBTASK_NODE_W} height={SUBTASK_NODE_H}
                rx={5} fill={stc.bg} stroke={stc.border} strokeWidth={1} opacity={0.9} />

              {/* Status dot */}
              <circle cx={12} cy={SUBTASK_NODE_H / 2} r={3} fill={stc.dot} />
              {stIsWorking && <circle cx={12} cy={SUBTASK_NODE_H / 2} r={3} fill={stc.dot} opacity={0.4}>
                <animate attributeName="r" from="3" to="7" dur="1.4s" repeatCount="indefinite"/>
                <animate attributeName="opacity" from="0.4" to="0" dur="1.4s" repeatCount="indefinite"/>
              </circle>}

              {/* Index */}
              <text x={22} y={SUBTASK_NODE_H / 2} fontSize={8} fill={stc.dot}
                fontFamily="'Geist Mono', monospace" fontWeight="600" dominantBaseline="middle">
                {st.subtaskIndex ?? (i + 1)}/{st.subtaskTotal ?? totalCount}
              </text>

              {/* Filename */}
              <text x={48} y={SUBTASK_NODE_H / 2} fontSize={9} fill={stc.text}
                fontFamily="'Geist Mono', monospace" dominantBaseline="middle">
                {fileName.length > 16 ? fileName.slice(0, 15) + '\u2026' : fileName}
              </text>
            </g>
          );
        })}
      </g>
    );
  }

  // ── Tooltip renderer ──────────────────────────────────────────────────────
  function renderTooltip() {
    if (!tooltip) return null;
    const { x, y, node, isGroup, group } = tooltip;
    const c = statusColor(node.status);

    if (isGroup && group) {
      // Group tooltip: show status breakdown + file list
      const statusBreakdown = new Map<string, number>();
      group.subtasks.forEach(s => {
        const st = s.status?.toUpperCase() ?? 'PENDING';
        statusBreakdown.set(st, (statusBreakdown.get(st) ?? 0) + 1);
      });
      const breakdownText = Array.from(statusBreakdown.entries())
        .map(([s, count]) => `${count} ${s.toLowerCase()}`)
        .join(', ');

      const tw = 220;
      const fileListHeight = Math.min(group.subtasks.length, 6) * 13;
      const th = 68 + fileListHeight;
      const tx2 = Math.min(x - tw / 2, svgW - tw - 8);
      const ty2 = y - th - 10;
      return (
        <g transform={`translate(${Math.max(8, tx2)},${Math.max(8, ty2)})`}
          style={{ pointerEvents: 'none' }}>
          <rect x={0} y={0} width={tw} height={th} rx={8}
            fill="#111827" stroke={c.border} strokeWidth={1.5} />
          <text x={10} y={18} fontSize={10} fill={c.text} fontWeight="700"
            fontFamily="'Geist Sans', sans-serif">
            {node.title.length > 28 ? node.title.slice(0, 27) + '\u2026' : node.title}
          </text>
          <text x={10} y={32} fontSize={9} fill="#9ca3af" fontFamily="'Geist Sans', sans-serif">
            {group.subtasks.length} subtasks: {breakdownText}
          </text>
          <text x={10} y={46} fontSize={8.5} fill="#6b7280" fontFamily="'Geist Mono', monospace">
            {(node.assignedModel ?? '').replace('claude-', '').slice(0, 30)}
          </text>
          <line x1={10} y1={52} x2={tw - 10} y2={52} stroke="#1f2937" strokeWidth={0.5} />
          {group.subtasks.slice(0, 6).map((st, i) => {
            const stc = statusColor(st.status);
            return (
              <g key={st.taskId} transform={`translate(10, ${58 + i * 13})`}>
                <circle cx={4} cy={5} r={2.5} fill={stc.dot} />
                <text x={12} y={5} fontSize={8} fill="#9ca3af"
                  fontFamily="'Geist Mono', monospace" dominantBaseline="middle">
                  {extractFileName(st.title)}
                </text>
              </g>
            );
          })}
          {group.subtasks.length > 6 && (
            <text x={10} y={58 + 6 * 13 + 4} fontSize={8} fill="#4b5563"
              fontFamily="'Geist Mono', monospace">
              +{group.subtasks.length - 6} more
            </text>
          )}
        </g>
      );
    }

    // Standard node tooltip
    const tw = 210;
    const th = 80 + (node.dependsOn?.length ? node.dependsOn.length * 14 : 0);
    const tx2 = Math.min(x - tw / 2, svgW - tw - 8);
    const ty2 = y - th - 10;
    return (
      <g transform={`translate(${Math.max(8, tx2)},${Math.max(8, ty2)})`}
        style={{ pointerEvents: 'none' }}>
        <rect x={0} y={0} width={tw} height={th} rx={8}
          fill="#111827" stroke={c.border} strokeWidth={1.5} />
        <text x={10} y={18} fontSize={10} fill={c.text} fontWeight="700"
          fontFamily="'Geist Sans', sans-serif">{node.taskId}</text>
        <text x={10} y={32} fontSize={9} fill="#9ca3af" fontFamily="'Geist Sans', sans-serif">
          {node.status} {'\u00B7'} {node.complexity ?? '?'} {'\u00B7'} L{localLevel[node.taskId] ?? '?'}
        </text>
        <text x={10} y={46} fontSize={8.5} fill="#6b7280" fontFamily="'Geist Mono', monospace">
          {(node.assignedModel ?? '').replace('claude-', '').slice(0, 30)}
        </text>
        {node.dependsOn?.length > 0 && (
          <>
            <text x={10} y={62} fontSize={8.5} fill="#4b5563" fontFamily="'Geist Sans', sans-serif">
              depends on:
            </text>
            {node.dependsOn.map((d, i) => {
              const depNode = nodes.find(n => n.taskId === d);
              const depLabel = depNode ? depNode.title : d;
              return (
                <text key={d} x={16} y={74 + i * 14} fontSize={8.5}
                  fill="#6366f1" fontFamily="'Geist Mono', monospace">
                  {'\u2192'} {depLabel.length > 28 ? depLabel.slice(0, 27) + '\u2026' : depLabel}
                </text>
              );
            })}
          </>
        )}
      </g>
    );
  }
}
