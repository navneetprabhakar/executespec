import { useMemo, useState } from 'react';
import type { ProjectRepoLink, StackInfo } from '@/types/project';
import StackBadge from './StackBadge';
import RepoRelationshipEditor from './RepoRelationshipEditor';
import { installRepoWebhook } from '@/lib/api';

interface Props {
  link: ProjectRepoLink;
  onUnlink: (repoId: string) => void;
  onSync: (repoId: string) => void;
  onUpdateRelationships: (repoId: string, relationships: string[]) => void;
  onRefresh?: () => void | Promise<void>;
}

export default function RepoCard({ link, onUnlink, onSync, onUpdateRelationships, onRefresh }: Props) {
  const { repo } = link;
  const [editing, setEditing] = useState(false);
  const [installingHook, setInstallingHook] = useState(false);

  const doInstallHook = async () => {
    if (installingHook) return;
    setInstallingHook(true);
    try {
      const r = await installRepoWebhook(repo.id);
      if (!r.installed && r.reason) alert(r.reason);
      if (onRefresh) await onRefresh();
    } catch (e) {
      alert(`Webhook install failed: ${e instanceof Error ? e.message : String(e)}`);
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
    <div className="rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] p-3">
      <div className="flex items-start justify-between gap-4">
        <div className="min-w-0">
          <div className="flex items-center gap-2">
            <span className="font-mono text-xs truncate text-primary">{repo.githubRepoFullName}</span>
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
          <div className="mt-1 flex items-center gap-2 text-[11px] text-muted">
            <StackBadge stack={stack} />
            <span>branch: {repo.defaultBranch}</span>
            {/* R-322 — surface the customer-declared monorepo project root
                so the user can see at a glance which subdirectory we'll
                treat as project root. */}
            {repo.subdir && (
              <span
                className="inline-flex items-center rounded-sm bg-accent-blue/10 px-1.5 py-0.5 text-[10px] font-mono text-accent-blue"
                title=".executespec/root.yml declares this subdirectory as the project root"
              >
                root: {repo.subdir}
              </span>
            )}
            {repo.lastSyncedSha && (
              <span>· last sync {repo.lastSyncedSha.slice(0, 7)}</span>
            )}
            <span
              className={`inline-flex items-center rounded-sm px-1.5 py-0.5 text-[9px] ${
                repo.webhookInstalled
                  ? 'bg-status-success/10 text-status-success'
                  : 'bg-[var(--gray-400)]/50 text-muted'
              }`}
              title={repo.webhookInstalled
                ? 'Webhook installed — PR merges trigger post-merge cascade'
                : 'Optional — install to auto-refresh architecture/memory on PR merge'}
            >
              {repo.webhookInstalled ? 'webhook ✓' : 'webhook: not set up'}
            </span>
          </div>
        </div>
        <div className="flex gap-1.5 shrink-0">
          <button
            onClick={() => onSync(repo.id)}
            className="text-[11px] px-2 py-0.5 rounded-sm border border-[var(--border-default)] text-muted hover:text-primary"
          >
            Sync
          </button>
          {!repo.webhookInstalled && (
            <button
              onClick={doInstallHook}
              disabled={installingHook}
              title="Install a pull_request webhook using your GitHub PAT (requires admin:repo_hook)"
              className="text-[11px] px-2 py-0.5 rounded-sm border border-accent-amber/40 text-accent-amber hover:bg-accent-amber/10 disabled:opacity-50"
            >
              {installingHook ? 'Installing…' : 'Install webhook'}
            </button>
          )}
          <button
            onClick={() => onUnlink(repo.id)}
            className="text-[11px] px-2 py-0.5 rounded-sm border border-accent-red/40 text-accent-red hover:bg-accent-red/10"
          >
            Unlink
          </button>
        </div>
      </div>

      <div className="mt-2">
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
          <div className="flex flex-wrap items-center gap-1.5">
            {relationships.length === 0 && (
              <span className="text-[11px] text-muted">no relationships</span>
            )}
            {relationships.map((r) => (
              <span
                key={r}
                className="inline-flex items-center rounded-sm bg-[var(--gray-200)] px-1.5 py-0.5 text-[11px] text-secondary"
              >
                {r}
              </span>
            ))}
            <button
              onClick={() => setEditing(true)}
              className="text-[11px] text-accent-blue hover:underline"
            >
              edit
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
