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
    <div className="border rounded-lg bg-white p-4 shadow-sm">
      <div className="flex items-start justify-between gap-4">
        <div className="min-w-0">
          <div className="flex items-center gap-2">
            <span className="font-mono text-sm truncate">{repo.githubRepoFullName}</span>
            <span
              className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs ${
                repo.scope === 'ORG'
                  ? 'bg-purple-50 text-purple-700'
                  : 'bg-amber-50 text-amber-700'
              }`}
            >
              {repo.scope}
            </span>
          </div>
          <div className="mt-1 flex items-center gap-2 text-xs text-gray-500">
            <StackBadge stack={stack} />
            <span>branch: {repo.defaultBranch}</span>
            {repo.lastSyncedSha && (
              <span>· last sync {repo.lastSyncedSha.slice(0, 7)}</span>
            )}
          </div>
        </div>
        <div className="flex gap-2 shrink-0">
          <button
            onClick={() => onSync(repo.id)}
            className="text-xs px-2 py-1 border rounded hover:bg-gray-50"
          >
            Sync
          </button>
          <button
            onClick={() => onUnlink(repo.id)}
            className="text-xs px-2 py-1 border rounded hover:bg-red-50 text-red-600 border-red-200"
          >
            Unlink
          </button>
        </div>
      </div>

      <div className="mt-3">
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
          <div className="flex flex-wrap items-center gap-2">
            {relationships.length === 0 && (
              <span className="text-xs text-gray-400">no relationships</span>
            )}
            {relationships.map((r) => (
              <span
                key={r}
                className="inline-flex items-center rounded bg-gray-100 px-2 py-0.5 text-xs"
              >
                {r}
              </span>
            ))}
            <button
              onClick={() => setEditing(true)}
              className="text-xs text-blue-600 hover:underline"
            >
              edit
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
