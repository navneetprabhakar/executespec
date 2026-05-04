import {useEffect, useMemo, useState} from 'react';
import {AlertCircle, Lock, RefreshCw} from 'lucide-react';
import * as api from '@/lib/api';
import type {ProjectRepo, RepoScope} from '@/types/project';
import type {GitHubRemoteRepo} from '@/lib/api';
import {useAuthStore} from '@/stores/useAuthStore';
import {cn, errMsg} from '@/lib/utils';
import ResponsiveModal from '@/components/shared/ResponsiveModal';
import Button from '@/components/shared/Button';
import {AddGitHubPatDrawer} from '@/components/runs/InlineCredentialDrawers';

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

  // P-03 (UX audit, 2026-05-03) — distinguish "PAT missing" from "0 repos
  // returned" so the empty list copy can render the inline PAT drawer button
  // (M-008 pattern from NewRunModal) rather than send the user out to
  // Settings → GitHub and back.
  const user = useAuthStore((s) => s.user);
  const hasPat = Boolean(user?.hasGithubPat);
  const [showPatDrawer, setShowPatDrawer] = useState(false);
  const reloadAfterPat = async () => {
    setShowPatDrawer(false);
    // user.hasGithubPat is updated by the drawer through useAuthStore. Refresh
    // the repo list now that the PAT is on file.
    await loadAll();
  };

  const loadAll = async (owner?: string) => {
    setLoading(true);
    setError(null);
    try {
      const [registered, fromGitHub] = await Promise.all([
        api.listRepos('ALL'),
        api.listGitHubRepos(owner).catch((e) => {
          setError(`GitHub: ${e instanceof Error ? errMsg(e) : String(e)}`);
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
      setError(`GitHub: ${e instanceof Error ? errMsg(e) : String(e)}`);
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
      setError(e instanceof Error ? errMsg(e) : 'Failed to link repo');
    }
  };

  const create = async () => {
    setError(null);
    if (!fullName.includes('/')) {
      setError('Repo name must be "owner/repo"');
      return;
    }
    try {
      const repo = await api.createRepo({scope, githubRepoFullName: fullName, defaultBranch});
      await api.linkProjectRepo(projectId, repo.id);
      onLinked();
      onClose();
    } catch (e) {
      setError(e instanceof Error ? errMsg(e) : 'Failed to create repo');
    }
  };

  const linkRemote = async (r: GitHubRemoteRepo) => {
    setError(null);
    setLinkingFullName(r.fullName);
    try {
      const existing = available.find(
        (a) => a.githubRepoFullName.toLowerCase() === r.fullName.toLowerCase(),
      );
      const repoId =
        existing?.id ??
        (
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
      setError(e instanceof Error ? errMsg(e) : 'Failed to link repo');
    } finally {
      setLinkingFullName(null);
    }
  };

  type PickRow = {kind: 'registered'; repo: ProjectRepo} | {kind: 'remote'; repo: GitHubRemoteRepo};

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
      out.push({kind: 'registered', repo: a});
    }
    for (const r of remote) {
      const key = r.fullName.toLowerCase();
      if (seen.has(key) || linkedFullNames.has(key)) continue;
      seen.add(key);
      out.push({kind: 'remote', repo: r});
    }
    const f = filter.trim().toLowerCase();
    if (!f) return out;
    return out.filter((row) => {
      const name = row.kind === 'registered' ? row.repo.githubRepoFullName : row.repo.fullName;
      return name.toLowerCase().includes(f);
    });
  }, [available, remote, alreadyLinkedRepoIds, linkedFullNames, filter]);

  return (
    <ResponsiveModal open={true} onClose={onClose} title='Add repository' maxWidth='max-w-lg'>
      <nav className='flex gap-4 border-b border-[var(--border-default)] mb-3 text-xs'>
        <button
          onClick={() => setTab('pick')}
          className={cn(
            'pb-1.5 border-b-2 transition-colors',
            tab === 'pick'
              ? 'border-accent-blue text-accent-blue'
              : 'border-transparent text-muted hover:text-secondary',
          )}
        >
          Pick existing
        </button>
        <button
          onClick={() => setTab('create')}
          className={cn(
            'pb-1.5 border-b-2 transition-colors',
            tab === 'create'
              ? 'border-accent-blue text-accent-blue'
              : 'border-transparent text-muted hover:text-secondary',
          )}
        >
          Create new
        </button>
      </nav>

      {error && <div className='mb-2 text-xs text-accent-red'>{error}</div>}

      {tab === 'pick' && (
        <div className='space-y-2'>
          <div className='flex gap-2'>
            <input
              type='text'
              value={filter}
              onChange={(e) => setFilter(e.target.value)}
              placeholder='Filter…'
              className='flex-1 px-2 py-1 text-xs rounded-sm border border-[var(--border-default)] bg-[var(--gray-200)] text-primary placeholder:text-muted focus:outline-none focus:border-accent-blue'
            />
            <input
              type='text'
              value={ownerOverride}
              onChange={(e) => setOwnerOverride(e.target.value)}
              placeholder='owner / org'
              className='w-28 px-2 py-1 text-xs font-mono rounded-sm border border-[var(--border-default)] bg-[var(--gray-200)] text-primary placeholder:text-muted focus:outline-none focus:border-accent-blue'
              title='Override the owner used for the GitHub query (defaults to your saved owner)'
            />
            <Button
              variant='tertiary'
              size='sm'
              onClick={refreshRemote}
              disabled={refreshingRemote}
              title='Re-fetch repos from GitHub using your saved PAT'
            >
              <RefreshCw className={cn('h-3 w-3', refreshingRemote && 'animate-spin')} />
              Refresh
            </Button>
          </div>
          <div className='max-h-80 overflow-y-auto space-y-1.5'>
            {/* P-03 — when the user has no GitHub PAT, render the inline
                drawer button RIGHT HERE instead of telling them to go to
                Settings. Same component NewRunModal uses for its own
                missing-PAT path (M-008). */}
            {!hasPat && (
              <div className='flex items-center gap-2 flex-wrap text-xs text-accent-amber/80 bg-accent-amber/[0.07] border border-accent-amber/20 rounded-sm px-3 py-2'>
                <AlertCircle className='h-3.5 w-3.5 flex-shrink-0' />
                <span>No GitHub PAT configured — we can't read your repos without one.</span>
                <button
                  type='button'
                  onClick={() => setShowPatDrawer(true)}
                  className='ml-auto rounded-sm bg-accent-amber/20 text-accent-amber px-2 py-0.5 font-semibold hover:bg-accent-amber/30 transition-colors'
                >
                  Add it now
                </button>
              </div>
            )}
            {loading && <div className='text-xs text-muted'>Loading…</div>}
            {!loading && rows.length === 0 && hasPat && (
              <div className='text-xs text-muted'>
                No repos visible for this owner. Try a different owner above and Refresh, or switch
                to "Create new" to register a repo by name.
              </div>
            )}
            {!loading && rows.length === 0 && !hasPat && (
              <div className='text-xs text-muted'>
                Add your GitHub PAT above to see repos you can link, or switch to "Create new" to
                register a repo by name.
              </div>
            )}
            {rows.map((row) => {
              const name =
                row.kind === 'registered' ? row.repo.githubRepoFullName : row.repo.fullName;
              const isPrivate = row.kind === 'remote' ? row.repo.isPrivate : false;
              const branch =
                row.kind === 'registered' ? row.repo.defaultBranch : row.repo.defaultBranch;
              const busy = linkingFullName === name;
              return (
                <div
                  key={`${row.kind}:${name}`}
                  className='flex items-center justify-between rounded-sm border border-[var(--border-default)] bg-[var(--gray-200)] px-2.5 py-1.5'
                >
                  <div className='min-w-0 flex items-center gap-2'>
                    <div className='min-w-0'>
                      <div className='font-mono text-xs truncate text-primary flex items-center gap-1'>
                        {name}
                        {isPrivate && <Lock className='h-2.5 w-2.5 text-muted' />}
                      </div>
                      <div className='text-[11px] text-muted'>
                        {row.kind === 'registered'
                          ? `registered · ${row.repo.scope} · branch: ${branch}`
                          : `from GitHub · branch: ${branch}`}
                      </div>
                    </div>
                  </div>
                  <Button
                    variant='tertiary'
                    size='sm'
                    onClick={() =>
                      row.kind === 'registered' ? link(row.repo.id) : linkRemote(row.repo)
                    }
                    disabled={busy}
                  >
                    {busy ? '…' : 'Link'}
                  </Button>
                </div>
              );
            })}
          </div>
        </div>
      )}

      {tab === 'create' && (
        <div className='space-y-2.5'>
          <div>
            <label className='block text-[11px] uppercase tracking-wide text-muted mb-1'>
              Scope
            </label>
            <div className='flex gap-2'>
              <Button
                variant={scope === 'USER' ? 'primary' : 'tertiary'}
                size='sm'
                fullWidth
                onClick={() => setScope('USER')}
              >
                User (private)
              </Button>
              <Button
                variant={scope === 'ORG' ? 'primary' : 'tertiary'}
                size='sm'
                fullWidth
                disabled={!isOrgAdmin}
                onClick={() => setScope('ORG')}
                title={isOrgAdmin ? '' : 'Only ORG_ADMIN can create org-scoped repos'}
              >
                Organization (shared)
              </Button>
            </div>
          </div>
          <div>
            <label className='block text-[11px] uppercase tracking-wide text-muted mb-1'>
              GitHub repo (owner/name)
            </label>
            <input
              type='text'
              value={fullName}
              onChange={(e) => setFullName(e.target.value)}
              placeholder='acme/api-server'
              className='w-full px-2 py-1 text-xs font-mono rounded-sm border border-[var(--border-default)] bg-[var(--gray-200)] text-primary placeholder:text-muted focus:outline-none focus:border-accent-blue'
            />
          </div>
          <div>
            <label className='block text-[11px] uppercase tracking-wide text-muted mb-1'>
              Default branch
            </label>
            <input
              type='text'
              value={defaultBranch}
              onChange={(e) => setDefaultBranch(e.target.value)}
              className='w-full px-2 py-1 text-xs rounded-sm border border-[var(--border-default)] bg-[var(--gray-200)] text-primary focus:outline-none focus:border-accent-blue'
            />
          </div>
          <div className='flex justify-end gap-2 pt-1'>
            <Button variant='secondary' size='sm' onClick={onClose}>
              Cancel
            </Button>
            <Button variant='primary' size='sm' onClick={create}>
              Create &amp; link
            </Button>
          </div>
        </div>
      )}
      {/* P-03 inline PAT drawer — closes back into the modal which then
          reloads the repo list from GitHub. */}
      <AddGitHubPatDrawer open={showPatDrawer} onClose={reloadAfterPat} />
    </ResponsiveModal>
  );
}
