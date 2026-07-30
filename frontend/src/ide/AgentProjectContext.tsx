import type * as api from '@/lib/api';

export function AgentProjectContext({
  currentProject,
  linkedProjectId,
  projects,
  query,
  loading,
  error,
  disabledReason,
  onQueryChange,
  onSelect,
  onUnlink,
  onCreate,
  onRetry,
}: {
  currentProject: api.ProjectDto | null;
  linkedProjectId?: string | null;
  projects: api.ProjectDto[];
  query: string;
  loading: boolean;
  error: string | null;
  disabledReason: string | null;
  onQueryChange: (query: string) => void;
  onSelect: (project: api.ProjectDto) => void;
  onUnlink: () => void;
  onCreate: () => void;
  onRetry: () => void;
}) {
  const normalized = query.trim().toLowerCase();
  const visible = projects
    .filter((project) => !project.archivedAt)
    .filter((project) => !normalized || project.name.toLowerCase().includes(normalized))
    .sort((left, right) => Date.parse(right.createdAt) - Date.parse(left.createdAt))
    .slice(0, normalized ? 20 : 5);
  return (
    <section className='agent-project-context' aria-labelledby='agent-project-context-title'>
      <div className='agent-project-context-head'>
        <div>
          <span className='agent-project-eyebrow'>Current project</span>
          <strong id='agent-project-context-title'>
            {currentProject?.name ??
              (linkedProjectId
                ? loading
                  ? 'Loading linked project…'
                  : 'Linked project unavailable'
                : 'No project linked')}
          </strong>
        </div>
        {linkedProjectId && (
          <button
            className='ide-btn ghost'
            type='button'
            disabled={!!disabledReason}
            title={disabledReason || 'Unlink project'}
            onClick={onUnlink}
          >
            Unlink
          </button>
        )}
      </div>
      {!linkedProjectId && (
        <p className='agent-project-recovery'>
          Link a project to ground code questions and run proposals. You can continue a general
          conversation without one.
        </p>
      )}
      <label htmlFor='agent-project-search'>Find an existing project</label>
      <input
        id='agent-project-search'
        className='ide-input'
        type='search'
        value={query}
        placeholder='Search projects'
        disabled={loading}
        onChange={(event) => onQueryChange(event.target.value)}
      />
      {error ? (
        <div className='agent-project-error' role='alert'>
          <span>{error}</span>
          <button type='button' className='ide-btn ghost' onClick={onRetry}>
            Retry
          </button>
        </div>
      ) : (
        <div
          className='agent-project-results'
          aria-label={normalized ? 'Project search results' : 'Recent projects'}
        >
          {loading ? (
            <span>Loading projects…</span>
          ) : visible.length === 0 ? (
            <span>{normalized ? 'No matching projects.' : 'No existing projects.'}</span>
          ) : (
            visible.map((project) => (
              <button
                type='button'
                key={project.id}
                disabled={!!disabledReason || project.id === linkedProjectId}
                title={disabledReason || undefined}
                onClick={() => onSelect(project)}
              >
                <span>{project.name}</span>
                <small>{project.id === linkedProjectId ? 'Linked' : 'Open in agent'}</small>
              </button>
            ))
          )}
        </div>
      )}
      <div className='agent-project-actions'>
        <button className='ide-btn' type='button' disabled={!!disabledReason} onClick={onCreate}>
          Create project
        </button>
        {disabledReason && <span role='status'>{disabledReason}</span>}
      </div>
    </section>
  );
}
