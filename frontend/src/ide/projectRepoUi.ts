export type RepoBadge = {kind: 'ok' | 'warn' | 'bad'; label: string};

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

export function normalizeGitHubRepoQuery(input: string): string {
  return input
    .trim()
    .replace(/^https?:\/\/github\.com\//i, '')
    .replace(/\.git$/i, '')
    .replace(/^\/+|\/+$/g, '');
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
