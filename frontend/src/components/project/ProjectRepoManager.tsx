import {useEffect, useMemo, useState} from 'react';
import {FolderGit2} from 'lucide-react';
import * as api from '@/lib/api';
import {useProjectStore} from '@/stores/useProjectStore';
import {useAuthStore} from '@/stores/useAuthStore';
import RepoCard from './RepoCard';
import AddRepoModal from './AddRepoModal';
import ResetWorkspaceSheet from './ResetWorkspaceSheet';
import EmptyState from '@/components/shared/EmptyState';

export default function ProjectRepoManager({projectId}: {projectId: string}) {
  const {repos, fetchRepos, error} = useProjectStore();
  const me = useAuthStore((s) => s.user);
  const [showAdd, setShowAdd] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);
  // v2.5.3 (UX-4.1 polish): reset now goes through a confirmation sheet
  // that previews per-repo SHA movement before running analyse.
  const [showResetSheet, setShowResetSheet] = useState(false);

  useEffect(() => {
    fetchRepos(projectId);
  }, [projectId, fetchRepos]);

  // Listen for global open-add-repo-modal events so deep-link entry points
  // (e.g. EmptyProjectLanding's "Link a repo" button) can pop the modal
  // without prop-drilling through ProjectDashboardPage → CodeTab → here.
  useEffect(() => {
    const handler = () => setShowAdd(true);
    window.addEventListener('open-add-repo-modal', handler);
    return () => window.removeEventListener('open-add-repo-modal', handler);
  }, []);

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
    <div className='space-y-4'>
      <div className='flex items-center justify-between'>
        <h2 className='text-lg font-medium'>Linked repositories</h2>
        <div className='flex items-center gap-2'>
          {/* P-25 (UX audit, 2026-05-03) — Reset + Add are admin-only on the
              backend. Pre-fix the buttons rendered live for everyone, clicked
              by DEVELOPER/VIEWER, returned 403. Now disable + tooltip the
              cause so non-admins see a consistent "this is admin only"
              treatment instead of a backend error. */}
          {repos.length > 0 && (
            <button
              onClick={() => isOrgAdmin && setShowResetSheet(true)}
              disabled={!isOrgAdmin}
              title={
                !isOrgAdmin
                  ? 'Only org admins can reset the workspace. Ask an admin.'
                  : 'Preview SHA movement per repo, then pull origin HEAD + regenerate codemaps/architecture'
              }
              className={
                !isOrgAdmin
                  ? 'text-sm px-3 py-1.5 rounded border border-[var(--border-default)] text-muted opacity-50 cursor-not-allowed'
                  : 'text-sm px-3 py-1.5 rounded border border-[var(--border-default)] text-muted hover:text-primary'
              }
            >
              Reset workspace…
            </button>
          )}
          <button
            onClick={() => isOrgAdmin && setShowAdd(true)}
            disabled={!isOrgAdmin}
            title={
              !isOrgAdmin
                ? 'Only org admins can add repositories. Ask an admin to link this repo for you.'
                : 'Link an existing GitHub repo or create a new one'
            }
            className={
              !isOrgAdmin
                ? 'text-sm px-3 py-1.5 bg-blue-600 text-white rounded opacity-50 cursor-not-allowed'
                : 'text-sm px-3 py-1.5 bg-blue-600 text-white rounded hover:bg-blue-700'
            }
          >
            + Add repository
          </button>
        </div>
      </div>

      {error && <div className='text-sm text-status-error'>{error}</div>}

      {repos.length === 0 && (
        <EmptyState
          icon={FolderGit2}
          title='No repositories linked yet'
          description='Click "Add repository" above to link an existing repo or create a new one.'
        />
      )}

      <div className='space-y-3'>
        {repos.map((link) => (
          <div
            key={link.id}
            className={busy === link.repoId ? 'opacity-50 pointer-events-none' : ''}
          >
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

      {showResetSheet && (
        <ResetWorkspaceSheet
          projectId={projectId}
          onClose={() => setShowResetSheet(false)}
          onDone={() => fetchRepos(projectId)}
        />
      )}

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
