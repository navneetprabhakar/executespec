import { useEffect, useState } from 'react';
import * as api from '@/lib/api';
import type { ProjectRepo, RepoScope } from '@/types/project';

interface Props {
  projectId: string;
  isOrgAdmin: boolean;
  alreadyLinkedRepoIds: Set<string>;
  onClose: () => void;
  onLinked: () => void;
}

/**
 * Two paths in a single modal:
 *  1. Pick an existing ORG / USER repo from the available catalogue and link it.
 *  2. Create a new repo at chosen scope, then link it.
 */
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

  // Create-form state
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
      className="fixed inset-0 bg-black/40 flex items-center justify-center z-50"
      onClick={onClose}
    >
      <div
        className="bg-white rounded-lg w-full max-w-lg p-6 shadow-xl"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between mb-4">
          <h2 className="text-lg font-semibold">Add repository</h2>
          <button onClick={onClose} className="text-gray-400 hover:text-gray-700">×</button>
        </div>

        <nav className="flex gap-4 border-b mb-4 text-sm">
          <button
            onClick={() => setTab('pick')}
            className={`pb-2 border-b-2 ${tab === 'pick' ? 'border-blue-600 text-blue-700' : 'border-transparent text-gray-600'}`}
          >
            Pick existing
          </button>
          <button
            onClick={() => setTab('create')}
            className={`pb-2 border-b-2 ${tab === 'create' ? 'border-blue-600 text-blue-700' : 'border-transparent text-gray-600'}`}
          >
            Create new
          </button>
        </nav>

        {error && <div className="mb-3 text-sm text-red-600">{error}</div>}

        {tab === 'pick' && (
          <div className="max-h-80 overflow-y-auto space-y-2">
            {loading && <div className="text-sm text-gray-500">Loading…</div>}
            {!loading && pickable.length === 0 && (
              <div className="text-sm text-gray-500">
                No available repos. Switch to "Create new" to register one.
              </div>
            )}
            {pickable.map((r) => (
              <div
                key={r.id}
                className="flex items-center justify-between border rounded p-2"
              >
                <div className="min-w-0">
                  <div className="font-mono text-sm truncate">{r.githubRepoFullName}</div>
                  <div className="text-xs text-gray-500">scope: {r.scope}</div>
                </div>
                <button
                  onClick={() => link(r.id)}
                  className="text-xs px-2 py-1 bg-blue-600 text-white rounded hover:bg-blue-700"
                >
                  Link
                </button>
              </div>
            ))}
          </div>
        )}

        {tab === 'create' && (
          <div className="space-y-3">
            <div>
              <label className="block text-xs font-medium text-gray-700 mb-1">Scope</label>
              <div className="flex gap-2">
                <button
                  type="button"
                  onClick={() => setScope('USER')}
                  className={`flex-1 px-3 py-2 text-sm rounded border ${scope === 'USER' ? 'bg-amber-50 border-amber-400' : 'bg-white'}`}
                >
                  User (private to me)
                </button>
                <button
                  type="button"
                  disabled={!isOrgAdmin}
                  onClick={() => setScope('ORG')}
                  className={`flex-1 px-3 py-2 text-sm rounded border ${scope === 'ORG' ? 'bg-purple-50 border-purple-400' : 'bg-white'} ${!isOrgAdmin ? 'opacity-50 cursor-not-allowed' : ''}`}
                  title={isOrgAdmin ? '' : 'Only ORG_ADMIN can create org-scoped repos'}
                >
                  Organization (shared)
                </button>
              </div>
            </div>
            <div>
              <label className="block text-xs font-medium text-gray-700 mb-1">
                GitHub repo (owner/name)
              </label>
              <input
                type="text"
                value={fullName}
                onChange={(e) => setFullName(e.target.value)}
                placeholder="acme/api-server"
                className="w-full px-2 py-1 text-sm border rounded font-mono"
              />
            </div>
            <div>
              <label className="block text-xs font-medium text-gray-700 mb-1">
                Default branch
              </label>
              <input
                type="text"
                value={defaultBranch}
                onChange={(e) => setDefaultBranch(e.target.value)}
                className="w-full px-2 py-1 text-sm border rounded"
              />
            </div>
            <div className="flex justify-end gap-2 pt-2">
              <button onClick={onClose} className="text-sm px-3 py-1 border rounded">
                Cancel
              </button>
              <button
                onClick={create}
                className="text-sm px-3 py-1 bg-blue-600 text-white rounded hover:bg-blue-700"
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
