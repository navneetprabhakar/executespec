import type { SolutionSpec, DependencyEdge } from '@/types/execution';

interface Props {
  solutions: SolutionSpec[];
  edges: DependencyEdge[];
}

/** Layered text-first DAG. Groups solutions into topological layers and prints
 *  them column-by-column. Cheap, clear, no chart library. */
export default function DependencyGraph({ solutions, edges }: Props) {
  if (solutions.length === 0) return null;
  const deps = new Map<number, Set<number>>();
  solutions.forEach((s) => deps.set(s.sequenceNumber, new Set()));
  edges.forEach((e) => deps.get(e.to)?.add(e.from));

  const placed = new Set<number>();
  const layers: SolutionSpec[][] = [];
  while (placed.size < solutions.length) {
    const layer = solutions.filter(
      (s) => !placed.has(s.sequenceNumber) &&
        Array.from(deps.get(s.sequenceNumber) ?? []).every((d) => placed.has(d)),
    );
    if (layer.length === 0) break; // cycle — bail out defensively
    layers.push(layer);
    layer.forEach((s) => placed.add(s.sequenceNumber));
  }

  return (
    <div className="flex gap-2 overflow-x-auto pb-2">
      {layers.map((layer, li) => (
        <div key={li} className="flex flex-col gap-1.5 min-w-[160px]">
          <div className="text-[10px] uppercase tracking-wide text-muted">Layer {li + 1}</div>
          {layer.map((s) => (
            <div
              key={s.sequenceNumber}
              className="rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] px-2 py-1 text-[11px]"
            >
              <div className="font-mono text-[10px] text-muted">S{s.sequenceNumber} · {s.complexity}</div>
              <div className="truncate">{s.title}</div>
              {s.repoName && <div className="text-[10px] text-muted truncate">{s.repoName}</div>}
            </div>
          ))}
        </div>
      ))}
    </div>
  );
}
