import { useEffect, useRef } from 'react';
import * as d3 from 'd3';
import GlassPanel from '@/components/shared/GlassPanel';

const AGENTS = ['Planner', 'Worker 1', 'Worker 2', 'Reviewer'];
const LINKS = [
  { source: 0, target: 1 }, { source: 0, target: 2 },
  { source: 1, target: 3 }, { source: 2, target: 3 },
];

export default function AgentCommGraph() {
  const svgRef = useRef<SVGSVGElement>(null);

  useEffect(() => {
    if (!svgRef.current) return;
    const W = svgRef.current.clientWidth;
    const H = 180;
    d3.select(svgRef.current).selectAll('*').remove();
    const svg = d3.select(svgRef.current).attr('height', H);

    const nodes = AGENTS.map((name, i) => ({ id: i, name }));
    const links = LINKS.map((l) => ({ ...l }));

    const defs = svg.append('defs');
    const grad = defs.append('linearGradient').attr('id', 'edge-grad').attr('gradientUnits', 'userSpaceOnUse');
    grad.append('stop').attr('offset', '0%').attr('stop-color', '#8B5CF6').attr('stop-opacity', 0.6);
    grad.append('stop').attr('offset', '100%').attr('stop-color', '#06B6D4').attr('stop-opacity', 0.6);

    const sim = d3.forceSimulation(nodes as any)
      .force('link', d3.forceLink(links).id((d: any) => d.id).distance(70))
      .force('charge', d3.forceManyBody().strength(-120))
      .force('center', d3.forceCenter(W / 2, H / 2))
      .stop();

    for (let i = 0; i < 100; i++) sim.tick();

    svg.selectAll('line').data(links).join('line')
      .attr('x1', (d: any) => d.source.x).attr('y1', (d: any) => d.source.y)
      .attr('x2', (d: any) => d.target.x).attr('y2', (d: any) => d.target.y)
      .attr('stroke', 'url(#edge-grad)').attr('stroke-width', 1.5).attr('opacity', 0.5);

    const g = svg.selectAll('g').data(nodes).join('g')
      .attr('transform', (d: any) => `translate(${d.x},${d.y})`);

    g.append('circle').attr('r', 18).attr('fill', 'rgba(139,92,246,0.15)')
      .attr('stroke', '#8B5CF6').attr('stroke-width', 1).attr('stroke-opacity', 0.5);

    g.append('text').attr('text-anchor', 'middle').attr('dominant-baseline', 'middle')
      .attr('fill', 'rgba(255,255,255,0.7)').attr('font-size', 9)
      .text((d) => d.name);
  }, []);

  return (
    <GlassPanel className="p-4">
      <h3 className="text-sm font-semibold text-primary mb-3">Agent Communication</h3>
      <svg ref={svgRef} className="w-full" />
    </GlassPanel>
  );
}
