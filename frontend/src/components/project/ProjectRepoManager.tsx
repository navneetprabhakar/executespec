import { useEffect, useMemo, useState } from 'react';
import * as api from '@/lib/api';
import { useProjectStore } from '@/stores/useProjectStore';
import { useAuthStore } from '@/stores/useAuthStore';
import RepoCard from './RepoCard';
import AddRepoModal from './AddRepoModal';

export default function ProjectRepoManager({ projectId }: { projectId: string }) {
  const { repos, fetchRepos, error } = useProjectStore();
  const me = useAuthStore((s) => s.user);
  const [showAdd, setShowAdd] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);
  const [resetting, setResetting] = useState(false);
  const [resetMsg, setResetMsg] = useState<string | null>(null);

  const handleResetAll = async () => {
    if (resetting) return;
    setResetting(true);
    setResetMsg(null);
    try {
      // "Reset workspace" for the project = re-pull every repo + regenerate
      // codemaps + architecture so the next Launch starts from current origin HEAD.
      // `analyseProject` already does all of that in one call.
      await api.analyseProject(projectId);
      setResetMsg('Workspace refreshed — codemaps & architecture regenerated.');
      await fetchRepos(projectId);
    } catch (e) {
      setResetMsg(`Reset failed: ${e instanceof Error ? e.message : String(e)}`);
    } finally {
      setResetting(false);
    }
  };

  useEffect(() => {
    fetchRepos(projectId);
  }, [projectId, fetchRepos]);

  const isOrgAdmin = useMemo(() => {
    const role = me?.orgRole;
    return role === 'ORG_ADMIN' || me?.globalRole === 'SUPER_ADMIN';
  }, [me]);

  const linkedIds = useMemo(() => new Set(repos.map((l) => l.repoId)), [repos]);

  const handleUnlink = async (repoId: string) => {
    setBusy(repoId);
    try {
      await api.unlinkProjectRepo(projectId, repoId);
      await fetchRepos(projectId);
    } finally {
      setBusy(null);
    }
  };

  const handleSync = async (repoId: string) => {
    setBusy(repoId);
    try {
      await api.syncRepo(repoId);
      await fetchRepos(projectId);
    } finally {
      setBusy(null);
    }
  };

  const handleUpdateRel = async (repoId: string, relationships: string[]) => {
    setBusy(repoId);
    try {
      await api.updateRepoRelationships(projectId, repoId, relationships);
      await fetchRepos(projectId);
    } finally {
      setBusy(null);
    }
  };

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h2 className="text-lg font-medium">Linked repositories</h2>
        <div className="flex items-center gap-2">
          {repos.length > 0 && (
            <button
              onClick={handleResetAll}
              disabled={resetting}
              title="Pull current origin HEAD for every linked repo + regenerate codemaps and architecture"
              className="text-sm px-3 py-1.5 rounded border border-[var(--border-default)] text-muted hover:text-primary disabled:opacity-50"
            >
              {resetting ? 'Refreshing…' : 'Reset workspace'}
            </button>
          )}
          <button
            onClick={() => setShowAdd(true)}
            className="text-sm px-3 py-1.5 bg-blue-600 text-white rounded hover:bg-blue-700"
          >
            + Add repository
          </button>
        </div>
      </div>

      {resetMsg && <div className="text-[11px] text-muted">{resetMsg}</div>}
      {error && <div className="text-sm text-red-600">{error}</div>}

      {repos.length === 0 && (
        <div className="text-sm text-gray-500 border-2 border-dashed rounded-lg p-6 text-center">
          No repositories linked yet. Click "Add repository" to get started.
        </div>
      )}

      <div className="space-y-3">
        {repos.map((link) => (
          <div key={link.id} className={busy === link.repoId ? 'opacity-50 pointer-events-none' : ''}>
            <RepoCard
              link={link}
              onUnlink={handleUnlink}
              onSync={handleSync}
              onUpdateRelationships={handleUpdateRel}
              onRefresh={() => fetchRepos(projectId)}
            />
          </div>
        ))}
      </div>

      {showAdd && (
        <AddRepoModal
          projectId={projectId}
          isOrgAdmin={isOrgAdmin}
          alreadyLinkedRepoIds={linkedIds}
          onClose={() => setShowAdd(false)}
          onLinked={() => fetchRepos(projectId)}
        />
      )}
    </div>
  );
}
