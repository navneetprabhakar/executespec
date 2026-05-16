/**
 * Tiny helpers for rendering run summaries and project rows in Signal pages.
 * Centralises the field-name normalisation across api.RunSummary /
 * api.RunDetail / api.ProjectDto so per-page code stays readable.
 */

import type {RunSummary, RunDetail, ProjectDto} from '@/lib/api';

export interface RunView {
  id: string;
  short: string;
  status: string;
  isLive: boolean;
  isTerminal: boolean;
  title: string;
  projectName?: string | null;
  projectId?: string | null;
  startedAt?: string | null;
  completedAt?: string | null;
  cost?: number | null;
  score?: number | null;
  prUrl?: string | null;
  archived: boolean;
}

const LIVE_STATUSES = new Set([
  'CLASSIFYING',
  'PLANNING',
  'EXECUTING',
  'AWAITING_APPROVAL',
  'RUNNING',
  'PROVIDER_SWITCHING',
  'PAUSED',
]);

export function isLiveStatus(status?: string | null): boolean {
  return LIVE_STATUSES.has((status || '').toUpperCase());
}

export function isTerminalStatus(status?: string | null): boolean {
  const s = (status || '').toUpperCase();
  return s === 'COMPLETED' || s === 'PARTIAL' || s === 'FAILED' || s === 'CANCELLED';
}

export function toRunView(r: RunSummary | RunDetail): RunView {
  const id = (r as unknown as {runId?: string}).runId || '';
  const status = (r.status || '').toUpperCase();
  const score =
    (r as unknown as {verifiedScore?: number; accuracyScore?: number}).verifiedScore ??
    (r as unknown as {accuracyScore?: number}).accuracyScore ??
    null;
  return {
    id,
    short: id ? `R-${id.slice(0, 6)}` : 'R-—',
    status,
    isLive: isLiveStatus(status),
    isTerminal: isTerminalStatus(status),
    title:
      (r as unknown as {runTitle?: string; userRequest?: string}).runTitle ||
      r.userRequest ||
      `Run R-${id.slice(0, 6)}`,
    projectName: (r as unknown as {projectName?: string | null}).projectName ?? null,
    projectId: (r as unknown as {projectId?: string | null}).projectId ?? null,
    startedAt: r.startedAt ?? null,
    completedAt: r.completedAt ?? null,
    cost: (r as unknown as {actualCostUsd?: number | null}).actualCostUsd ?? null,
    score,
    prUrl:
      (r as unknown as {scmPrUrl?: string | null}).scmPrUrl ??
      (r as unknown as {githubPrUrl?: string | null}).githubPrUrl ??
      null,
    archived: !!(r as unknown as {archivedAt?: string | null}).archivedAt,
  };
}

export function relTime(input?: string | null): string {
  if (!input) return '—';
  const t = new Date(input).getTime();
  if (!t) return '—';
  const s = Math.round((Date.now() - t) / 1000);
  if (s < 60) return `${s}s ago`;
  const m = Math.round(s / 60);
  if (m < 60) return `${m}m ago`;
  const h = Math.round(m / 60);
  if (h < 24) return `${h}h ago`;
  const d = Math.round(h / 24);
  if (d < 30) return `${d}d ago`;
  return new Date(input).toLocaleDateString();
}

export function statusToDot(s?: string): 'live' | 'mint' | 'coral' | 'signal' | 'lavender' {
  const x = (s || '').toUpperCase();
  if (LIVE_STATUSES.has(x)) return 'live';
  if (x === 'COMPLETED') return 'mint';
  if (x === 'FAILED' || x === 'CANCELLED') return 'coral';
  if (x === 'PARTIAL') return 'signal';
  return 'lavender';
}

export function scoreKind(score?: number | null): 'good' | 'warn' | 'fail' | 'default' {
  if (score == null) return 'default';
  if (score >= 85) return 'good';
  if (score >= 70) return 'warn';
  if (score > 0) return 'fail';
  return 'default';
}

export function formatElapsed(ms?: number | null): string {
  if (!ms || ms < 0) return '0s';
  const totalS = Math.floor(ms / 1000);
  const m = Math.floor(totalS / 60);
  const s = totalS % 60;
  if (m === 0) return `${s}s`;
  return `${m}m ${s}s`;
}

export function projectIsArchived(p: ProjectDto): boolean {
  return !!p.archivedAt;
}

/**
 * Render the display name for a project repo. The backend `ProjectRepoDto`
 * carries `githubRepoFullName` (e.g. `owner/repo`) — older fixtures and
 * fallback paths may surface a bare `name` / `repoName` instead. If every
 * candidate is missing, fall back to a short ID (first 8 chars + `…`)
 * rather than the full UUID, which is unreadable in chip pills + tables.
 *
 * Centralised here so the "UUID instead of repo name" regression spotted
 * on the Compose page, Code → Code review per-repo list, and Architecture
 * per-repo grid is fixed in exactly one place going forward.
 */
export function repoDisplayName(r: unknown): string {
  const rec = r as {
    githubRepoFullName?: string | null;
    fullName?: string | null;
    name?: string | null;
    repoName?: string | null;
    githubOwner?: string | null;
    repoId?: string | null;
    id?: string | null;
  };
  const slug = rec.githubRepoFullName?.trim() || rec.fullName?.trim();
  if (slug) return slug;
  const bare = rec.name?.trim() || rec.repoName?.trim();
  if (bare) {
    return rec.githubOwner ? `${rec.githubOwner}/${bare}` : bare;
  }
  const rawId = rec.id || rec.repoId || '';
  return rawId ? `${rawId.slice(0, 8)}…` : '—';
}

/** Stable, never-empty repo id for use as map keys / API params. */
export function repoIdFor(r: unknown): string {
  const rec = r as {repoId?: string | null; id?: string | null};
  return String(rec.repoId || rec.id || '');
}

/**
 * SDET 2026-05-16 (BUG-022) — single helper for picking a run title across all
 * five list views (Home, AllRuns, ProjectRuns split-list, ProjectPulse, project
 * compose recent-runs row). Previously some sites trimmed and some didn't; an
 * agent that returned `runTitle: "   "` (whitespace) would render the full
 * 600-char spec on the un-trimmed surfaces.
 *
 * Order: agent-generated `runTitle` (trimmed) → raw `userRequest` (trimmed) →
 * synthetic `Run R-xxxxxx` from runId → `Untitled run`.
 */
export function runTitleFor(r: unknown): string {
  const rec = r as {
    runTitle?: string | null;
    userRequest?: string | null;
    runId?: string | null;
    id?: string | null;
  };
  const title = rec.runTitle?.trim();
  if (title) return title;
  const spec = rec.userRequest?.trim();
  if (spec) return spec;
  const id = rec.runId || rec.id;
  if (id) return `Run R-${String(id).slice(0, 6)}`;
  return 'Untitled run';
}
