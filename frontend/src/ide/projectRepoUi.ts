import type {ProjectCodeIndexReadiness, ProjectCodeIndexRefreshAccepted} from '@/types/project';

export type RepoBadge = {kind: 'ok' | 'warn' | 'bad'; label: string};
const BITBUCKET_READ_CAPABILITIES = new Set([
  'CATALOG',
  'ATTACH',
  'SYNC',
  'SOURCE_READINESS',
  'SOURCE_BROWSE',
]);

export function repoIdFromUnknown(value: unknown): string {
  const row = (value || {}) as {
    repoId?: string | null;
    id?: string | null;
    repo?: {id?: string | null} | null;
  };
  return String(row.repoId || row.repo?.id || row.id || '');
}

export function explicitLinkedRepoSelection(rows: unknown[]): string[] {
  return rows.map(repoIdFromUnknown).filter(Boolean);
}

export function normalizeScmRepoQuery(input: string, provider: 'GITHUB' | 'BITBUCKET'): string {
  const host = provider === 'BITBUCKET' ? 'bitbucket.org' : 'github.com';
  return input
    .trim()
    .replace(new RegExp(`^https?://${host.replace('.', '\\.')}\\/`, 'i'), '')
    .replace(/\.git$/i, '')
    .replace(/^\/+|\/+$/g, '');
}

/** Backward-compatible helper retained for older imports/tests. */
export function normalizeGitHubRepoQuery(input: string): string {
  return normalizeScmRepoQuery(input, 'GITHUB');
}

export function repoSupports(repo: unknown, capability: string): boolean {
  const row = ((repo || {}) as {repo?: unknown}).repo || repo || {};
  const value = row as {sourceCapabilities?: string[] | null; scmProvider?: string | null};
  const provider = String(value.scmProvider || 'GITHUB').toUpperCase();
  if (provider === 'BITBUCKET' && !BITBUCKET_READ_CAPABILITIES.has(capability)) return false;
  if (Array.isArray(value.sourceCapabilities) && value.sourceCapabilities.length > 0) {
    return value.sourceCapabilities.includes(capability);
  }
  // Legacy rows predate capability persistence. Preserve GitHub behavior, fail closed otherwise.
  return provider === 'GITHUB';
}

export function syncBadge(status?: string | null): RepoBadge {
  const normalized = String(status || 'UNKNOWN').toUpperCase();
  if (normalized === 'IN_SYNC') return {kind: 'ok', label: 'in sync'};
  if (normalized === 'DRIFTED') return {kind: 'warn', label: 'drifted'};
  return {kind: 'warn', label: 'sync unknown'};
}

export function sourceReadinessBadge(
  status?: string | null,
  reason?: string | null,
  fileCount?: number | null,
): RepoBadge {
  const normalized = String(status || 'UNKNOWN').toUpperCase();
  if (normalized === 'READY') {
    return {
      kind: 'ok',
      label: `source ready${fileCount == null ? '' : ` · ${fileCount} files`}`,
    };
  }
  if (normalized === 'BLOCKED') {
    return {kind: 'bad', label: `source blocked · ${reason || 'check access'}`};
  }
  return {kind: 'warn', label: 'source not verified'};
}

/**
 * Translate the refresh admission result without confusing HTTP 202 with provider work actually
 * being queued. Exact backend reason codes remain visible because they are the actionable routing
 * evidence (for example, an embedding provider is unavailable for the selected project route).
 */
export function codeIndexRefreshNotice(result: ProjectCodeIndexRefreshAccepted): string {
  const reason = result.reason ? ` · ${result.reason}` : '';
  switch (result.status) {
    case 'READY':
      return 'Semantic index is already ready.';
    case 'PARTIAL':
      return `Semantic index remains partial${reason}. Ripgrep search remains available.`;
    case 'UNAVAILABLE':
      return `Semantic indexing unavailable${reason}. Ripgrep search remains available.`;
    case 'QUEUED':
      return `Semantic indexing queued${reason}. Ripgrep search remains available.`;
    default:
      return `Semantic indexing status unavailable${reason}. Ripgrep search remains available.`;
  }
}

export function semanticIndexBadgeLabel(readiness: ProjectCodeIndexReadiness): string {
  const status = readiness.status.toLowerCase();
  return readiness.status === 'UNINDEXED'
    ? `semantic ${status}`
    : `semantic ${status} · ${readiness.coveragePercent.toFixed(0)}%`;
}

export function semanticCoverageLabel(readiness: ProjectCodeIndexReadiness): string {
  if (readiness.status === 'UNINDEXED') return 'Not measured — index not built';
  return `${readiness.indexedFiles}/${readiness.eligibleFiles} files · ${readiness.coveragePercent.toFixed(1)}%`;
}

export function codeSearchCapabilitiesLabel(readiness: ProjectCodeIndexReadiness): string {
  return [
    readiness.lexicalFallbackAvailable ? 'ripgrep available' : 'ripgrep unavailable',
    readiness.relationshipGraphAvailable
      ? 'relationship graph available'
      : 'relationship graph not available',
  ].join(' · ');
}
