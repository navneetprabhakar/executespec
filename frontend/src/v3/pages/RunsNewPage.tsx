import {useEffect, useMemo, useState} from 'react';
import {Link} from 'react-router-dom';
import {ArrowLeft} from 'lucide-react';
import {getProjects, type ProjectDto} from '@/lib/api';
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
          Launch a one-shot run. Pick a project to anchor it inside a workspace, or leave the
          picker on standalone — the run still shows up under /runs and you can wire it into a
          project later.
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
          value={pickedProjectId}
          onChange={(e) => setPickedProjectId(e.target.value)}
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
        </select>
        <p
          style={{
            margin: '8px 0 0',
            fontSize: 11.5,
            color: 'var(--v3-text-subtle)',
            lineHeight: 1.5,
          }}
        >
          {pickedProjectId
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
