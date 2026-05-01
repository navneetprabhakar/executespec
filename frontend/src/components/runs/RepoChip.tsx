/**
 * v2.5.2 (UX-3.1) — small repo attribution chip for task cards and graph nodes.
 *
 * Extracts a short-name from any `repos/<name>/...` path prefix in the task's
 * title or objective. Returns null when no match — call sites should render
 * conditionally so single-repo runs don't get a stray chip.
 *
 * Intentionally frontend-only: adding a dedicated `repoShortName` column on
 * AgentTask is a deeper change (entity + migration + planner persistence).
 * The prefix scan handles the common multi-repo case correctly since
 * WorkspaceInitService clones each repo to `workspace/<runId>/repos/<name>/`
 * and the planner emits file paths relative to that root.
 */

const REPO_PATH_RE = /\brepos\/([A-Za-z0-9._-]+)\//;

export function extractRepoShortName(...texts: (string | null | undefined)[]): string | null {
  for (const t of texts) {
    if (!t) continue;
    const m = t.match(REPO_PATH_RE);
    if (m && m[1]) return m[1];
  }
  return null;
}

export default function RepoChip({
  name,
  className = '',
}: {
  name: string | null;
  className?: string;
}) {
  if (!name) return null;
  return (
    <span
      className={`inline-flex items-center rounded-sm px-1 py-0.5 text-[9px] font-mono bg-accent-blue/10 text-accent-blue ${className}`}
      title={`repos/${name}`}
    >
      {name}
    </span>
  );
}
