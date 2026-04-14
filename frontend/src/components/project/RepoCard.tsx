import { useMemo, useState } from 'react';
import type { ProjectRepoLink, StackInfo } from '@/types/project';
import StackBadge from './StackBadge';
import RepoRelationshipEditor from './RepoRelationshipEditor';

interface Props {
  link: ProjectRepoLink;
  onUnlink: (repoId: string) => void;
  onSync: (repoId: string) => void;
  onUpdateRelationships: (repoId: string, relationships: string[]) => void;
}

export default function RepoCard({ link, onUnlink, onSync, onUpdateRelationships }: Props) {
  const { repo } = link;
  const [editing, setEditing] = useState(false);

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
            <span className="font-mono text-[11px] truncate text-primary">{repo.githubRepoFullName}</span>
            <span
              className={`inline-flex items-center rounded-sm px-1.5 py-0.5 text-[10px] ${
                repo.scope === 'ORG'
                  ? 'bg-accent-blue/10 text-accent-blue'
                  : 'bg-accent-amber/10 text-accent-amber'
              }`}
            >
              {repo.scope}
            </span>
          </div>
          <div className="mt-1 flex items-center gap-2 text-[10px] text-muted">
            <StackBadge stack={stack} />
            <span>branch: {repo.defaultBranch}</span>
            {repo.lastSyncedSha && (
              <span>· last sync {repo.lastSyncedSha.slice(0, 7)}</span>
            )}
          </div>
        </div>
        <div className="flex gap-1.5 shrink-0">
          <button
            onClick={() => onSync(repo.id)}
            className="text-[10px] px-2 py-0.5 rounded-sm border border-[var(--border-default)] text-muted hover:text-primary"
          >
            Sync
          </button>
          <button
            onClick={() => onUnlink(repo.id)}
            className="text-[10px] px-2 py-0.5 rounded-sm border border-accent-red/40 text-accent-red hover:bg-accent-red/10"
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
              <span className="text-[10px] text-muted">no relationships</span>
            )}
            {relationships.map((r) => (
              <span
                key={r}
                className="inline-flex items-center rounded-sm bg-[var(--gray-200)] px-1.5 py-0.5 text-[10px] text-secondary"
              >
                {r}
              </span>
            ))}
            <button
              onClick={() => setEditing(true)}
              className="text-[10px] text-accent-blue hover:underline"
            >
              edit
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
