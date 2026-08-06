import type * as api from '@/lib/api';

/**
 * Compact project-scope control for the agent surface.
 *
 * Replaces the horizontal project tab strip (unusable past a handful of
 * projects — founder feedback 2026-08-05) with one labelled dropdown plus the
 * Create project action. Selecting "No project" preserves the old Unlink
 * behavior; switching scope must never create a session by itself — sessions
 * are created lazily on the first message.
 */
export function AgentProjectContext({
  currentProject,
  linkedProjectId,
  projects,
  loading,
  error,
  disabledReason,
  onSelect,
  onUnlink,
  onCreate,
  onRetry,
}: {
  currentProject: api.ProjectDto | null;
  linkedProjectId?: string | null;
  projects: api.ProjectDto[];
  loading: boolean;
  error: string | null;
  disabledReason: string | null;
  onSelect: (project: api.ProjectDto) => void;
  onUnlink: () => void;
  onCreate: () => void;
  onRetry: () => void;
}) {
  const active = projects
    .filter((project) => !project.archivedAt)
    .sort((left, right) => left.name.localeCompare(right.name));
  // A linked project that fell out of the list (archived, or a stale fetch)
  // must stay visible as the current selection instead of snapping to "No project".
  const linkedMissing =
    !!linkedProjectId && !active.some((project) => project.id === linkedProjectId);
  return (
    <section className='agent-project-context' aria-labelledby='agent-project-context-title'>
      <label id='agent-project-context-title' htmlFor='agent-project-select'>
        Project
      </label>
      <select
        id='agent-project-select'
        className='ide-select'
        aria-label='Project context'
        value={linkedProjectId ?? ''}
        disabled={loading || !!disabledReason}
        title={disabledReason || undefined}
        onChange={(event) => {
          const nextId = event.target.value;
          if (!nextId) {
            if (linkedProjectId) onUnlink();
            return;
          }
          if (nextId === linkedProjectId) return;
          const project = active.find((candidate) => candidate.id === nextId);
          if (project) onSelect(project);
        }}
      >
        <option value=''>No project</option>
        {linkedMissing && (
          <option value={linkedProjectId ?? ''}>
            {currentProject?.name ?? (loading ? 'Loading linked project…' : 'Linked project')}
          </option>
        )}
        {active.map((project) => (
          <option key={project.id} value={project.id}>
            {project.name}
          </option>
        ))}
      </select>
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
      {/* Ghost: this bar is context chrome, not the surface's commit. */}
      <button
        className='ide-btn ghost'
        type='button'
        disabled={!!disabledReason}
        onClick={onCreate}
      >
        Create project
      </button>
      {/* `.agent-project-error` alone styles a failure as muted body text — the
          same treatment as the hint beside it. The note classes add the 3px
          leading edge and tint; the original class is retained because it is the
          identity other surfaces (and IdeAgent.test.tsx) select this element by. */}
      {error && (
        <div className='agent-project-error ide-note bad gf-inline-note' role='alert'>
          <span>{error}</span>
          <button type='button' className='ide-btn ghost' onClick={onRetry}>
            Retry
          </button>
        </div>
      )}
      {/* The reason is stated in full at every width. On a phone it is stated in
          the accessibility tree and the control's own tooltip while the visible
          form shrinks to one word, because three wrapped lines of it were the
          third stacked header bar above a 375px transcript. Nothing about the
          lock is dropped — only its typography changes. */}
      {disabledReason && (
        <span className='agent-project-locked' role='status' title={disabledReason}>
          <span className='agent-project-locked-full'>{disabledReason}</span>
          <span className='agent-project-locked-brief' aria-hidden='true'>
            Locked
          </span>
        </span>
      )}
    </section>
  );
}
