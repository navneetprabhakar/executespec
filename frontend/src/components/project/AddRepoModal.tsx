import { useEffect, useState } from 'react';
import { X } from 'lucide-react';
import * as api from '@/lib/api';
import type { ProjectRepo, RepoScope } from '@/types/project';
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
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [scope, setScope] = useState<RepoScope>('USER');
  const [fullName, setFullName] = useState('');
  const [defaultBranch, setDefaultBranch] = useState('main');

  useEffect(() => {
    setLoading(true);
    api
      .listRepos('ALL')
      .then(setAvailable)
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  }, []);

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

  const pickable = available.filter((r) => !alreadyLinkedRepoIds.has(r.id));

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
          <div className="max-h-80 overflow-y-auto space-y-1.5">
            {loading && <div className="text-[11px] text-muted">Loading…</div>}
            {!loading && pickable.length === 0 && (
              <div className="text-[11px] text-muted">
                No available repos. Switch to "Create new" to register one.
              </div>
            )}
            {pickable.map((r) => (
              <div
                key={r.id}
                className="flex items-center justify-between rounded-sm border border-[var(--border-default)] bg-[var(--gray-200)] px-2.5 py-1.5"
              >
                <div className="min-w-0">
                  <div className="font-mono text-[11px] truncate text-primary">{r.githubRepoFullName}</div>
                  <div className="text-[10px] text-muted">scope: {r.scope}</div>
                </div>
                <button
                  onClick={() => link(r.id)}
                  className="text-[11px] px-2 py-0.5 border border-accent-blue text-accent-blue rounded-sm hover:bg-accent-blue/10"
                >
                  Link
                </button>
              </div>
            ))}
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
