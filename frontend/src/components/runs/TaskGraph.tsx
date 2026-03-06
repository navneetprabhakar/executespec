import { useEffect, useState, useRef, useCallback } from 'react';
import { getTaskGraph, TaskGraphNode } from '@/lib/api';

interface Props { runId: string }

// ── Status colours — kept in sync with tailwind.config.ts accent palette ────
// accent-emerald=#10B981  accent-cyan=#06B6D4  accent-amber=#F59E0B
// accent-rose=#F43F5E     accent-violet=#8B5CF6
const STATUS_COLOR: Record<string, { bg: string; border: string; text: string; dot: string }> = {
  COMPLETE:  { bg: '#051a10', border: '#10B981', text: '#6ee7b7', dot: '#10B981' },
  WORKING:   { bg: '#021619', border: '#06B6D4', text: '#67e8f9', dot: '#06B6D4' },
  PENDING:   { bg: '#18181b', border: '#71717a', text: '#a1a1aa', dot: '#71717a' },
  LOCKED:    { bg: '#18181b', border: '#52525b', text: '#a1a1aa', dot: '#52525b' },
  PARTIAL:   { bg: '#1c1407', border: '#F59E0B', text: '#fcd34d', dot: '#F59E0B' },
  BLOCKED:   { bg: '#1c1407', border: '#F59E0B', text: '#fcd34d', dot: '#F59E0B' },
  FAILED:    { bg: '#200510', border: '#F43F5E', text: '#fda4af', dot: '#F43F5E' },
};
const DEFAULT_COLOR = { bg: '#111118', border: '#374151', text: '#9ca3af', dot: '#6b7280' };

function statusColor(s: string) { return STATUS_COLOR[s?.toUpperCase()] ?? DEFAULT_COLOR; }

// ── Layout constants ─────────────────────────────────────────────────────────
const NODE_W = 200;
const NODE_H = 72;
const COL_GAP = 80;   // horizontal gap between levels
const ROW_GAP = 20;   // vertical gap between nodes in same level

// ── Component ────────────────────────────────────────────────────────────────
export default function TaskGraph({ runId }: Props) {
  const [nodes, setNodes] = useState<TaskGraphNode[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const svgRef = useRef<SVGSVGElement>(null);
  const [tooltip, setTooltip] = useState<{ x: number; y: number; node: TaskGraphNode } | null>(null);

  const load = useCallback(async () => {
    try {
      const data = await getTaskGraph(runId);
      setNodes(data);
    } catch (e: any) {
      setError(e.message ?? 'Failed to load graph');
    } finally {
      setLoading(false);
    }
  }, [runId]);

  useEffect(() => { load(); }, [load]);

  // Auto-refresh while any task is WORKING
  useEffect(() => {
    const working = nodes.some(n => n.status === 'WORKING' || n.status === 'PENDING');
    if (!working) return;
    const id = setInterval(load, 4000);
    return () => clearInterval(id);
  }, [nodes, load]);

  if (loading) return (
    <div className="flex items-center justify-center h-48 text-gray-500 text-sm">
      Loading execution graph…
    </div>
  );
  if (error) return (
    <div className="flex items-center justify-center h-48 text-red-400 text-sm">{error}</div>
  );
  if (nodes.length === 0) return (
    <div className="flex items-center justify-center h-48 text-gray-500 text-sm">
      No tasks found for this run.
    </div>
  );

  // ── Compute layout ──────────────────────────────────────────────────────
  const maxLevel = Math.max(...nodes.map(n => n.level));
  const byLevel: Record<number, TaskGraphNode[]> = {};
  for (let i = 0; i <= maxLevel; i++) byLevel[i] = [];
  nodes.forEach(n => byLevel[n.level].push(n));

  // Assign pixel positions
  const pos: Record<string, { x: number; y: number }> = {};
  for (let lvl = 0; lvl <= maxLevel; lvl++) {
    const col = byLevel[lvl];
    const colHeight = col.length * NODE_H + (col.length - 1) * ROW_GAP;
    col.forEach((n, i) => {
      pos[n.taskId] = {
        x: lvl * (NODE_W + COL_GAP) + 16,
        y: i * (NODE_H + ROW_GAP) + 16,
      };
    });
    void colHeight;
  }

  // SVG canvas size
  const svgW = (maxLevel + 1) * (NODE_W + COL_GAP) + 32;
  const maxRows = Math.max(...Object.values(byLevel).map(c => c.length));
  const svgH = maxRows * (NODE_H + ROW_GAP) + 32;

  // Build edges
  const edges: { from: TaskGraphNode; to: TaskGraphNode }[] = [];
  nodes.forEach(to => {
    (to.dependsOn ?? []).forEach(depId => {
      const from = nodes.find(n => n.taskId === depId);
      if (from) edges.push({ from, to });
    });
  });

  // Complexity badge colours
  const complexityBadge = (c: string | null) => {
    switch (c?.toUpperCase()) {
      case 'HIGH':   return { bg: 'rgba(239,68,68,0.15)', text: '#fca5a5' };
      case 'MEDIUM': return { bg: 'rgba(234,179,8,0.15)', text: '#fde047' };
      default:       return { bg: 'rgba(99,102,241,0.15)', text: '#a5b4fc' };
    }
  };

  return (
    <div className="relative w-full overflow-auto rounded-xl border border-white/10 bg-[#0a0a0f] p-2">
      {/* Legend */}
      <div className="flex flex-wrap gap-3 px-3 pt-2 pb-1 text-xs">
        {Object.entries(STATUS_COLOR).map(([s, c]) => (
          <span key={s} className="flex items-center gap-1.5">
            <span className="w-2 h-2 rounded-full" style={{ background: c.dot }} />
            <span style={{ color: c.text }}>{s}</span>
          </span>
        ))}
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
          const fx = pos[from.taskId].x + NODE_W;
          const fy = pos[from.taskId].y + NODE_H / 2;
          const tx = pos[to.taskId].x;
          const ty = pos[to.taskId].y + NODE_H / 2;
          const mx = (fx + tx) / 2;
          const active = from.status === 'COMPLETE';
          return (
            <path
              key={i}
              d={`M${fx},${fy} C${mx},${fy} ${mx},${ty} ${tx},${ty}`}
              fill="none"
              stroke={active ? '#374151' : '#1f2937'}
              strokeWidth={active ? 1.5 : 1}
              strokeDasharray={active ? undefined : '4 3'}
              markerEnd="url(#arrow)"
              opacity={active ? 0.8 : 0.4}
            />
          );
        })}

        {/* Nodes */}
        {nodes.map(n => {
          const { x, y } = pos[n.taskId];
          const c = statusColor(n.status);
          const cb = complexityBadge(n.complexity);
          const isWorking = n.status === 'WORKING';
          return (
            <g
              key={n.taskId}
              transform={`translate(${x},${y})`}
              style={{ cursor: 'pointer' }}
              onMouseEnter={e => {
                const rect = svgRef.current?.getBoundingClientRect();
                if (rect) setTooltip({ x: x + NODE_W / 2, y: y, node: n });
              }}
              onMouseLeave={() => setTooltip(null)}
            >
              {/* Glow for working tasks */}
              {isWorking && (
                <rect x={-2} y={-2} width={NODE_W + 4} height={NODE_H + 4}
                  rx={10} fill="none" stroke={c.border} strokeWidth={2} opacity={0.3}
                  filter="url(#glow)" />
              )}

              {/* Node body */}
              <rect x={0} y={0} width={NODE_W} height={NODE_H}
                rx={8} fill={c.bg} stroke={c.border} strokeWidth={1.5} />

              {/* Status dot */}
              <circle cx={14} cy={14} r={4} fill={c.dot} />
              {isWorking && <circle cx={14} cy={14} r={4} fill={c.dot} opacity={0.4}>
                <animate attributeName="r" from="4" to="9" dur="1.4s" repeatCount="indefinite"/>
                <animate attributeName="opacity" from="0.4" to="0" dur="1.4s" repeatCount="indefinite"/>
              </circle>}

              {/* Task ID */}
              <text x={24} y={16} fontSize={9} fill={c.dot} fontFamily="monospace" fontWeight="600"
                dominantBaseline="middle">
                {n.taskId.length > 22 ? n.taskId.slice(0, 21) + '…' : n.taskId}
              </text>

              {/* Title */}
              <text x={10} y={38} fontSize={10.5} fill={c.text} fontFamily="sans-serif"
                dominantBaseline="middle">
                {n.title.length > 26 ? n.title.slice(0, 25) + '…' : n.title}
              </text>

              {/* Complexity badge */}
              {n.complexity && (
                <g transform={`translate(${NODE_W - 54}, ${NODE_H - 20})`}>
                  <rect x={0} y={0} width={48} height={14} rx={4}
                    fill={cb.bg} />
                  <text x={24} y={7} fontSize={8.5} fill={cb.text} fontFamily="monospace"
                    textAnchor="middle" dominantBaseline="middle" fontWeight="600">
                    {n.complexity?.toUpperCase()}
                  </text>
                </g>
              )}

              {/* Level badge */}
              <g transform={`translate(8, ${NODE_H - 20})`}>
                <text x={0} y={7} fontSize={8} fill="#4b5563" fontFamily="monospace"
                  dominantBaseline="middle">
                  L{n.level}
                </text>
              </g>
            </g>
          );
        })}

        {/* Tooltip */}
        {tooltip && (() => {
          const { x, y, node } = tooltip;
          const c = statusColor(node.status);
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
                fontFamily="sans-serif">{node.taskId}</text>
              <text x={10} y={32} fontSize={9} fill="#9ca3af" fontFamily="sans-serif">
                {node.status} · {node.complexity ?? '?'} · L{node.level}
              </text>
              <text x={10} y={46} fontSize={8.5} fill="#6b7280" fontFamily="monospace">
                {(node.assignedModel ?? '').replace('claude-', '').slice(0, 30)}
              </text>
              {node.dependsOn?.length > 0 && (
                <>
                  <text x={10} y={62} fontSize={8.5} fill="#4b5563" fontFamily="sans-serif">
                    depends on:
                  </text>
                  {node.dependsOn.map((d, i) => (
                    <text key={d} x={16} y={74 + i * 14} fontSize={8.5}
                      fill="#6366f1" fontFamily="monospace">
                      → {d}
                    </text>
                  ))}
                </>
              )}
            </g>
          );
        })()}
      </svg>
    </div>
  );
}
