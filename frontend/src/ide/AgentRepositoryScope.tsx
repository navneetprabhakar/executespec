export interface CloudRunRepoChoice {
  id: string;
  label: string;
  selectable: boolean;
}

export interface CloudRunRepoSelection {
  status: 'idle' | 'loading' | 'ready' | 'error';
  projectId: string | null;
  repos: CloudRunRepoChoice[];
  selectedRepoIds: string[];
  error: string | null;
  onToggle: (repoId: string) => void;
  onRetry: () => void;
}

export function repositoryConfirmationDisabled(
  selection: CloudRunRepoSelection | undefined,
  busy: boolean,
): boolean {
  const selectableRepos = selection?.repos.filter((repo) => repo.selectable) ?? [];
  return (
    busy ||
    selection?.status !== 'ready' ||
    ((selection?.repos.length ?? 0) > 0 && selectableRepos.length === 0) ||
    (selectableRepos.length > 0 && (selection?.selectedRepoIds.length ?? 0) === 0)
  );
}

export function AgentRepositoryScope({
  selection,
  busy,
}: {
  selection?: CloudRunRepoSelection;
  busy: boolean;
}) {
  const selectableRepos = selection?.repos.filter((repo) => repo.selectable) ?? [];
  const repositoryRequired = selectableRepos.length > 0;
  const hasRepositorySelection = (selection?.selectedRepoIds.length ?? 0) > 0;

  return (
    <div className='agent-repo-boundary' aria-label='Repository scope'>
      <div className='agent-contract-title'>Repository scope</div>
      {!selection || selection.status === 'idle' || selection.status === 'loading' ? (
        <div className='agent-card-sub' role='status'>
          Loading linked repositories…
        </div>
      ) : selection.status === 'error' ? (
        <div className='agent-repo-boundary-error' role='alert'>
          <span>{selection.error || 'Linked repositories could not be loaded.'}</span>
          <button className='ide-btn ghost' type='button' onClick={selection.onRetry}>
            Retry
          </button>
        </div>
      ) : selection.repos.length === 0 ? (
        <div className='agent-card-sub'>
          This project has no linked repositories. The run will use project context only.
        </div>
      ) : (
        <>
          <div className='agent-card-sub'>
            Select the repositories this run may read and change. Nothing is selected automatically.
          </div>
          <div className='agent-repo-options'>
            {selection.repos.map((repo) => (
              <label
                key={repo.id}
                className={`agent-repo-option${repo.selectable ? '' : ' disabled'}`}
              >
                <input
                  type='checkbox'
                  checked={selection.selectedRepoIds.includes(repo.id)}
                  disabled={busy || !repo.selectable}
                  onChange={() => selection.onToggle(repo.id)}
                />
                <span>{repo.label}</span>
                {!repo.selectable && <small>browse only</small>}
              </label>
            ))}
          </div>
          {repositoryRequired && !hasRepositorySelection && (
            <div className='agent-repo-boundary-warning' role='status'>
              Select at least one runnable repository before creating the plan.
            </div>
          )}
          {!repositoryRequired && (
            <div className='agent-repo-boundary-warning' role='alert'>
              No linked repository supports remote execution.
            </div>
          )}
        </>
      )}
    </div>
  );
}
