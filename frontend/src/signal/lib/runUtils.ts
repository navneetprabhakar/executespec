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
  const score = (r as unknown as {verifiedScore?: number; accuracyScore?: number}).verifiedScore ??
    (r as unknown as {accuracyScore?: number}).accuracyScore ?? null;
  return {
    id,
    short: id ? `R-${id.slice(0, 6)}` : 'R-—',
    status,
    isLive: isLiveStatus(status),
    isTerminal: isTerminalStatus(status),
    title: (r as unknown as {runTitle?: string; userRequest?: string}).runTitle ||
      r.userRequest || `Run R-${id.slice(0, 6)}`,
    projectName: (r as unknown as {projectName?: string | null}).projectName ?? null,
    projectId: (r as unknown as {projectId?: string | null}).projectId ?? null,
    startedAt: r.startedAt ?? null,
    completedAt: r.completedAt ?? null,
    cost: (r as unknown as {actualCostUsd?: number | null}).actualCostUsd ?? null,
    score,
    prUrl: (r as unknown as {scmPrUrl?: string | null}).scmPrUrl ??
      (r as unknown as {githubPrUrl?: string | null}).githubPrUrl ?? null,
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
