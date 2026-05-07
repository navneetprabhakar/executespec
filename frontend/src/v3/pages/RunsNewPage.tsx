import {useEffect, useMemo, useState} from 'react';
import {Link} from 'react-router-dom';
import {ArrowLeft, Plus} from 'lucide-react';
import {createProject, getProjects, type ProjectDto} from '@/lib/api';
import Composer from '../components/Composer';

/**
 * 2026-05-07 — `/runs/new` standalone launcher.
 *
 * Closes the user feedback that "Launch new run" on /runs forced a
 * detour through /home → first-project, blocking the FREE / no-project
 * use case. The previous redirect chain assumed every authed user had
 * a project shell — which is false for FREE users (no Project Shell
 * gate) and also unhelpful for power users who just want to fire a
 * one-shot run without picking a specific project.
 *
 * Layout: page header with back link → optional project picker →
 * the existing v3 Composer. Composer accepts an optional projectId
 * since 2026-05-07; when null/undefined it submits the run with no
 * projectId and redirects to /runs/{id} on success (the run shows
 * up under /runs but isn't pinned to any project shell).
 *
 * Default project pick: "(no project — standalone run)" so users
 * land on the project-less path by default — matches the user's
 * "I should be able to submit run even without a project" ask.
 * They can opt into a specific project from the dropdown if they
 * want the run to live inside that shell.
 */
export default function RunsNewPage() {
  const [projects, setProjects] = useState<ProjectDto[] | null>(null);
  const [pickedProjectId, setPickedProjectId] = useState<string>('');

  // 2026-05-07 — Issue 3: inline "Create new project" flow. Picker has a
  // sentinel "__create__" option; selecting it reveals a name input below
  // the dropdown. Submit calls createProject() and auto-selects the new
  // project so the user can immediately type their spec without bouncing
  // through /org/projects.
  const [creatingMode, setCreatingMode] = useState(false);
  const [newProjectName, setNewProjectName] = useState('');
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    getProjects()
      .then((p) => {
        if (!cancelled) setProjects(p);
      })
      .catch(() => {
        if (!cancelled) setProjects([]);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  function onPickerChange(value: string) {
    if (value === '__create__') {
      setCreatingMode(true);
      // Don't change the selected project — keep the previous pick so a
      // user who opens the dropdown and changes their mind can cancel
      // out without losing the standalone / existing-project state.
      return;
    }
    setCreatingMode(false);
    setNewProjectName('');
    setCreateError(null);
    setPickedProjectId(value);
  }

  async function onCreateProject() {
    const name = newProjectName.trim();
    if (!name || creating) return;
    setCreating(true);
    setCreateError(null);
    try {
      const project = await createProject({name});
      setProjects((prev) => [...(prev ?? []), project]);
      setPickedProjectId(project.id);
      setCreatingMode(false);
      setNewProjectName('');
    } catch (e) {
      setCreateError((e as Error)?.message ?? 'Failed to create project');
    } finally {
      setCreating(false);
    }
  }

  function onCancelCreate() {
    setCreatingMode(false);
    setNewProjectName('');
    setCreateError(null);
  }

  // When the user picks a real project, look up its default provider
  // so the Composer's Advanced provider field reflects that project's
  // configured AI vendor instead of the platform default. Standalone
  // path uses `undefined` → Composer falls back to ANTHROPIC.
  const pickedProject = useMemo<ProjectDto | undefined>(() => {
    if (!pickedProjectId) return undefined;
    return projects?.find((p) => p.id === pickedProjectId);
  }, [projects, pickedProjectId]);

  const composerKey = pickedProjectId || '__standalone__';

  return (
    <div style={{maxWidth: 880, margin: '0 auto', padding: '32px 24px'}}>
      <Link
        to='/runs'
        style={{
          display: 'inline-flex',
          alignItems: 'center',
          gap: 4,
          fontSize: 12,
          color: 'var(--v3-text-muted)',
          textDecoration: 'none',
          marginBottom: 16,
        }}
      >
        <ArrowLeft className='h-3 w-3' />
        All runs
      </Link>

      <header style={{marginBottom: 20}}>
        <div
          className='v3-eyebrow'
          style={{
            display: 'inline-block',
            padding: '4px 10px',
            border: '1px solid rgba(91,141,239,0.30)',
            borderRadius: 999,
            color: 'var(--v3-brand-hover)',
            marginBottom: 12,
          }}
        >
          New run
        </div>
        <h1 style={{fontSize: 28, fontWeight: 700, color: 'var(--v3-text)', margin: 0}}>
          Describe what you want to ship.
        </h1>
        <p
          style={{
            margin: '8px 0 0',
            color: 'var(--v3-text-muted)',
            maxWidth: 640,
            lineHeight: 1.55,
          }}
        >
          Launch a one-shot run. Pick a project to anchor it inside a workspace, or leave the picker
          on standalone — the run still shows up under /runs and you can wire it into a project
          later.
        </p>
      </header>

      {/* Project picker — defaults to "(no project)" so the standalone
          flow is the path of least resistance. Users with linked
          projects can opt in via the dropdown. */}
      <div className='v3-card' style={{marginBottom: 16}}>
        <label
          className='v3-eyebrow'
          htmlFor='runs-new-project-picker'
          style={{display: 'block', marginBottom: 6}}
        >
          Project
        </label>
        <select
          id='runs-new-project-picker'
          value={creatingMode ? '__create__' : pickedProjectId}
          onChange={(e) => onPickerChange(e.target.value)}
          style={{
            width: '100%',
            padding: '8px 10px',
            background: 'var(--v3-bg)',
            color: 'var(--v3-text)',
            border: '1px solid var(--v3-border)',
            borderRadius: 'var(--v3-radius)',
            fontSize: 13,
            fontFamily: 'inherit',
          }}
        >
          <option value=''>(no project — standalone run)</option>
          {(projects ?? []).map((p) => (
            <option key={p.id} value={p.id}>
              {p.name}
              {p.aiProvider ? ` · ${p.aiProvider}` : ''}
            </option>
          ))}
          <option value='__create__'>+ Create new project…</option>
        </select>

        {/* Inline create-project form. Reveals when "Create new project…" is
            picked. Submit names the project + auto-selects it so the user can
            type their spec immediately without bouncing through /org/projects. */}
        {creatingMode && (
          <div
            style={{
              marginTop: 10,
              padding: 12,
              borderRadius: 'var(--v3-radius)',
              border: '1px solid var(--v3-border)',
              background: 'var(--v3-bg)',
              display: 'flex',
              flexDirection: 'column',
              gap: 8,
            }}
          >
            <label
              className='v3-eyebrow'
              htmlFor='runs-new-project-name'
              style={{display: 'block'}}
            >
              New project name
            </label>
            <input
              id='runs-new-project-name'
              type='text'
              value={newProjectName}
              onChange={(e) => setNewProjectName(e.target.value)}
              placeholder='e.g. payments-platform'
              autoFocus
              disabled={creating}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && newProjectName.trim()) {
                  e.preventDefault();
                  void onCreateProject();
                }
                if (e.key === 'Escape') onCancelCreate();
              }}
              style={{
                width: '100%',
                padding: '7px 10px',
                background: 'var(--v3-surface)',
                color: 'var(--v3-text)',
                border: '1px solid var(--v3-border)',
                borderRadius: 'var(--v3-radius-sm)',
                fontSize: 13,
                fontFamily: 'inherit',
              }}
            />
            <div style={{display: 'flex', gap: 8}}>
              <button
                type='button'
                onClick={onCreateProject}
                disabled={!newProjectName.trim() || creating}
                style={{
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: 4,
                  padding: '6px 12px',
                  fontSize: 12.5,
                  fontWeight: 600,
                  background:
                    newProjectName.trim() && !creating
                      ? 'var(--v3-brand)'
                      : 'var(--v3-surface-elevated)',
                  color:
                    newProjectName.trim() && !creating
                      ? 'var(--v3-text-on-brand)'
                      : 'var(--v3-text-subtle)',
                  border: 'none',
                  borderRadius: 'var(--v3-radius-sm)',
                  cursor: newProjectName.trim() && !creating ? 'pointer' : 'not-allowed',
                  fontFamily: 'inherit',
                }}
              >
                <Plus className='h-3.5 w-3.5' />
                {creating ? 'Creating…' : 'Create project'}
              </button>
              <button
                type='button'
                onClick={onCancelCreate}
                disabled={creating}
                style={{
                  padding: '6px 12px',
                  fontSize: 12.5,
                  background: 'transparent',
                  color: 'var(--v3-text-muted)',
                  border: '1px solid var(--v3-border)',
                  borderRadius: 'var(--v3-radius-sm)',
                  cursor: creating ? 'not-allowed' : 'pointer',
                  fontFamily: 'inherit',
                }}
              >
                Cancel
              </button>
            </div>
            {createError && (
              <div
                role='alert'
                style={{
                  fontSize: 11.5,
                  color: 'var(--v3-error)',
                }}
              >
                {createError}
              </div>
            )}
          </div>
        )}

        <p
          style={{
            margin: '8px 0 0',
            fontSize: 11.5,
            color: 'var(--v3-text-subtle)',
            lineHeight: 1.5,
          }}
        >
          {creatingMode
            ? 'You can attach a GitHub repo + tweak the AI provider after the project is created.'
            : pickedProjectId
              ? 'The run will live inside this project shell. Past runs in the project feed into the planner via project memory.'
              : 'Standalone runs work end-to-end (planner, executor, validation, PR) without a project — useful for one-off scripts or trying ExecuteSpec on a fresh idea.'}
        </p>
      </div>

      {/* Composer — keyed on projectId so a fresh instance mounts when
          the user switches between standalone and a specific project.
          That re-runs the prefill / cap / estimate hooks against the
          right project context. */}
      <Composer
        key={composerKey}
        projectId={pickedProjectId || undefined}
        isEmpty
        defaultProvider={pickedProject?.aiProvider}
      />
    </div>
  );
}
