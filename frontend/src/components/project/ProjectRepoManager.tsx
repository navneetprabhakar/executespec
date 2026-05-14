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
      {/* 2026-05-14 UX redesign — title + action header.
          Hierarchy: H2 (16px medium) is a section heading, NOT a hero. Sits
          inline with a tight action cluster. The two actions are PEER
          operations (Reset workspace / Add repository) so they share the
          same button size + shape + height. Add is brand-accent because it's
          the primary creative action; Reset is ghost because it's
          destructive-leaning. On phone the cluster wraps below the title
          and both buttons share the row 50/50 via `flex-1`. */}
      <div className='flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3'>
        <div>
          <h2 className='text-[15px] sm:text-base font-semibold text-primary leading-tight'>
            Linked repositories
          </h2>
          <p className='mt-0.5 text-[11.5px] text-muted'>
            {repos.length === 0
              ? 'No repos yet · add one to start launching'
              : `${repos.length} ${repos.length === 1 ? 'repo' : 'repos'} linked`}
          </p>
        </div>
        <div className='flex items-center gap-2 w-full sm:w-auto'>
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
                'press-effect inline-flex items-center justify-center text-[12.5px] font-medium h-9 px-3 rounded-md border border-[var(--border-default)] whitespace-nowrap flex-1 sm:flex-none ' +
                (!isOrgAdmin
                  ? 'text-muted opacity-50 cursor-not-allowed'
                  : 'text-secondary hover:text-primary hover:border-[var(--border-hover)] hover:bg-[var(--surface-raised)]')
              }
            >
              Reset workspace
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
              'press-effect inline-flex items-center justify-center gap-1 text-[12.5px] font-semibold h-9 px-3 rounded-md whitespace-nowrap flex-1 sm:flex-none ' +
              (!isOrgAdmin
                ? 'bg-accent-blue/30 text-white opacity-60 cursor-not-allowed'
                : 'bg-accent-blue text-white hover:bg-[var(--accent-blue-hover)]')
            }
          >
            <span aria-hidden='true'>+</span> Add repository
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
