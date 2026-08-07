import {Check} from 'lucide-react';
import {useEffect, useState} from 'react';
import {Link, Navigate, useLocation, useNavigate, useParams} from 'react-router-dom';
import * as api from '@/lib/api';
import {IdeBadge, IdeLoader, statusKind} from '@/ide/IdeKit';
import {SpecChat} from '@/signal/components/SpecChat';
import {repoDisplayName, repoIdFor, relTime} from '@/signal/lib/runUtils';
import {ProjectTabs, runStatusLabel} from '@/ide/IdeProjectTabs';
import {LoadError} from '@/ide/LoadError';
import {toLoadFailure, type LoadFailure} from '@/lib/loadState';
import {isActiveRunStatus} from '@/lib/runStatus';
import {explicitLinkedRepoSelection, repoSupports} from '@/ide/projectRepoUi';
import {buildIdeComposeRunRequest} from '@/ide/ideComposeRunRequest';
import {
  PRODUCT_PROVIDER_OPTIONS,
  providerIsDispatchable,
  providerOptionLabel,
  providerReadinessLabel,
} from '@/ide/providerReadiness';
import {useProviderReadiness} from '@/ide/useProviderReadiness';
import {useAuthStore, selectNormalizedTier} from '@/stores/useAuthStore';
import {executeRunMutation} from '@/lib/runMutationAttempt';
import {ModelPicker, type ModelPickerOption} from '@/ide/ModelPicker';

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
  // D4 — this effect coordinates three loads against a shared `loading` /
  // `runsLoaded` gate, so it keeps manual state and uses the shared classifier
  // rather than the `useAsyncData` hook.
  const [reposFailure, setReposFailure] = useState<LoadFailure | null>(null);
  const [runsFailure, setRunsFailure] = useState<LoadFailure | null>(null);
  const [selected, setSelected] = useState<string[]>([]);
  const [runs, setRuns] = useState<api.RunSummary[]>([]);
  const [runsLoaded, setRunsLoaded] = useState(false);
  const [spec, setSpec] = useState('');
  const [provider, setProvider] = useState('ANTHROPIC');
  const [modelName, setModelName] = useState('');
  const [modelLabels, setModelLabels] = useState<Record<string, string>>({});
  const [modelsByProvider, setModelsByProvider] = useState<Record<string, string[]>>({});
  const [mode, setMode] = useState<'PROTOTYPE' | 'PRODUCTION'>('PRODUCTION');
  const [confirmModelUpgrades, setConfirmModelUpgrades] = useState(false);
  const [requireBenchmarkedUpgrades, setRequireBenchmarkedUpgrades] = useState(false);
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
        setReposFailure(null);
        // Project runs are explicit about their code boundary. The previous UI displayed every
        // linked repo as selected while leaving this array empty, so the request omitted
        // selectedRepoIds and the backend created a seed-only workspace.
        setSelected(
          explicitLinkedRepoSelection(
            loadedRepos.filter((repo) => repoSupports(repo, 'CLOUD_RUN')),
          ),
        );
      })
      // D4: an empty repo list is a claim about the project's code boundary and
      // it also drives `selected`. Failing to a bare `[]` here silently allowed
      // a run to be composed against no repositories at all.
      .catch((e: unknown) => setReposFailure(toLoadFailure(e)));
    api
      .getRuns({projectId, size: 6})
      .then((r) => {
        setRuns(r.content ?? []);
        setRunsFailure(null);
      })
      .catch((e: unknown) => setRunsFailure(toLoadFailure(e)))
      .finally(() => setRunsLoaded(true));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId]);

  useEffect(() => {
    if (tier === 'FREE') setProvider('DEEPSEEK');
  }, [tier]);

  // Reuse the same Enterprise per-run exact-model contract as the global Compose surface. The
  // selection is never persisted as a project default; it only populates RunRequest.modelName.
  useEffect(() => {
    if (tier !== 'ENTERPRISE') {
      setModelName('');
      setModelLabels({});
      setModelsByProvider({});
      return;
    }
    // `/models/me` is the authenticated run-submitter catalog. Unlike the org-admin matrix
    // endpoint, it is available to Enterprise developers and already applies tier + key/policy
    // readiness. Org overrides remain an admin concern; the run picker needs allowed exact routes.
    api
      .getMyModels()
      .then((models) => {
        const available = models.filter((model) => model.available);
        setModelLabels(Object.fromEntries(available.map((model) => [model.id, model.label])));
        setModelsByProvider(
          available.reduce<Record<string, string[]>>((byProvider, model) => {
            const providerKey = model.provider.toLowerCase();
            (byProvider[providerKey] ||= []).push(model.id);
            return byProvider;
          }, {}),
        );
      })
      .catch(() => {
        setModelLabels({});
        setModelsByProvider({});
      });
  }, [tier]);

  useEffect(() => setModelName(''), [provider]);

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
      if (repos.length > 0 && selected.length === 0) {
        throw new Error('Select at least one linked repository before starting work.');
      }
      const body = buildIdeComposeRunRequest({
        spec,
        provider,
        mode,
        projectId: projectId ?? null,
        selectedRepoIds: selected,
        costCap: 0,
        tddMode: false,
        confirmModelUpgrades,
        requireBenchmarkedUpgrades,
        startLowTasksOnCheapTier: false,
        figmaUrl: '',
        tier,
        modelName,
      });
      const res = await executeRunMutation(
        `create:${projectId || 'standalone'}`,
        body,
        (idempotencyKey) => api.createRun(body, idempotencyKey),
      );
      // RR-1519 — a PRODUCTION run pauses at AWAITING_APPROVAL, and IdeLive has no plan-gate
      // handling, so sending it to /live stranded the user on a live console for a run that is not
      // running and shows no approve/reject control. Route PRODUCTION to the plan gate (which owns
      // the tested approve/cancel flow and forwards already-approved runs), PROTOTYPE to /live.
      navigate(mode === 'PRODUCTION' ? `/plan/${res.runId}` : `/live/${res.runId}`);
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

  // RR-1521 — this used to hardcode six statuses and omitted APPROVAL_RESUMING, WORKING,
  // REVIEWING and the halted states, i.e. most of a run's wall-clock. A project with a run
  // mid-execution therefore reported "Active 0". Active now means "not terminal", derived
  // from the one shared vocabulary.
  const activeRuns = runs.filter((r) => isActiveRunStatus(r.status));
  const lastRun = runs[0];
  const selectedProviderReadiness = providerReadiness.byProvider.get(provider.toUpperCase());
  const selectedProviderReady = providerIsDispatchable(selectedProviderReadiness);
  const projectProviderOptions =
    tier === 'FREE'
      ? PRODUCT_PROVIDER_OPTIONS.filter((p) => p.id === 'DEEPSEEK')
      : PRODUCT_PROVIDER_OPTIONS;
  const modelPickerOptions: ModelPickerOption[] = (() => {
    const providerModels = modelsByProvider[provider.toLowerCase()] || [];
    return [
      {value: '', label: 'Recommended', provider, helper: 'Org / system default'},
      ...providerModels.map((model) => ({
        value: model,
        label: modelLabels[model] || model,
        provider,
        helper: model,
      })),
    ];
  })();

  return (
    <div className='ide-pane'>
      {/* C3 — ProjectTabs renders the Projects › {name} crumbs; the old "←"
          back button is redundant with the crumb trail. */}
      <ProjectTabs projectId={projectId || ''} />
      <h1 className='ide-h1' style={{marginBottom: 4}}>
        {project?.name || 'Project'}
      </h1>
      <div className='ide-sub'>
        {reposFailure
          ? 'Repositories not loaded'
          : `${repos.length} repo${repos.length === 1 ? '' : 's'}`}{' '}
        · {project?.status || 'ACTIVE'}
      </div>

      <div className='ide-project-context-grid'>
        <div className='ide-card'>
          <div className='ide-h2' style={{marginTop: 0}}>
            Code boundary
          </div>
          <div className='ide-kv'>
            <span>Repos connected</span>
            <span>{reposFailure ? 'Unknown' : repos.length}</span>
          </div>
          <div className='ide-kv' style={{borderBottom: 'none'}}>
            <span>Repos selected</span>
            <span>{reposFailure ? 'Unknown' : selected.length}</span>
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
              {lastRun
                ? `${runStatusLabel(lastRun.status)} · ${fmtWhen(lastRun.startedAt)}`
                : 'No work yet'}
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

      {reposFailure && (
        <>
          <div className='ide-h2'>Repos the agent may touch</div>
          <LoadError failure={reposFailure} what='linked repositories' />
        </>
      )}
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
                  {on ? (
                    <Check
                      size={13}
                      strokeWidth={2.25}
                      aria-hidden='true'
                      style={{display: 'inline-block', verticalAlign: '-0.15em', marginRight: 4}}
                    />
                  ) : null}
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
            style={{fontSize: 12, color: 'var(--ide-fg-muted)', marginTop: 4}}
          >
            {providerReadiness.error
              ? 'Provider readiness unavailable; starting work is disabled.'
              : providerReadiness.loading
                ? 'Checking provider readiness…'
                : providerReadinessLabel(selectedProviderReadiness)}
          </div>
        </div>
        {tier === 'ENTERPRISE' && (
          <div style={{flex: 1, minWidth: 220}} role='group' aria-labelledby='project-model-label'>
            <div className='ide-h2' id='project-model-label' style={{marginTop: 0}}>
              Model · Beta
            </div>
            <ModelPicker
              ariaLabel='Project run model'
              value={modelName}
              options={modelPickerOptions}
              onChange={setModelName}
            />
            <div style={{fontSize: 12, color: 'var(--ide-fg-muted)', marginTop: 4}}>
              Overrides this run only
            </div>
          </div>
        )}
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
      <details className='ide-compose-advanced' style={{marginBottom: 14}}>
        <summary>Recovery policy for this run</summary>
        <div className='ide-compose-options'>
          <label
            style={{display: 'flex', alignItems: 'center', gap: 8, fontSize: 13, cursor: 'pointer'}}
          >
            <input
              type='checkbox'
              checked={confirmModelUpgrades}
              onChange={(e) => setConfirmModelUpgrades(e.target.checked)}
            />
            Confirm model or provider changes
          </label>
          <label
            style={{display: 'flex', alignItems: 'center', gap: 8, fontSize: 13, cursor: 'pointer'}}
          >
            <input
              type='checkbox'
              checked={requireBenchmarkedUpgrades}
              onChange={(e) => setRequireBenchmarkedUpgrades(e.target.checked)}
            />
            Require task-relevant benchmark evidence
          </label>
          <div style={{fontSize: 12, color: 'var(--ide-fg-muted)'}}>
            Automatic recovery can use one bounded model or provider change when both controls are
            off. Enterprise runs remain limited to routes allowed by the organization model matrix.
            Exact model pins never change route automatically.
          </div>
        </div>
      </details>
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
      {runsFailure ? (
        <LoadError failure={runsFailure} what='work in this project' />
      ) : runs.length === 0 ? (
        <div className='ide-empty'>No work yet.</div>
      ) : (
        <div className='ide-grid'>
          {runs.map((r) => (
            <Link key={r.runId} className='ide-run-card' to={`/result/${r.runId}`}>
              <div className='ide-run-title'>
                {(r.runTitle || r.userRequest || r.runId).slice(0, 70)}
              </div>
              <div className='ide-run-meta'>
                <IdeBadge kind={statusKind(r.status)}>{runStatusLabel(r.status)}</IdeBadge>
                <span>{fmtWhen(r.startedAt)}</span>
              </div>
            </Link>
          ))}
        </div>
      )}
    </div>
  );
}
