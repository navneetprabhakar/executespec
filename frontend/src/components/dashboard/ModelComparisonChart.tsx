import { useEffect, useRef } from 'react';
import * as d3 from 'd3';
import GlassPanel from '@/components/shared/GlassPanel';
import type { ModelComparisonData } from '@/lib/api';

export default function ModelComparisonChart({ data }: { data: ModelComparisonData | null }) {
  const svgRef = useRef<SVGSVGElement>(null);

  useEffect(() => {
    if (!data || !svgRef.current || data.models.length === 0) return;
    const models = [...data.models].sort((a, b) => (b.totalInputTokens + b.totalOutputTokens) - (a.totalInputTokens + a.totalOutputTokens)).slice(0, 8);

    const margin = { top: 16, right: 60, bottom: 24, left: 130 };
    const width = svgRef.current.clientWidth - margin.left - margin.right;
    const rowH = 28;
    const height = models.length * rowH;

    d3.select(svgRef.current).selectAll('*').remove();
    const svg = d3.select(svgRef.current)
      .attr('height', height + margin.top + margin.bottom)
      .append('g').attr('transform', `translate(${margin.left},${margin.top})`);

    const x = d3.scaleLinear().domain([0, d3.max(models, (d) => d.totalInputTokens + d.totalOutputTokens) ?? 1]).range([0, width]).nice();
    const y = d3.scaleBand().domain(models.map((d) => d.model)).range([0, height]).padding(0.3);

    const grad = svg.append('defs').append('linearGradient').attr('id', 'bar-grad').attr('x1', '0%').attr('x2', '100%');
    grad.append('stop').attr('offset', '0%').attr('stop-color', '#8B5CF6');
    grad.append('stop').attr('offset', '100%').attr('stop-color', '#06B6D4');

    svg.selectAll('.bar').data(models).join('rect')
      .attr('x', 0).attr('y', (d) => y(d.model)!).attr('height', y.bandwidth())
      .attr('width', (d) => x(d.totalInputTokens + d.totalOutputTokens)).attr('fill', 'url(#bar-grad)').attr('rx', 3).attr('opacity', 0.85);

    svg.selectAll('.label').data(models).join('text')
      .attr('x', -6).attr('y', (d) => y(d.model)! + y.bandwidth() / 2)
      .attr('text-anchor', 'end').attr('dominant-baseline', 'middle')
      .attr('fill', 'rgba(255,255,255,0.5)').attr('font-size', 11)
      .text((d) => d.model.replace('claude-', '').slice(0, 16));

    svg.selectAll('.value').data(models).join('text')
      .attr('x', (d) => x(d.totalInputTokens + d.totalOutputTokens) + 4).attr('y', (d) => y(d.model)! + y.bandwidth() / 2)
      .attr('dominant-baseline', 'middle').attr('fill', 'rgba(255,255,255,0.35)').attr('font-size', 10)
      .text((d) => { const t = d.totalInputTokens + d.totalOutputTokens; return t > 999 ? `${(t / 1000).toFixed(0)}k` : String(t); });

    svg.append('g').attr('transform', `translate(0,${height})`)
      .call(d3.axisBottom(x).ticks(4).tickSize(0))
      .call((g) => g.select('.domain').remove())
      .selectAll('text').attr('fill', 'rgba(255,255,255,0.3)').attr('font-size', 10);
  }, [data]);

  return (
    <GlassPanel className="p-4">
      <h3 className="text-sm font-semibold text-zinc-200 mb-3">Model Comparison (Tokens)</h3>
      {!data || data.models.length === 0
        ? <p className="text-sm text-zinc-600 py-8 text-center">No data yet</p>
        : <svg ref={svgRef} className="w-full" />
      }
    </GlassPanel>
  );
}
