import {useEffect, useState} from 'react';
import {Link, Navigate, useLocation, useNavigate, useParams} from 'react-router-dom';
import * as api from '@/lib/api';
import {IdeBadge, IdeLoader, statusKind} from '@/ide/IdeKit';
import {SpecChat} from '@/signal/components/SpecChat';
import {repoDisplayName, repoIdFor, relTime} from '@/signal/lib/runUtils';
import {ProjectTabs} from '@/ide/IdeProjectTabs';
import {explicitLinkedRepoSelection, repoSupports} from '@/ide/projectRepoUi';
import {
  PRODUCT_PROVIDER_OPTIONS,
  providerIsDispatchable,
  providerOptionLabel,
  providerReadinessLabel,
} from '@/ide/providerReadiness';
import {useProviderReadiness} from '@/ide/useProviderReadiness';
import {useAuthStore, selectNormalizedTier} from '@/stores/useAuthStore';

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
  // §3.4 — explicit compose intent (the Start tab + Overview CTA
  // pass {compose:true} via Link state) skips the landing→Overview redirect below,
  // so the composer stays reachable on projects with work history.
  const composeIntent = Boolean((location.state as {compose?: boolean} | null)?.compose);
  const providerReadiness = useProviderReadiness();
  const tier = useAuthStore(selectNormalizedTier);
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
  const [loadError, setLoadError] = useState<'not-found' | 'forbidden' | 'error' | null>(null);

  useEffect(() => {
    if (!projectId) return;
    api
      .getProject(projectId)
      .then((loadedProject) => {
        setProject(loadedProject);
        if (tier === 'FREE') setProvider('DEEPSEEK');
        else if (loadedProject.aiProvider) setProvider(loadedProject.aiProvider.toUpperCase());
      })
      .catch((e: unknown) => {
        const status = typeof e === 'object' && e ? Number((e as {status?: number}).status) : 0;
        setLoadError(status === 404 ? 'not-found' : status === 403 ? 'forbidden' : 'error');
      })
      .finally(() => setLoading(false));
    api
      .getProjectRepos(projectId)
      .then((list) => {
        const loadedRepos = (list as unknown as Repo[]) ?? [];
        setRepos(loadedRepos);
        // Project runs are explicit about their code boundary. The previous UI displayed every
        // linked repo as selected while leaving this array empty, so the request omitted
        // selectedRepoIds and the backend created a seed-only workspace.
        setSelected(
          explicitLinkedRepoSelection(
            loadedRepos.filter((repo) => repoSupports(repo, 'CLOUD_RUN')),
          ),
        );
      })
      .catch(() => setRepos([]));
    api
      .getRuns({projectId, size: 6})
      .then((r) => setRuns(r.content ?? []))
      .catch(() => setRuns([]))
      .finally(() => setRunsLoaded(true));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId]);

  useEffect(() => {
    if (tier === 'FREE') setProvider('DEEPSEEK');
  }, [tier]);

  async function submit() {
    if (!spec.trim()) {
      setErr('Describe what to build first.');
      return;
    }
    const readiness = providerReadiness.byProvider.get(provider.toUpperCase());
    if (
      providerReadiness.loading ||
      providerReadiness.error ||
      !providerIsDispatchable(readiness)
    ) {
      setErr(
        providerReadiness.error
          ? 'Provider readiness could not be verified. Retry after the service is available.'
          : providerReadiness.loading
            ? 'Provider readiness is still being checked.'
            : providerReadinessLabel(readiness),
      );
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
      if (repos.length > 0 && selected.length === 0) {
        throw new Error('Select at least one linked repository before starting work.');
      }
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

  if (loadError || !project) {
    const forbidden = loadError === 'forbidden';
    const unavailable = loadError === 'error';
    return (
      <div className='ide-pane'>
        <div className='ide-empty' role='alert'>
          <strong>
            {forbidden
              ? 'Project access denied'
              : unavailable
                ? 'Project unavailable'
                : 'Project not found'}
          </strong>
          <div>
            {forbidden
              ? 'You do not have access to this project.'
              : unavailable
                ? 'The project could not be loaded. Try again shortly.'
                : 'This project does not exist or was removed.'}
          </div>
          <Link to='/projects'>Back to projects</Link>
        </div>
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
  const selectedProviderReadiness = providerReadiness.byProvider.get(provider.toUpperCase());
  const selectedProviderReady = providerIsDispatchable(selectedProviderReadiness);
  const projectProviderOptions =
    tier === 'FREE'
      ? PRODUCT_PROVIDER_OPTIONS.filter((p) => p.id === 'DEEPSEEK')
      : PRODUCT_PROVIDER_OPTIONS;

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
            <span>{selected.length}</span>
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

      <div className='ide-h2'>Start work in this project</div>
      <textarea
        className='ide-textarea'
        rows={8}
        placeholder='Describe what you want, in plain English. Reference repos or files to keep the agent inside specific boundaries.'
        value={spec}
        onChange={(e) => {
          setSpec(e.target.value);
          if (err === 'Describe what to build first.') setErr('');
        }}
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
              const canRun = repoSupports(r, 'CLOUD_RUN');
              return (
                <button
                  key={id}
                  className='ide-badge interactive'
                  disabled={!canRun}
                  title={
                    canRun
                      ? undefined
                      : 'This provider is linked for source browsing and sync; cloud execution is not supported yet.'
                  }
                  style={{
                    cursor: 'pointer',
                    borderColor: on ? 'var(--ide-accent)' : undefined,
                    color: on ? 'var(--ide-accent)' : undefined,
                  }}
                  onClick={() =>
                    canRun && setSelected((s) => (on ? s.filter((x) => x !== id) : [...s, id]))
                  }
                >
                  {on ? '✓ ' : ''}
                  {repoDisplayName(r)}
                  {!canRun ? ' · browse only' : ''}
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
            aria-label='Project run provider'
            value={provider}
            onChange={(e) => setProvider(e.target.value)}
          >
            {projectProviderOptions.map((option) => (
              <option
                key={option.id}
                value={option.id}
                disabled={!providerIsDispatchable(providerReadiness.byProvider.get(option.id))}
              >
                {providerOptionLabel(option.label, providerReadiness.byProvider.get(option.id))}
              </option>
            ))}
          </select>
          <div
            role={selectedProviderReady ? 'status' : 'alert'}
            style={{fontSize: 11, color: 'var(--ide-fg-muted)', marginTop: 4}}
          >
            {providerReadiness.error
              ? 'Provider readiness unavailable; starting work is disabled.'
              : providerReadiness.loading
                ? 'Checking provider readiness…'
                : providerReadinessLabel(selectedProviderReadiness)}
          </div>
        </div>
        <div style={{flex: 1, minWidth: 160}}>
          <div className='ide-h2' style={{marginTop: 0}}>
            Mode
          </div>
          <select
            className='ide-select'
            aria-label='Project run approval mode'
            value={mode}
            onChange={(e) => setMode(e.target.value as 'PROTOTYPE' | 'PRODUCTION')}
          >
            <option value='PRODUCTION'>Production (plan gate)</option>
            <option value='PROTOTYPE'>Prototype (auto)</option>
          </select>
        </div>
      </div>
      {err && <div style={{color: 'var(--ide-red)', fontSize: 13, marginBottom: 10}}>{err}</div>}
      <button
        className='ide-btn'
        onClick={submit}
        disabled={
          busy || providerReadiness.loading || providerReadiness.error || !selectedProviderReady
        }
      >
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
