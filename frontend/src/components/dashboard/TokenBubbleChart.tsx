import {useEffect, useRef} from 'react';
import * as d3 from 'd3';
import GlassPanel from '@/components/shared/GlassPanel';
import type {MetricsResponse} from '@/lib/api';

export default function TokenBubbleChart({metrics}: {metrics: MetricsResponse | null}) {
  const svgRef = useRef<SVGSVGElement>(null);

  useEffect(() => {
    if (!metrics || !svgRef.current) return;
    // Build runsByStatus from the individual count fields in MetricsResponse
    const runsByStatus = [
      ['COMPLETE', metrics.completedRuns],
      ['FAILED', metrics.failedRuns],
      ['ACTIVE', metrics.activeRuns],
      ['QUEUED', metrics.queuedRuns],
    ].filter(([, v]) => (v as number) > 0) as [string, number][];
    if (!runsByStatus.length) return;

    const W = svgRef.current.clientWidth;
    const H = 180;
    d3.select(svgRef.current).selectAll('*').remove();

    const svg = d3.select(svgRef.current).attr('height', H);

    const STATUS_HEX: Record<string, string> = {
      COMPLETE: '#10B981',
      FAILED: '#EF4444',
      STUCK: '#EF4444',
      WORKING: '#3B82F6',
      COMPILING: '#3B82F6',
      MERGING: '#3B82F6',
      PLANNING: '#8B5CF6',
      REVIEWING: '#8B5CF6',
      AWAITING_APPROVAL: '#F59E0B',
      AWAITING_MERGE: '#F59E0B',
      AWAITING_CLARIFICATION: '#F59E0B',
      PARTIAL: '#F97316',
      BLOCKED: '#EC4899',
      CANCELLED: '#71717A',
    };

    const data = runsByStatus.map(([status, count]) => ({status, count: count as number}));
    const pack = d3.pack<(typeof data)[0]>().size([W, H]).padding(8);
    const root = d3.hierarchy({children: data} as any).sum((d: any) => d.count ?? 0);
    const nodes = pack(root).leaves();

    const defs = svg.append('defs');
    nodes.forEach((node: any) => {
      const g = defs.append('radialGradient').attr('id', `bubble-${node.data.status}`);
      g.append('stop')
        .attr('offset', '0%')
        .attr('stop-color', STATUS_HEX[node.data.status] ?? '#8B5CF6')
        .attr('stop-opacity', 0.8);
      g.append('stop')
        .attr('offset', '100%')
        .attr('stop-color', STATUS_HEX[node.data.status] ?? '#8B5CF6')
        .attr('stop-opacity', 0.2);
    });

    const g = svg
      .selectAll('g')
      .data(nodes)
      .join('g')
      .attr('transform', (d: any) => `translate(${d.x},${d.y})`);
    g.append('circle')
      .attr('r', (d: any) => d.r)
      .attr('fill', (d: any) => `url(#bubble-${d.data.status})`)
      .attr('stroke', (d: any) => STATUS_HEX[d.data.status] ?? '#8B5CF6')
      .attr('stroke-width', 1)
      .attr('stroke-opacity', 0.4);

    g.append('text')
      .attr('text-anchor', 'middle')
      .attr('dominant-baseline', 'middle')
      .attr('fill', 'rgba(255,255,255,0.9)')
      .attr('font-size', (d: any) => Math.max(10, d.r / 2.5))
      .text((d: any) => d.data.count);

    g.filter((d: any) => d.r > 24)
      .append('text')
      .attr('text-anchor', 'middle')
      .attr('dominant-baseline', 'middle')
      .attr('y', (d: any) => d.r / 3)
      .attr('fill', 'rgba(255,255,255,0.5)')
      .attr('font-size', 9)
      .text((d: any) => d.data.status.replace('_', ' '));
  }, [metrics]);

  return (
    <GlassPanel className='p-4'>
      <h3 className='text-sm font-semibold text-primary mb-3'>Runs by Status</h3>
      {!metrics ? (
        <div className='h-44 flex items-center justify-center text-muted text-sm'>No data</div>
      ) : (
        <svg ref={svgRef} className='w-full' />
      )}
    </GlassPanel>
  );
}
