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
 * Render the display name for a project repo. Two shapes flow through this
 * helper today:
 *
 *  1. **`ProjectRepoDto`** (returned by `/api/repos` and `/api/repos/{id}`):
 *     fields live at the top level — `githubRepoFullName`, `githubOwner`, etc.
 *  2. **`ProjectRepoLinkDto`** (returned by `/api/projects/{id}/repos`):
 *     the link wrapper, with the repo nested at `.repo`. Its own `id` is the
 *     LINK uuid, not the repo uuid, so reading `.id` for a fallback name shows
 *     a meaningless prefix that users mistake for a git SHA.
 *
 * Order of preference:
 *   1. top-level `githubRepoFullName` / `fullName` (ProjectRepoDto)
 *   2. nested `repo.githubRepoFullName` / `repo.fullName` (ProjectRepoLinkDto)
 *   3. top-level or nested `name` / `repoName` (legacy fixtures)
 *   4. nothing left → 8-char short of the *repo* UUID (never the link UUID)
 *
 * Bug fixed 2026-05-16 (user feedback): the helper used to fall through to
 * `link.id.slice(0,8)` for every linked repo because the nested `.repo`
 * shape's name fields are invisible at the top level. That produced strings
 * like "a47b3c91…" in the Repos tab — visually indistinguishable from a SHA.
 */
export function repoDisplayName(r: unknown): string {
  const rec = (r ?? {}) as {
    githubRepoFullName?: string | null;
    fullName?: string | null;
    name?: string | null;
    repoName?: string | null;
    githubOwner?: string | null;
    repoId?: string | null;
    id?: string | null;
    repo?: {
      githubRepoFullName?: string | null;
      fullName?: string | null;
      name?: string | null;
      repoName?: string | null;
      githubOwner?: string | null;
      id?: string | null;
    } | null;
  };
  // ProjectRepoDto shape (top-level fields)
  const slug = rec.githubRepoFullName?.trim() || rec.fullName?.trim();
  if (slug) return slug;
  // ProjectRepoLinkDto shape (nested .repo)
  const nestedSlug = rec.repo?.githubRepoFullName?.trim() || rec.repo?.fullName?.trim();
  if (nestedSlug) return nestedSlug;
  // legacy bare-name fixtures (top-level or nested)
  const bareTop = rec.name?.trim() || rec.repoName?.trim();
  if (bareTop) {
    return rec.githubOwner ? `${rec.githubOwner}/${bareTop}` : bareTop;
  }
  const bareNested = rec.repo?.name?.trim() || rec.repo?.repoName?.trim();
  if (bareNested) {
    const owner = rec.repo?.githubOwner;
    return owner ? `${owner}/${bareNested}` : bareNested;
  }
  // Last resort: short of the REPO uuid (not the link uuid).
  const rawId = rec.repoId || rec.repo?.id || rec.id || '';
  return rawId ? `${rawId.slice(0, 8)}…` : '—';
}

/**
 * Stable, never-empty repo id for use as map keys / API params.
 *
 * Same nested-shape gotcha as {@link repoDisplayName}: when the caller passes
 * a `ProjectRepoLinkDto`, the link's `.id` is the link UUID — wrong for any
 * subsequent repo-scoped call (`syncRepo`, `installRepoWebhook`,
 * `unlinkProjectRepo`). Prefer the inner repo UUID in that order:
 *   1. top-level `repoId` (already a repo-id alias)
 *   2. nested `repo.id` (ProjectRepoLinkDto)
 *   3. top-level `id` (ProjectRepoDto — already a repo UUID)
 */
export function repoIdFor(r: unknown): string {
  const rec = (r ?? {}) as {
    repoId?: string | null;
    id?: string | null;
    repo?: {id?: string | null} | null;
  };
  return String(rec.repoId || rec.repo?.id || rec.id || '');
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
  if (title) return condenseTitle(title);
  const spec = rec.userRequest?.trim();
  if (spec) return condenseTitle(spec);
  const id = rec.runId || rec.id;
  if (id) return `Run R-${String(id).slice(0, 6)}`;
  return 'Untitled run';
}

/**
 * RL-2026-06-019 UX wave — proper display casing for provider enums. The raw
 * values leaked into customer UI as "anthropic" / "Openrouter" / "Openai"
 * (review/11 defect #6).
 */
const PROVIDER_LABELS: Record<string, string> = {
  ANTHROPIC: 'Anthropic',
  OPENAI: 'OpenAI',
  GEMINI: 'Gemini',
  GOOGLE: 'Gemini',
  OPENROUTER: 'OpenRouter',
  DEEPSEEK: 'DeepSeek',
  FIREWORKS: 'Fireworks',
};
export function providerLabel(p?: string | null): string {
  if (!p) return '';
  const key = p.toUpperCase();
  return PROVIDER_LABELS[key] ?? key.charAt(0) + key.slice(1).toLowerCase();
}

/**
 * RL-2026-06-019 UX wave — humanize pipeline_run.status_reason for customer UI
 * (the raw enum, e.g. CLEAN_COMPLETION, leaked onto the result header —
 * review/11 defect #6). Returns '' for reasons not worth showing.
 */
const STATUS_REASON_LABELS: Record<string, string> = {
  CLEAN_COMPLETION: 'Clean completion',
  CLEAN_WITH_INTERMEDIATE_WARNINGS: 'Completed — intermediate warnings',
  FINAL_BUILD_FAILED: 'Final build failed',
  REVIEWER_UNRESOLVED: 'Reviewer found unresolved issues',
  REVIEWER_FAILED: 'Reviewer pass failed',
  ALL_TASKS_FAILED: 'All tasks failed',
  LEGACY_UNKNOWN: '',
};
export function humanStatusReason(reason?: string | null): string {
  if (!reason) return '';
  const tests = /^TESTS_FAILED:(\d+)_OF_(\d+)$/.exec(reason);
  if (tests) return `Tests failed (${tests[1]} of ${tests[2]})`;
  if (reason in STATUS_REASON_LABELS) return STATUS_REASON_LABELS[reason];
  const words = reason.replace(/_/g, ' ').toLowerCase();
  return words.charAt(0).toUpperCase() + words.slice(1);
}

// SWE-bench / harness specs embed multi-paragraph markdown (failing-test output,
// gold-patch diff). On list views that pasted into the row title cell, making
// rows indistinguishable. Pick the first non-blank line, strip the leading
// markdown headers / list markers / code fences, cap at 80 chars.
function condenseTitle(raw: string): string {
  const firstLine =
    raw
      .split(/\r?\n/)
      .map((l) => l.trim())
      .find((l) => l.length > 0 && !/^```/.test(l)) || raw.trim();
  const stripped = firstLine
    .replace(/^#{1,6}\s+/, '') // markdown headings
    .replace(/^[-*+>]\s+/, '') // list / blockquote markers
    .replace(/^\d+\.\s+/, '') // ordered list
    .trim();
  return stripped.length > 80 ? stripped.slice(0, 77).trimEnd() + '…' : stripped;
}
