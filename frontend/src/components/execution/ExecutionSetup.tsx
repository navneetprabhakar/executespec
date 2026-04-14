import { useEffect, useState } from 'react';
import { useProjectStore } from '@/stores/useProjectStore';
import { useExecutionStore } from '@/stores/useExecutionStore';

interface Props {
  projectId: string;
  onSubmitted: (executionId: string) => void;
}

export default function ExecutionSetup({ projectId, onSubmitted }: Props) {
  const { repos, fetchRepos } = useProjectStore();
  const { createExecution, loading, error } = useExecutionStore();
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [requirement, setRequirement] = useState('');
  const [jiraEnabled, setJiraEnabled] = useState(false);

  useEffect(() => { fetchRepos(projectId); }, [projectId, fetchRepos]);

  const toggle = (id: string) => {
    const next = new Set(selected);
    if (next.has(id)) next.delete(id); else next.add(id);
    setSelected(next);
  };

  const canSubmit = requirement.trim().length > 0 && selected.size > 0 && !loading;

  const submit = async () => {
    if (!canSubmit) return;
    const id = await createExecution(projectId, {
      requirement: requirement.trim(),
      repoIds: Array.from(selected),
      jiraEnabled,
    });
    onSubmitted(id);
  };

  return (
    <div className="space-y-4">
      <div>
        <h3 className="text-[12px] font-medium text-primary mb-2">Select repos</h3>
        <div className="space-y-1.5">
          {(repos ?? []).map((r: any) => {
            const rid = r.id ?? r.repoId;
            const name = r.githubRepoFullName ?? r.repoName ?? rid;
            return (
              <label key={rid} className="flex items-center gap-2 text-[12px]">
                <input type="checkbox" checked={selected.has(rid)} onChange={() => toggle(rid)} />
                <span>{name}</span>
              </label>
            );
          })}
          {(!repos || repos.length === 0) && (
            <div className="text-[11px] text-muted">No repos linked to this project yet.</div>
          )}
        </div>
      </div>

      <div>
        <h3 className="text-[12px] font-medium text-primary mb-2">Requirement</h3>
        <textarea
          value={requirement}
          onChange={(e) => setRequirement(e.target.value)}
          rows={8}
          className="w-full rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] px-2 py-1 text-[12px] font-mono"
          placeholder="Describe the cross-repo change in plain English…"
        />
      </div>

      <label className="flex items-center gap-2 text-[12px]">
        <input type="checkbox" checked={jiraEnabled} onChange={(e) => setJiraEnabled(e.target.checked)} />
        <span>Sync this execution with JIRA (requires org-level JIRA integration + project key)</span>
      </label>

      {error && <div className="text-[11px] text-accent-red">{error}</div>}

      <button
        disabled={!canSubmit}
        onClick={submit}
        className="rounded-sm border border-accent-blue/40 bg-accent-blue/10 text-accent-blue px-3 py-1.5 text-[12px] disabled:opacity-40"
      >
        Analyse & generate plan
      </button>
    </div>
  );
}
