import type { PrSummary } from '@/types/project';

const STATUS_COLOR: Record<string, string> = {
  OPEN: 'bg-blue-100 text-blue-700',
  MERGED: 'bg-purple-100 text-purple-700',
  CLOSED: 'bg-red-100 text-red-700',
};

export default function ProjectPrList({ prs }: { prs: PrSummary[] }) {
  if (!prs || prs.length === 0) {
    return <div className="text-sm text-gray-500">No pull requests yet.</div>;
  }
  const merged = prs.filter((p) => p.status === 'MERGED').length;
  const total = prs.length;
  return (
    <div className="space-y-2">
      <div className="text-xs text-gray-500">
        {merged}/{total} merged ({Math.round((merged / total) * 100)}%)
      </div>
      <ul className="space-y-1">
        {prs.map((pr) => (
          <li key={`${pr.repoFullName}-${pr.prNumber}`} className="flex items-center gap-2 text-sm">
            <span className={`text-[10px] uppercase tracking-wide px-1.5 py-0.5 rounded ${STATUS_COLOR[pr.status] ?? 'bg-gray-100'}`}>
              {pr.status}
            </span>
            <a
              href={pr.prUrl}
              target="_blank"
              rel="noopener noreferrer"
              className="font-mono text-blue-600 hover:underline truncate"
            >
              {pr.repoFullName}#{pr.prNumber}
            </a>
          </li>
        ))}
      </ul>
    </div>
  );
}
