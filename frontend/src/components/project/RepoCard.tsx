import {useMemo, useState} from 'react';
import {errMsg} from '@/lib/utils';
import type {ProjectRepoLink, StackInfo} from '@/types/project';
import StackBadge from './StackBadge';
import RepoRelationshipEditor from './RepoRelationshipEditor';
import {installRepoWebhook} from '@/lib/api';
import {useUiStore} from '@/stores/useUiStore';

interface Props {
  link: ProjectRepoLink;
  onUnlink: (repoId: string) => void;
  onSync: (repoId: string) => void;
  onUpdateRelationships: (repoId: string, relationships: string[]) => void;
  onRefresh?: () => void | Promise<void>;
}

export default function RepoCard({
  link,
  onUnlink,
  onSync,
  onUpdateRelationships,
  onRefresh,
}: Props) {
  const {repo} = link;
  const [editing, setEditing] = useState(false);
  const [installingHook, setInstallingHook] = useState(false);

  const addToast = useUiStore((s) => s.addToast);
  const doInstallHook = async () => {
    if (installingHook) return;
    setInstallingHook(true);
    try {
      const r = await installRepoWebhook(repo.id);
      if (!r.installed && r.reason) {
        addToast({type: 'error', message: r.reason});
      } else if (r.installed) {
        addToast({type: 'success', message: 'Webhook installed'});
      }
      if (onRefresh) await onRefresh();
    } catch (e) {
      addToast({
        type: 'error',
        message: `Webhook install failed: ${e instanceof Error ? errMsg(e) : String(e)}`,
      });
    } finally {
      setInstallingHook(false);
    }
  };

  const stack = useMemo<StackInfo | null>(() => {
    if (!repo.detectedStack) return null;
    try {
      return JSON.parse(repo.detectedStack) as StackInfo;
    } catch {
      return null;
    }
  }, [repo.detectedStack]);

  const relationships = useMemo<string[]>(() => {
    if (!link.relationships) return [];
    if (Array.isArray(link.relationships)) return link.relationships;
    try {
      const parsed = JSON.parse(link.relationships as unknown as string);
      return Array.isArray(parsed) ? parsed : [];
    } catch {
      return [];
    }
  }, [link.relationships]);

  return (
    <div className='rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] p-3'>
      {/* 2026-05-14 UX feedback — was `flex items-start justify-between gap-4`
          unconditionally. On 375px the right-side action cluster (Sync /
          Install webhook / Unlink) squeezed the meta column to <50% of the
          card and the "webhook: not set up" chip rendered BEHIND the Unlink
          button. Stack vertically on phone (meta first, full-width action
          row below), inline on sm+. */}
      <div className='flex flex-col sm:flex-row sm:items-start sm:justify-between gap-3 sm:gap-4'>
        <div className='min-w-0'>
          <div className='flex items-center gap-2'>
            <span className='font-mono text-xs truncate text-primary'>
              {repo.githubRepoFullName}
            </span>
            <span
              className={`inline-flex items-center rounded-sm px-1.5 py-0.5 text-[11px] ${
                repo.scope === 'ORG'
                  ? 'bg-accent-blue/10 text-accent-blue'
                  : 'bg-accent-amber/10 text-accent-amber'
              }`}
            >
              {repo.scope}
            </span>
          </div>
          <div className='mt-1 flex flex-wrap items-center gap-x-2 gap-y-1 text-[11px] text-muted'>
            <StackBadge stack={stack} />
            <span>branch: {repo.defaultBranch}</span>
            {/* R-322 — surface the customer-declared monorepo project root
                so the user can see at a glance which subdirectory we'll
                treat as project root. */}
            {repo.subdir && (
              <span
                className='inline-flex items-center rounded-sm bg-accent-blue/10 px-1.5 py-0.5 text-[10px] font-mono text-accent-blue'
                title='.executespec/root.yml declares this subdirectory as the project root'
              >
                root: {repo.subdir}
              </span>
            )}
            {/* R-325 — flag big-repo opt-in attaches so the user knows
                runs touching this repo will count more credits. */}
            {repo.attachedAnyway && (
              <span
                className='inline-flex items-center rounded-sm bg-accent-amber/10 px-1.5 py-0.5 text-[10px] text-accent-amber'
                title={`Attached despite the platform size budget${
                  repo.sizeKb ? ` (≈${Math.round(repo.sizeKb / 1024)} MB)` : ''
                }. Runs touching this repo count more credits.`}
              >
                big repo · counts more credits
              </span>
            )}
            {repo.lastSyncedSha && <span>· last sync {repo.lastSyncedSha.slice(0, 7)}</span>}
            <span
              className={`inline-flex items-center rounded-sm px-1.5 py-0.5 text-[9px] ${
                repo.webhookInstalled
                  ? 'bg-status-success/10 text-status-success'
                  : 'bg-[var(--gray-400)]/50 text-muted'
              }`}
              title={
                repo.webhookInstalled
                  ? 'Webhook installed — PR merges trigger post-merge cascade'
                  : 'Optional — install to auto-refresh architecture/memory on PR merge'
              }
            >
              {repo.webhookInstalled ? 'webhook ✓' : 'webhook: not set up'}
            </span>
          </div>
        </div>
        <div className='flex flex-wrap gap-1.5 sm:shrink-0'>
          <button
            onClick={() => onSync(repo.id)}
            className='press-effect text-[11px] px-2.5 py-1 rounded-sm border border-[var(--border-default)] text-muted hover:text-primary'
          >
            Sync
          </button>
          {!repo.webhookInstalled && (
            <button
              onClick={doInstallHook}
              disabled={installingHook}
              title='Install a pull_request webhook using your GitHub PAT (requires admin:repo_hook)'
              className='press-effect text-[11px] px-2.5 py-1 rounded-sm border border-accent-amber/40 text-accent-amber hover:bg-accent-amber/10 disabled:opacity-50'
            >
              {installingHook ? 'Installing…' : 'Install webhook'}
            </button>
          )}
          <button
            onClick={() => onUnlink(repo.id)}
            className='press-effect text-[11px] px-2.5 py-1 rounded-sm border border-accent-red/40 text-accent-red hover:bg-accent-red/10 ml-auto sm:ml-0'
          >
            Unlink
          </button>
        </div>
      </div>

      <div className='mt-2'>
        {editing ? (
          <RepoRelationshipEditor
            initial={relationships}
            onSave={(next) => {
              onUpdateRelationships(repo.id, next);
              setEditing(false);
            }}
            onCancel={() => setEditing(false)}
          />
        ) : (
          <div className='flex flex-wrap items-center gap-1.5'>
            {relationships.length === 0 && (
              <span className='text-[11px] text-muted'>no relationships</span>
            )}
            {relationships.map((r) => (
              <span
                key={r}
                className='inline-flex items-center rounded-sm bg-[var(--gray-200)] px-1.5 py-0.5 text-[11px] text-secondary'
              >
                {r}
              </span>
            ))}
            <button
              onClick={() => setEditing(true)}
              className='text-[11px] text-accent-blue hover:underline'
            >
              edit
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
