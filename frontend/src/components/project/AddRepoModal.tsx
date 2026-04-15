import { useEffect, useMemo, useState } from 'react';
import { X, Lock, RefreshCw } from 'lucide-react';
import * as api from '@/lib/api';
import type { ProjectRepo, RepoScope } from '@/types/project';
import type { GitHubRemoteRepo } from '@/lib/api';
import { cn } from '@/lib/utils';

interface Props {
  projectId: string;
  isOrgAdmin: boolean;
  alreadyLinkedRepoIds: Set<string>;
  onClose: () => void;
  onLinked: () => void;
}

export default function AddRepoModal({
  projectId,
  isOrgAdmin,
  alreadyLinkedRepoIds,
  onClose,
  onLinked,
}: Props) {
  const [tab, setTab] = useState<'pick' | 'create'>('pick');
  const [available, setAvailable] = useState<ProjectRepo[]>([]);
  const [remote, setRemote] = useState<GitHubRemoteRepo[]>([]);
  const [filter, setFilter] = useState('');
  const [ownerOverride, setOwnerOverride] = useState('');
  const [loading, setLoading] = useState(false);
  const [refreshingRemote, setRefreshingRemote] = useState(false);
  const [linkingFullName, setLinkingFullName] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const [scope, setScope] = useState<RepoScope>('USER');
  const [fullName, setFullName] = useState('');
  const [defaultBranch, setDefaultBranch] = useState('main');

  const loadAll = async (owner?: string) => {
    setLoading(true);
    setError(null);
    try {
      const [registered, fromGitHub] = await Promise.all([
        api.listRepos('ALL'),
        api.listGitHubRepos(owner).catch((e) => {
          setError(`GitHub: ${e instanceof Error ? e.message : String(e)}`);
          return [] as GitHubRemoteRepo[];
        }),
      ]);
      setAvailable(registered);
      setRemote(fromGitHub);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadAll();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const refreshRemote = async () => {
    setRefreshingRemote(true);
    setError(null);
    try {
      const fromGitHub = await api.listGitHubRepos(ownerOverride.trim() || undefined);
      setRemote(fromGitHub);
    } catch (e) {
      setError(`GitHub: ${e instanceof Error ? e.message : String(e)}`);
    } finally {
      setRefreshingRemote(false);
    }
  };

  const link = async (repoId: string) => {
    try {
      await api.linkProjectRepo(projectId, repoId);
      onLinked();
      onClose();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to link repo');
    }
  };

  const create = async () => {
    setError(null);
    if (!fullName.includes('/')) {
      setError('Repo name must be "owner/repo"');
      return;
    }
    try {
      const repo = await api.createRepo({ scope, githubRepoFullName: fullName, defaultBranch });
      await api.linkProjectRepo(projectId, repo.id);
      onLinked();
      onClose();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to create repo');
    }
  };

  const linkRemote = async (r: GitHubRemoteRepo) => {
    setError(null);
    setLinkingFullName(r.fullName);
    try {
      // Reuse an existing registration if present, otherwise register at USER scope
      // (default — least privilege; ORG scope must be done explicitly via Create new).
      const existing = available.find(
        (a) => a.githubRepoFullName.toLowerCase() === r.fullName.toLowerCase(),
      );
      const repoId = existing?.id ?? (
        await api.createRepo({
          scope: 'USER',
          githubRepoFullName: r.fullName,
          defaultBranch: r.defaultBranch || 'main',
        })
      ).id;
      await api.linkProjectRepo(projectId, repoId);
      onLinked();
      onClose();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to link repo');
    } finally {
      setLinkingFullName(null);
    }
  };

  // Merge view: registered repos + remote GitHub repos, excluding already-linked
  type PickRow =
    | { kind: 'registered'; repo: ProjectRepo }
    | { kind: 'remote'; repo: GitHubRemoteRepo };

  const linkedFullNames = useMemo(() => {
    const names = new Set<string>();
    available.forEach((a) => {
      if (alreadyLinkedRepoIds.has(a.id)) names.add(a.githubRepoFullName.toLowerCase());
    });
    return names;
  }, [available, alreadyLinkedRepoIds]);

  const rows: PickRow[] = useMemo(() => {
    const seen = new Set<string>();
    const out: PickRow[] = [];
    for (const a of available) {
      if (alreadyLinkedRepoIds.has(a.id)) continue;
      const key = a.githubRepoFullName.toLowerCase();
      if (seen.has(key)) continue;
      seen.add(key);
      out.push({ kind: 'registered', repo: a });
    }
    for (const r of remote) {
      const key = r.fullName.toLowerCase();
      if (seen.has(key) || linkedFullNames.has(key)) continue;
      seen.add(key);
      out.push({ kind: 'remote', repo: r });
    }
    const f = filter.trim().toLowerCase();
    if (!f) return out;
    return out.filter((row) => {
      const name = row.kind === 'registered' ? row.repo.githubRepoFullName : row.repo.fullName;
      return name.toLowerCase().includes(f);
    });
  }, [available, remote, alreadyLinkedRepoIds, linkedFullNames, filter]);

  return (
    <div
      className="fixed inset-0 bg-black/60 flex items-center justify-center z-50"
      onClick={onClose}
    >
      <div
        className="rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] w-full max-w-lg p-4 shadow-xl"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between mb-3">
          <h2 className="text-[12px] font-medium text-primary">Add repository</h2>
          <button onClick={onClose} className="text-muted hover:text-primary">
            <X className="h-3.5 w-3.5" />
          </button>
        </div>

        <nav className="flex gap-4 border-b border-[var(--border-default)] mb-3 text-[11px]">
          <button
            onClick={() => setTab('pick')}
            className={cn(
              'pb-1.5 border-b-2 transition-colors',
              tab === 'pick' ? 'border-accent-blue text-accent-blue' : 'border-transparent text-muted hover:text-secondary',
            )}
          >
            Pick existing
          </button>
          <button
            onClick={() => setTab('create')}
            className={cn(
              'pb-1.5 border-b-2 transition-colors',
              tab === 'create' ? 'border-accent-blue text-accent-blue' : 'border-transparent text-muted hover:text-secondary',
            )}
          >
            Create new
          </button>
        </nav>

        {error && <div className="mb-2 text-[11px] text-accent-red">{error}</div>}

        {tab === 'pick' && (
          <div className="space-y-2">
            <div className="flex gap-2">
              <input
                type="text"
                value={filter}
                onChange={(e) => setFilter(e.target.value)}
                placeholder="Filter…"
                className="flex-1 px-2 py-1 text-[11px] rounded-sm border border-[var(--border-default)] bg-[var(--gray-200)] text-primary placeholder:text-muted focus:outline-none focus:border-accent-blue"
              />
              <input
                type="text"
                value={ownerOverride}
                onChange={(e) => setOwnerOverride(e.target.value)}
                placeholder="owner / org"
                className="w-28 px-2 py-1 text-[11px] font-mono rounded-sm border border-[var(--border-default)] bg-[var(--gray-200)] text-primary placeholder:text-muted focus:outline-none focus:border-accent-blue"
                title="Override the owner used for the GitHub query (defaults to your saved owner)"
              />
              <button
                onClick={refreshRemote}
                disabled={refreshingRemote}
                className="inline-flex items-center gap-1 text-[11px] px-2 py-1 rounded-sm border border-[var(--border-default)] text-muted hover:text-primary disabled:opacity-50"
                title="Re-fetch repos from GitHub using your saved PAT"
              >
                <RefreshCw className={cn('h-3 w-3', refreshingRemote && 'animate-spin')} />
                Refresh
              </button>
            </div>
            <div className="max-h-80 overflow-y-auto space-y-1.5">
              {loading && <div className="text-[11px] text-muted">Loading…</div>}
              {!loading && rows.length === 0 && (
                <div className="text-[11px] text-muted">
                  No repos visible. Set a GitHub PAT + owner under Settings → GitHub, then Refresh. Or switch to "Create new" to register a repo by name.
                </div>
              )}
              {rows.map((row) => {
                const name = row.kind === 'registered' ? row.repo.githubRepoFullName : row.repo.fullName;
                const isPrivate = row.kind === 'remote' ? row.repo.isPrivate : false;
                const branch = row.kind === 'registered' ? row.repo.defaultBranch : row.repo.defaultBranch;
                const busy = linkingFullName === name;
                return (
                  <div
                    key={`${row.kind}:${name}`}
                    className="flex items-center justify-between rounded-sm border border-[var(--border-default)] bg-[var(--gray-200)] px-2.5 py-1.5"
                  >
                    <div className="min-w-0 flex items-center gap-2">
                      <div className="min-w-0">
                        <div className="font-mono text-[11px] truncate text-primary flex items-center gap-1">
                          {name}
                          {isPrivate && <Lock className="h-2.5 w-2.5 text-muted" />}
                        </div>
                        <div className="text-[10px] text-muted">
                          {row.kind === 'registered'
                            ? `registered · ${row.repo.scope} · branch: ${branch}`
                            : `from GitHub · branch: ${branch}`}
                        </div>
                      </div>
                    </div>
                    <button
                      onClick={() =>
                        row.kind === 'registered' ? link(row.repo.id) : linkRemote(row.repo)
                      }
                      disabled={busy}
                      className="text-[11px] px-2 py-0.5 border border-accent-blue text-accent-blue rounded-sm hover:bg-accent-blue/10 disabled:opacity-50"
                    >
                      {busy ? '…' : 'Link'}
                    </button>
                  </div>
                );
              })}
            </div>
          </div>
        )}

        {tab === 'create' && (
          <div className="space-y-2.5">
            <div>
              <label className="block text-[10px] uppercase tracking-wide text-muted mb-1">Scope</label>
              <div className="flex gap-2">
                <button
                  type="button"
                  onClick={() => setScope('USER')}
                  className={cn(
                    'flex-1 px-2.5 py-1.5 text-[11px] rounded-sm border transition-colors',
                    scope === 'USER'
                      ? 'border-accent-amber text-accent-amber bg-accent-amber/10'
                      : 'border-[var(--border-default)] text-muted hover:text-secondary',
                  )}
                >
                  User (private)
                </button>
                <button
                  type="button"
                  disabled={!isOrgAdmin}
                  onClick={() => setScope('ORG')}
                  className={cn(
                    'flex-1 px-2.5 py-1.5 text-[11px] rounded-sm border transition-colors',
                    scope === 'ORG'
                      ? 'border-accent-blue text-accent-blue bg-accent-blue/10'
                      : 'border-[var(--border-default)] text-muted hover:text-secondary',
                    !isOrgAdmin && 'opacity-50 cursor-not-allowed',
                  )}
                  title={isOrgAdmin ? '' : 'Only ORG_ADMIN can create org-scoped repos'}
                >
                  Organization (shared)
                </button>
              </div>
            </div>
            <div>
              <label className="block text-[10px] uppercase tracking-wide text-muted mb-1">
                GitHub repo (owner/name)
              </label>
              <input
                type="text"
                value={fullName}
                onChange={(e) => setFullName(e.target.value)}
                placeholder="acme/api-server"
                className="w-full px-2 py-1 text-[11px] font-mono rounded-sm border border-[var(--border-default)] bg-[var(--gray-200)] text-primary placeholder:text-muted focus:outline-none focus:border-accent-blue"
              />
            </div>
            <div>
              <label className="block text-[10px] uppercase tracking-wide text-muted mb-1">
                Default branch
              </label>
              <input
                type="text"
                value={defaultBranch}
                onChange={(e) => setDefaultBranch(e.target.value)}
                className="w-full px-2 py-1 text-[11px] rounded-sm border border-[var(--border-default)] bg-[var(--gray-200)] text-primary focus:outline-none focus:border-accent-blue"
              />
            </div>
            <div className="flex justify-end gap-2 pt-1">
              <button
                onClick={onClose}
                className="text-[11px] px-2.5 py-1 rounded-sm border border-[var(--border-default)] text-muted hover:text-primary"
              >
                Cancel
              </button>
              <button
                onClick={create}
                className="text-[11px] px-2.5 py-1 rounded-sm border border-accent-blue text-accent-blue hover:bg-accent-blue/10"
              >
                Create &amp; link
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
