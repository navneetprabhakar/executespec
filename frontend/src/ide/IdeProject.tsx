import {useEffect, useState} from 'react';
import {Link, Navigate, useLocation, useNavigate, useParams} from 'react-router-dom';
import * as api from '@/lib/api';
import {IdeBadge, IdeLoader, statusKind} from '@/ide/IdeKit';
import {SpecChat} from '@/signal/components/SpecChat';
import {repoDisplayName, repoIdFor, relTime} from '@/signal/lib/runUtils';
import {ProjectTabs} from '@/ide/IdeProjectTabs';

type Repo = Record<string, unknown>;
// Relative timestamps everywhere (review/11 #8) — absolute locale strings were
// ambiguous (DD/MM vs MM/DD) and inconsistent with the Console chrome.
function fmtWhen(iso?: string | null): string {
  return relTime(iso);
}

// Project compose surface. Project header + spec composer (repo scoping) +
// recent project work, all inside the Console route outlet.
export default function IdeProject() {
  const {projectId} = useParams();
  const navigate = useNavigate();
  const location = useLocation();
  // §3.4 — explicit compose intent (the New work tab + Overview CTA
  // pass {compose:true} via Link state) skips the landing→Overview redirect below,
  // so the composer stays reachable on projects with work history.
  const composeIntent = Boolean((location.state as {compose?: boolean} | null)?.compose);
  const [project, setProject] = useState<api.ProjectDto | null>(null);
  const [repos, setRepos] = useState<Repo[]>([]);
  const [selected, setSelected] = useState<string[]>([]);
  const [runs, setRuns] = useState<api.RunSummary[]>([]);
  const [runsLoaded, setRunsLoaded] = useState(false);
  const [spec, setSpec] = useState('');
  const [provider, setProvider] = useState('ANTHROPIC');
  const [mode, setMode] = useState<'PROTOTYPE' | 'PRODUCTION'>('PRODUCTION');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!projectId) return;
    api
      .getProject(projectId)
      .then(setProject)
      .catch(() => {})
      .finally(() => setLoading(false));
    api
      .getProjectRepos(projectId)
      .then((list) => setRepos((list as unknown as Repo[]) ?? []))
      .catch(() => setRepos([]));
    api
      .getRuns({projectId, size: 6})
      .then((r) => setRuns(r.content ?? []))
      .catch(() => setRuns([]))
      .finally(() => setRunsLoaded(true));
    if (project?.aiProvider) setProvider(project.aiProvider.toUpperCase());
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId]);

  async function submit() {
    if (!spec.trim()) {
      setErr('Describe what to build first.');
      return;
    }
    setBusy(true);
    setErr('');
    try {
      const body: Parameters<typeof api.createRun>[0] = {
        userRequest: spec.trim(),
        aiProvider: provider,
        executionMode: mode,
        projectId,
      };
      if (selected.length) body.selectedRepoIds = selected;
      const res = await api.createRun(body);
      navigate(`/live/${res.runId}`);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Submit failed.');
      setBusy(false);
    }
  }

  // Hold the loader until the run list settles too (unless compose was asked
  // for explicitly) so the composer doesn't flash before the redirect.
  if (loading || (!composeIntent && !runsLoaded)) {
    return (
      <div className='ide-pane'>
        <IdeLoader label='Loading project…' />
      </div>
    );
  }

  // §3.4 (P1 IA) — most arrivals at /projects/:id are status checks: land on
  // Overview once the project has work history. Brand-new projects keep
  // the compose-first landing.
  if (!composeIntent && runs.length > 0) {
    return <Navigate to={`/projects/${projectId}/pulse`} replace />;
  }

  const activeRuns = runs.filter((r) =>
    [
      'CLASSIFYING',
      'PLANNING',
      'EXECUTING',
      'RUNNING',
      'IN_PROGRESS',
      'AWAITING_APPROVAL',
    ].includes((r.status || '').toUpperCase()),
  );
  const lastRun = runs[0];

  return (
    <div className='ide-pane'>
      {/* C3 — ProjectTabs renders the Projects › {name} crumbs; the old "←"
          back button is redundant with the crumb trail. */}
      <ProjectTabs projectId={projectId || ''} />
      <h1 className='ide-h1' style={{marginBottom: 4}}>
        {project?.name || 'Project'}
      </h1>
      <div className='ide-sub'>
        {repos.length} repo{repos.length === 1 ? '' : 's'} · {project?.status || 'ACTIVE'}
      </div>

      <div className='ide-project-context-grid'>
        <div className='ide-card'>
          <div className='ide-h2' style={{marginTop: 0}}>
            Code boundary
          </div>
          <div className='ide-kv'>
            <span>Repos connected</span>
            <span>{repos.length}</span>
          </div>
          <div className='ide-kv' style={{borderBottom: 'none'}}>
            <span>Repos selected</span>
            <span>{selected.length || repos.length || 0}</span>
          </div>
        </div>
        <div className='ide-card'>
          <div className='ide-h2' style={{marginTop: 0}}>
            Work state
          </div>
          <div className='ide-kv'>
            <span>Active</span>
            <span>{activeRuns.length}</span>
          </div>
          <div className='ide-kv' style={{borderBottom: 'none'}}>
            <span>Latest</span>
            <span>
              {lastRun ? `${lastRun.status} · ${fmtWhen(lastRun.startedAt)}` : 'No work yet'}
            </span>
          </div>
        </div>
        <div className='ide-card'>
          <div className='ide-h2' style={{marginTop: 0}}>
            Run behavior
          </div>
          <div className='ide-kv'>
            <span>Provider</span>
            <span>{provider.toLowerCase()}</span>
          </div>
          <div className='ide-kv' style={{borderBottom: 'none'}}>
            <span>Approval</span>
            <span>{mode === 'PRODUCTION' ? 'Plan gate' : 'Auto-start'}</span>
          </div>
        </div>
      </div>

      <div className='ide-h2'>New work in this project</div>
      <textarea
        className='ide-textarea'
        rows={8}
        placeholder='Describe what you want, in plain English. Reference repos or files to keep the agent inside specific boundaries.'
        value={spec}
        onChange={(e) => setSpec(e.target.value)}
      />
      <div style={{marginTop: 10}}>
        <SpecChat projectId={projectId} draftSpec={spec} onApply={setSpec} />
      </div>

      {repos.length > 0 && (
        <>
          <div className='ide-h2'>Repos the agent may touch</div>
          <div style={{display: 'flex', gap: 8, flexWrap: 'wrap'}}>
            {repos.map((r) => {
              const id = repoIdFor(r);
              const on = selected.includes(id);
              return (
                <button
                  key={id}
                  className='ide-badge'
                  style={{
                    cursor: 'pointer',
                    borderColor: on ? 'var(--ide-accent)' : undefined,
                    color: on ? 'var(--ide-accent)' : undefined,
                  }}
                  onClick={() => setSelected((s) => (on ? s.filter((x) => x !== id) : [...s, id]))}
                >
                  {on ? '✓ ' : ''}
                  {repoDisplayName(r)}
                </button>
              );
            })}
          </div>
        </>
      )}

      <div style={{display: 'flex', gap: 12, margin: '14px 0', flexWrap: 'wrap'}}>
        <div style={{flex: 1, minWidth: 160}}>
          <div className='ide-h2' style={{marginTop: 0}}>
            Provider
          </div>
          <select
            className='ide-select'
            value={provider}
            onChange={(e) => setProvider(e.target.value)}
          >
            <option value='ANTHROPIC'>Anthropic</option>
            <option value='OPENAI'>OpenAI</option>
            <option value='GEMINI'>Gemini</option>
            <option value='DEEPSEEK'>DeepSeek</option>
          </select>
        </div>
        <div style={{flex: 1, minWidth: 160}}>
          <div className='ide-h2' style={{marginTop: 0}}>
            Mode
          </div>
          <select
            className='ide-select'
            value={mode}
            onChange={(e) => setMode(e.target.value as 'PROTOTYPE' | 'PRODUCTION')}
          >
            <option value='PRODUCTION'>Production (plan gate)</option>
            <option value='PROTOTYPE'>Prototype (auto)</option>
          </select>
        </div>
      </div>
      {err && <div style={{color: 'var(--ide-red)', fontSize: 13, marginBottom: 10}}>{err}</div>}
      <button className='ide-btn' onClick={submit} disabled={busy}>
        {busy ? 'Submitting…' : 'Start work'}
      </button>

      <div className='ide-h2'>Recent work in this project</div>
      {runs.length === 0 ? (
        <div className='ide-empty'>No work yet.</div>
      ) : (
        <div className='ide-grid'>
          {runs.map((r) => (
            <Link key={r.runId} className='ide-run-card' to={`/result/${r.runId}`}>
              <div className='ide-run-title'>
                {(r.runTitle || r.userRequest || r.runId).slice(0, 70)}
              </div>
              <div className='ide-run-meta'>
                <IdeBadge kind={statusKind(r.status)}>{r.status}</IdeBadge>
                <span>{fmtWhen(r.startedAt)}</span>
              </div>
            </Link>
          ))}
        </div>
      )}
    </div>
  );
}
