import { useEffect, useRef } from 'react';
import * as Tabs from '@radix-ui/react-tabs';
import { useUiStore } from '@/stores/useUiStore';
import { useRunStore } from '@/stores/useRunStore';
import { getTimeline } from '@/lib/api';
import type { RunDetail } from '@/lib/api';
import GlowDot from '@/components/shared/GlowDot';
import TaskCard from './TaskCard';
import LiveLog from './LiveLog';
import { PIPELINE_STEPS } from '@/lib/constants';
import { fmtTokens, fmtCost, relativeTime, cn } from '@/lib/utils';
import * as d3 from 'd3';

const TABS = [
  { value: 'overview',  label: 'Overview' },
  { value: 'tasks',     label: 'Tasks' },
  { value: 'log',       label: 'Live Log' },
  { value: 'timeline',  label: 'Timeline' },
  { value: 'code',      label: 'Code' },
  { value: 'preview',   label: 'Preview' },
];

export default function RunDetailTabs() {
  const { activeTab, setActiveTab } = useUiStore();
  const activeRun = useRunStore((s) => s.activeRun);

  if (!activeRun) return null;

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
            {tab.label}
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
                <TaskCard key={t.taskId} task={t} runId={activeRun.runId} />
              ))
          }
        </Tabs.Content>

        <Tabs.Content value="log" className="h-full">
          <LiveLog />
        </Tabs.Content>

        <Tabs.Content value="timeline" className="h-full overflow-hidden">
          <GanttChart runId={activeRun.runId} />
        </Tabs.Content>

        <Tabs.Content value="code" className="h-full overflow-y-auto p-4">
          <CodeTab runId={activeRun.runId} />
        </Tabs.Content>

        <Tabs.Content value="preview" className="h-full">
          <iframe
            src={`/api/runs/${activeRun.runId}/preview/`}
            className="w-full h-full border-0"
            title="Preview"
          />
        </Tabs.Content>
      </div>
    </Tabs.Root>
  );
}

// ── Overview tab ─────────────────────────────────────────────────────────────

function OverviewTab({ run }: { run: RunDetail }) {
  const stepIndex = PIPELINE_STEPS.indexOf(run.status);

  return (
    <div className="space-y-6">
      {/* Pipeline timeline */}
      <div className="space-y-0">
        {PIPELINE_STEPS.map((step, i) => {
          const isDone = i < stepIndex;
          const isActive = step === run.status;
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
          { label: 'Model', value: run.modelName },
          { label: 'Provider', value: run.provider },
          { label: 'Tokens', value: fmtTokens(run.totalTokens) },
          { label: 'Cost', value: fmtCost(run.totalCostUsd) },
          { label: 'Created', value: relativeTime(run.createdAt) },
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

// ── Gantt chart tab ───────────────────────────────────────────────────────────

function GanttChart({ runId }: { runId: string }) {
  const svgRef = useRef<SVGSVGElement>(null);

  useEffect(() => {
    if (!svgRef.current) return;
    getTimeline(runId).then((data) => {
      const tasks = data.tasks.filter((t) => t.startTime && t.endTime);
      if (!tasks.length) return;

      const margin = { top: 24, right: 16, bottom: 24, left: 120 };
      const width = svgRef.current!.clientWidth - margin.left - margin.right;
      const rowH = 28;
      const height = tasks.length * rowH;

      d3.select(svgRef.current).selectAll('*').remove();

      const svg = d3.select(svgRef.current)
        .attr('height', height + margin.top + margin.bottom)
        .append('g')
        .attr('transform', `translate(${margin.left},${margin.top})`);

      const times = tasks.flatMap((t) => [new Date(t.startTime), new Date(t.endTime)]);
      const x = d3.scaleTime().domain(d3.extent(times) as [Date, Date]).range([0, width]);
      const y = d3.scaleBand().domain(tasks.map((t) => t.taskId)).range([0, height]).padding(0.3);

      const colors: Record<string, string> = {
        COMPLETE: '#10B981', FAILED: '#F43F5E', WORKING: '#8B5CF6', default: '#06B6D4',
      };

      // Grid lines
      svg.selectAll('.gridline')
        .data(x.ticks(5))
        .join('line')
        .attr('x1', (d) => x(d))
        .attr('x2', (d) => x(d))
        .attr('y1', 0)
        .attr('y2', height)
        .attr('stroke', 'rgba(255,255,255,0.05)')
        .attr('stroke-dasharray', '3,3');

      // Bars
      svg.selectAll('.bar')
        .data(tasks)
        .join('rect')
        .attr('x', (d) => x(new Date(d.startTime)))
        .attr('y', (d) => y(d.taskId)!)
        .attr('width', (d) => Math.max(2, x(new Date(d.endTime)) - x(new Date(d.startTime))))
        .attr('height', y.bandwidth())
        .attr('fill', (d) => colors[d.status] ?? colors.default)
        .attr('rx', 3)
        .attr('opacity', 0.85);

      // Labels
      svg.selectAll('.label')
        .data(tasks)
        .join('text')
        .attr('x', -8)
        .attr('y', (d) => y(d.taskId)! + y.bandwidth() / 2)
        .attr('text-anchor', 'end')
        .attr('dominant-baseline', 'middle')
        .attr('fill', 'rgba(255,255,255,0.5)')
        .attr('font-size', 11)
        .text((d) => d.title.slice(0, 14));

      // X axis
      svg.append('g')
        .attr('transform', `translate(0,${height})`)
        .call(d3.axisBottom(x).ticks(5).tickSize(0))
        .call((g) => g.select('.domain').remove())
        .selectAll('text')
        .attr('fill', 'rgba(255,255,255,0.3)')
        .attr('font-size', 10);
    }).catch(() => {});
  }, [runId]);

  return (
    <div className="h-full overflow-auto p-4">
      <svg ref={svgRef} className="w-full" />
    </div>
  );
}

// ── Code tab ──────────────────────────────────────────────────────────────────

function CodeTab({ runId }: { runId: string }) {
  return (
    <div className="space-y-3">
      <p className="text-sm text-zinc-500">
        Use the files API to browse the generated code.
      </p>
      <a
        href={`/api/runs/${runId}/files`}
        target="_blank"
        rel="noopener noreferrer"
        className="inline-block text-sm text-accent-violet hover:text-accent-violet/80"
      >
        Browse files →
      </a>
    </div>
  );
}
