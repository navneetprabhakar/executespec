import { useEffect, useRef } from 'react';
import * as d3 from 'd3';
import GlassPanel from '@/components/shared/GlassPanel';
import type { UserRunsSummaryData } from '@/lib/api';

export default function UserRunsChart({ data }: { data: UserRunsSummaryData | null }) {
  const svgRef = useRef<SVGSVGElement>(null);

  useEffect(() => {
    if (!data || !svgRef.current || data.users.length === 0) return;
    const users = [...data.users]
      .sort((a, b) => b.runCount - a.runCount)
      .slice(0, 10);

    const isMobile = svgRef.current.clientWidth < 500;
    const margin = { top: 16, right: isMobile ? 30 : 60, bottom: 24, left: isMobile ? 80 : 130 };
    const width = svgRef.current.clientWidth - margin.left - margin.right;
    const rowH = 28;
    const height = users.length * rowH;

    d3.select(svgRef.current).selectAll('*').remove();
    const svg = d3.select(svgRef.current)
      .attr('height', height + margin.top + margin.bottom)
      .append('g').attr('transform', `translate(${margin.left},${margin.top})`);

    const x = d3.scaleLinear()
      .domain([0, d3.max(users, (d) => d.runCount) ?? 1])
      .range([0, width]).nice();
    const y = d3.scaleBand()
      .domain(users.map((d) => d.displayName))
      .range([0, height]).padding(0.3);

    const grad = svg.append('defs').append('linearGradient')
      .attr('id', 'user-bar-grad').attr('x1', '0%').attr('x2', '100%');
    grad.append('stop').attr('offset', '0%').attr('stop-color', '#10B981');
    grad.append('stop').attr('offset', '100%').attr('stop-color', '#06B6D4');

    svg.selectAll('.bar').data(users).join('rect')
      .attr('x', 0)
      .attr('y', (d) => y(d.displayName)!)
      .attr('height', y.bandwidth())
      .attr('width', (d) => x(d.runCount))
      .attr('fill', 'url(#user-bar-grad)')
      .attr('rx', 3).attr('opacity', 0.85);

    svg.selectAll('.label').data(users).join('text')
      .attr('x', -6)
      .attr('y', (d) => y(d.displayName)! + y.bandwidth() / 2)
      .attr('text-anchor', 'end').attr('dominant-baseline', 'middle')
      .attr('fill', 'rgba(255,255,255,0.5)').attr('font-size', isMobile ? 9 : 11)
      .text((d) => { const max = isMobile ? 10 : 16; return d.displayName.length > max ? d.displayName.slice(0, max - 1) + '\u2026' : d.displayName; });

    svg.selectAll('.value').data(users).join('text')
      .attr('x', (d) => x(d.runCount) + 4)
      .attr('y', (d) => y(d.displayName)! + y.bandwidth() / 2)
      .attr('dominant-baseline', 'middle')
      .attr('fill', 'rgba(255,255,255,0.35)').attr('font-size', 10)
      .text((d) => `${d.runCount} runs`);

    svg.append('g').attr('transform', `translate(0,${height})`)
      .call(d3.axisBottom(x).ticks(isMobile ? 3 : 4).tickSize(0))
      .call((g) => g.select('.domain').remove())
      .selectAll('text').attr('fill', 'rgba(255,255,255,0.3)').attr('font-size', 10);
  }, [data]);

  return (
    <GlassPanel className="p-4">
      <h3 className="text-sm font-semibold text-primary mb-3">Runs by User</h3>
      {!data || data.users.length === 0
        ? <p className="text-sm text-muted py-8 text-center">No data yet</p>
        : <svg ref={svgRef} className="w-full" />
      }
    </GlassPanel>
  );
}
