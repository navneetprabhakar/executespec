import {useEffect, useMemo, useRef, useState} from 'react';
import {Link, useNavigate, useParams} from 'react-router-dom';
import * as api from '@/lib/api';
import {IdeBadge, IdeLoader, statusKind} from '@/ide/IdeKit';
import {SpecChat} from '@/signal/components/SpecChat';
import {CostProgressBar} from '@/signal/components/CostProgressBar';
import {ConfirmModal} from '@/signal/components/Modal';
import {repoDisplayName, repoIdFor, relTime, providerLabel} from '@/signal/lib/runUtils';
import {useIsMobile} from '@/hooks/useIsMobile';
import {useAuthStore, selectNormalizedTier} from '@/stores/useAuthStore';
import {useSse} from '@/hooks/useSse';
import {useSseStore} from '@/stores/useSseStore';

// Relative timestamps everywhere (review/11 #8) — `toLocaleString()` was
// locale-ambiguous (DD/MM vs MM/DD), carried seconds noise, and clashed with
// the Console chrome's relative times. Hover for the absolute timestamp.
function fmtWhen(iso?: string | null): string {
  return relTime(iso);
}
function runLabel(r: {
  runTitle?: string | null;
  userRequest?: string | null;
  runId: string;
}): string {
  return (r.runTitle || r.userRequest || r.runId).slice(0, 80);
}
function shortRunId(id?: string): string {
  return id ? `R-${id.slice(0, 6)}` : 'R-—';
}
function formatElapsed(ms?: number | null): string {
  if (!ms || ms < 0) return '0s';
  const totalS = Math.floor(ms / 1000);
  const m = Math.floor(totalS / 60);
  const s = totalS % 60;
  return m === 0 ? `${s}s` : `${m}m ${s}s`;
}
// RunDetail has no durationMs — derive elapsed from startedAt → completedAt|now.
function runElapsedMs(
  r?: {startedAt?: string | null; completedAt?: string | null} | null,
): number | null {
  if (!r?.startedAt) return null;
  const end = r.completedAt ? new Date(r.completedAt).getTime() : Date.now();
  return end - new Date(r.startedAt).getTime();
}
const NON_TERMINAL = new Set([
  'CLASSIFYING',
  'PLANNING',
  'EXECUTING',
  'RUNNING',
  'AWAITING_APPROVAL',
  'PROVIDER_SWITCHING',
  'REVIEWING',
  'PAUSED',
]);
const FALLBACK_PROVIDER_OPTIONS: api.ProviderEntry[] = [
  {id: 'ANTHROPIC', label: 'Anthropic', models: []},
  {id: 'OPENAI', label: 'OpenAI', models: []},
  {id: 'GEMINI', label: 'Gemini', models: []},
  {id: 'DEEPSEEK', label: 'DeepSeek', models: []},
];
const ENTERPRISE_PROVIDER_IDS = new Set(['OPENROUTER', 'FIREWORKS']);
function managedKind(state?: string | null): 'ok' | 'warn' | 'bad' {
  if (state === 'EXHAUSTED') return 'bad';
  if (state === 'CHEAP_ONLY' || state === 'FRONTIER_LOCKED' || state === 'VALUE_MODE')
    return 'warn';
  return 'ok';
}
function isLiveStatus(s?: string | null): boolean {
  return NON_TERMINAL.has((s || '').toUpperCase());
}
// C6 (review/11) — quota subtitle segment shared by IdeWelcome + IdeCompose.
// Never emits a leading separator; unlimited tiers say so in words instead of
// the "0 / ∞ runs" jargon, and "this cycle" became "this month".
function quotaSubtitle(
  tier: string,
  _permissions: {monthlyRunLimit: number; currentRunCount: number} | null,
): string {
  // Run-count retirement (2026-06-22): monthly run caps no longer gate usage — managed ES AI
  // credits do (surfaced on the billing / top-up pages, and enforced server-side at submit).
  // The subtitle stays at the tier so it never shows a stale run cap.
  return tier;
}
// Map a backend run status to the human phase the user actually sees on screen.
function phaseLabel(status?: string | null): string {
  const s = (status || '').toUpperCase();
  if (s === 'CLASSIFYING') return 'Classifying';
  if (s === 'PLANNING') return 'Planning';
  if (s === 'AWAITING_APPROVAL') return 'Awaiting approval';
  if (s === 'REVIEWING') return 'Reviewing changes';
  if (s === 'PROVIDER_SWITCHING') return 'Switching provider';
  if (s === 'PAUSED') return 'Paused';
  if (s === 'COMPLETED' || s === 'COMPLETE') return 'Completed';
  if (s === 'PARTIAL') return 'Completed with warnings';
  if (s === 'FAILED' || s === 'ERROR') return 'Failed';
  if (s === 'CANCELLED') return 'Cancelled';
  return 'Running now';
}

export function IdeWelcome() {
  const navigate = useNavigate();
  const isMobile = useIsMobile();
  const tier = useAuthStore(selectNormalizedTier);
  const permissions = useAuthStore((s) => s.permissions);
  const [runs, setRuns] = useState<api.RunSummary[] | null>(null);
  const [projects, setProjects] = useState<api.ProjectDto[] | null>(null);
  const [liveRun, setLiveRun] = useState<api.RunDetail | null>(null);
  const [samples, setSamples] = useState<api.SampleSpecDto[]>([]);
  const [sampleBusyId, setSampleBusyId] = useState<string | null>(null);

  // Surface the monthly run cap (mirrors HomePage). Permissions are loaded on
  // login; fetch lazily if the store hasn't been hydrated yet.
  useEffect(() => {
    if (!permissions) {
      api
        .getMyPermissions()
        .then((p) => useAuthStore.setState({permissions: p}))
        .catch(() => {});
    }
  }, [permissions]);

  useEffect(() => {
    let cancelled = false;
    api
      .getRuns({size: 12})
      .then(async (page) => {
        if (cancelled) return;
        const list = page.content ?? [];
        setRuns(list);
        // LiveHero: hydrate full detail for the most recent in-flight run.
        const liveSummary = list.find((r) => isLiveStatus(r.status));
        if (liveSummary) {
          try {
            const detail = await api.getRun(liveSummary.runId);
            if (!cancelled) setLiveRun(detail);
          } catch {
            if (!cancelled) setLiveRun(null);
          }
        } else if (!cancelled) {
          setLiveRun(null);
        }
      })
      .catch(() => !cancelled && setRuns([]));
    api
      .getProjects()
      .then((p) => !cancelled && setProjects(p.filter((x) => !x.archivedAt)))
      .catch(() => !cancelled && setProjects([]));
    return () => {
      cancelled = true;
    };
  }, []);

  // First-run onboarding (2026-06-10): the /welcome sandbox picker was only
  // reachable by typing the URL, so brand-new users landed on an empty IDE.
  // Surface the same starter samples inline while there are no runs yet.
  useEffect(() => {
    api
      .listOnboardingSamples()
      .then(setSamples)
      .catch(() => setSamples([]));
  }, []);

  async function startFromSample(s: api.SampleSpecDto) {
    setSampleBusyId(s.id);
    try {
      const picked = await api.pickOnboardingSample(s.id);
      sessionStorage.setItem('signal:pending-spec', picked.spec || s.title);
    } catch {
      sessionStorage.setItem('signal:pending-spec', s.title);
    } finally {
      setSampleBusyId(null);
    }
    navigate('/compose');
  }

  const runCap = quotaSubtitle(tier, permissions);

  const liveTasks = liveRun?.tasks ?? [];
  const liveDone = liveTasks.filter((t) => t.status === 'COMPLETED' || t.status === 'DONE').length;
  const liveSpent = liveRun?.actualCostUsd ?? 0;

  return (
    <div className='ide-pane'>
      <div className='ide-h1'>Workspace</div>
      <div className='ide-sub'>
        {/* ⌘K hint suppressed on touch layouts (review/11 #9). */}
        Spec-driven runs across your repos. {runCap}.{isMobile ? '' : ' Press ⌘K to jump anywhere.'}
      </div>

      {liveRun && (
        <div className='ide-card' style={{borderColor: 'var(--ide-accent)', marginBottom: 14}}>
          <div style={{display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8}}>
            <span
              style={{
                width: 8,
                height: 8,
                borderRadius: '50%',
                background: 'var(--ide-accent)',
                boxShadow: '0 0 8px var(--ide-accent)',
              }}
            />
            <span className='ide-mono' style={{fontSize: 11, color: 'var(--ide-fg-muted)'}}>
              {shortRunId(liveRun.runId)}
            </span>
            <IdeBadge kind={statusKind(liveRun.status)}>{phaseLabel(liveRun.status)}</IdeBadge>
          </div>
          <div className='ide-run-title' style={{marginBottom: 8}}>
            {(liveRun.userRequest || liveRun.runTitle || 'Run in progress').slice(0, 120)}
          </div>
          <div className='ide-run-meta' style={{marginBottom: 12}}>
            <span>{liveTasks.length ? `${liveDone}/${liveTasks.length} tasks` : 'warming up'}</span>
            <span>·</span>
            <span>{formatElapsed(runElapsedMs(liveRun))}</span>
            <span>·</span>
            <span>${liveSpent.toFixed(2)}</span>
          </div>
          <div style={{display: 'flex', gap: 8}}>
            <Link className='ide-btn ghost' to={`/plan/${liveRun.runId}`}>
              ◷ Plan
            </Link>
            <Link className='ide-btn' to={`/live/${liveRun.runId}`}>
              ◉ Watch live
            </Link>
          </div>
        </div>
      )}

      <div style={{display: 'flex', gap: 10, marginBottom: 8}}>
        <Link className='ide-btn' to='/compose'>
          ＋ Start work
        </Link>
        <Link className='ide-btn ghost' to='/runs'>
          ◎ Work
        </Link>
      </div>

      <div className='ide-h2'>Recent work</div>
      {runs === null ? (
        <IdeLoader label='Loading work…' />
      ) : runs.length === 0 ? (
        <div className='ide-card' style={{marginBottom: 8}}>
          <div className='ide-run-title' style={{marginBottom: 6}}>
            Ship your first verified PR
          </div>
          <div className='ide-sub' style={{marginBottom: 10}}>
            Pick a starter work request — it takes a few minutes — or write your own. Starters run
            in a sandbox project · 1 credit (≈$5 of model spend) · no card needed.
          </div>
          {samples.length > 0 && (
            <div style={{display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 10}}>
              {samples.map((s) => (
                <button
                  key={s.id}
                  className='ide-btn ghost'
                  disabled={sampleBusyId != null}
                  onClick={() => void startFromSample(s)}
                  title={`${s.category} · ${s.complexity}`}
                >
                  {sampleBusyId === s.id ? 'Preparing…' : s.title}
                </button>
              ))}
            </div>
          )}
          <div style={{display: 'flex', gap: 8, flexWrap: 'wrap'}}>
            <Link className='ide-btn' to='/compose'>
              ＋ Write my own spec
            </Link>
            <Link className='ide-btn ghost' to='/integrations'>
              ⚙ Connect GitHub for real-repo PRs
            </Link>
          </div>
        </div>
      ) : (
        <div className='ide-grid'>
          {runs.map((r) => (
            <Link
              key={r.runId}
              className='ide-run-card'
              to={isLiveStatus(r.status) ? `/live/${r.runId}` : `/result/${r.runId}`}
            >
              <div className='ide-run-title'>{runLabel(r)}</div>
              <div className='ide-run-meta'>
                <IdeBadge kind={statusKind(r.status)}>{r.status}</IdeBadge>
                {/* Lead with the verified score — the product's signature metric
                    (review/11 §4) — instead of provider internals. */}
                {typeof r.verifiedScore === 'number' && (
                  <IdeBadge
                    kind={r.verifiedScore >= 85 ? 'ok' : r.verifiedScore >= 70 ? 'warn' : 'bad'}
                  >
                    {r.verifiedScore}
                  </IdeBadge>
                )}
                {typeof r.actualCostUsd === 'number' && (
                  <span className='ide-mono' style={{color: 'var(--ide-fg-muted)'}}>
                    ${r.actualCostUsd.toFixed(2)}
                  </span>
                )}
                {r.aiProvider && <span>{providerLabel(r.aiProvider)}</span>}
                <span title={r.startedAt ? new Date(r.startedAt).toLocaleString() : undefined}>
                  {fmtWhen(r.startedAt)}
                </span>
              </div>
            </Link>
          ))}
        </div>
      )}

      <div className='ide-h2'>Projects</div>
      {projects === null ? (
        <IdeLoader label='Loading projects…' />
      ) : projects.length === 0 ? (
        <div className='ide-empty'>No projects yet.</div>
      ) : (
        <div className='ide-grid'>
          {projects.map((p) => (
            <Link key={p.id} className='ide-run-card' to={`/projects/${p.id}`}>
              <div className='ide-run-title'>▤ {p.name}</div>
              <div className='ide-run-meta'>{p.description || 'No description'}</div>
            </Link>
          ))}
        </div>
      )}
    </div>
  );
}

// Compose (new spec)
export function IdeCompose() {
  const navigate = useNavigate();
  const tier = useAuthStore(selectNormalizedTier);
  const permissions = useAuthStore((s) => s.permissions);
  const user = useAuthStore((s) => s.user);

  const [spec, setSpec] = useState('');
  const [provider, setProvider] = useState('ANTHROPIC');
  // Per-run provider+model pin (Beta, Enterprise-only — 2026-05-22). '' = Default (org/system
  // matrix), i.e. send no modelName. modelsByProvider keys are lowercase provider names.
  const [modelName, setModelName] = useState('');
  const [managedAi, setManagedAi] = useState<api.ManagedAiBalance | null>(null);
  const [providerOptions, setProviderOptions] = useState<api.ProviderEntry[]>([]);
  const [modelLabels, setModelLabels] = useState<Record<string, string>>({});
  const [modelsByProvider, setModelsByProvider] = useState<Record<string, string[]>>({});
  const [mode, setMode] = useState<'PROTOTYPE' | 'PRODUCTION'>('PRODUCTION');
  const [tddMode, setTddMode] = useState(false);
  const [confirmModelUpgrades, setConfirmModelUpgrades] = useState(false);
  const [requireBenchmarkedUpgrades, setRequireBenchmarkedUpgrades] = useState(false);
  const [figmaUrl, setFigmaUrl] = useState('');
  const [projects, setProjects] = useState<api.ProjectDto[]>([]);
  const [projectId, setProjectId] = useState<string | null>(null);
  // RUN-1 (review/11) — searchable project picker. The chip wall rendered every
  // project; with ~50 projects the Run button landed screens below the fold.
  const [projectPickerOpen, setProjectPickerOpen] = useState(false);
  const [projectQuery, setProjectQuery] = useState('');
  const [projectRepos, setProjectRepos] = useState<Array<Record<string, unknown>>>([]);
  const [selectedRepoIds, setSelectedRepoIds] = useState<string[]>([]);
  // Per-tier max hard cap, mirroring TierEnforcementService.resolveRunCostBudget:
  // FREE $3 · PRO $5 (BYOK lifts it server-side) · TEAM/ENTERPRISE have no platform
  // cap, so the input there only sets a voluntary ceiling.
  const tierMaxCap = tier === 'FREE' ? 3 : tier === 'PRO' ? 5 : tier === 'ENTERPRISE' ? 1000 : 25;
  const [costCap, setCostCap] = useState<number>(Math.min(2, tierMaxCap));
  const [estimate, setEstimate] = useState<api.LaunchEstimate | null>(null);
  // RUN-7 (review/11) — estimate freshness: timestamp of the last fetch + a
  // "stale" flag that flips after 60s and surfaces a refresh link-button.
  const [estimateAt, setEstimateAt] = useState<number | null>(null);
  const [estimateStale, setEstimateStale] = useState(false);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');
  const [samples, setSamples] = useState<api.SampleSpecDto[]>([]);
  const [sampleBusyId, setSampleBusyId] = useState<string | null>(null);
  const [mobileStep, setMobileStep] = useState<1 | 2 | 3 | 4>(1);

  // Compose handoff — a discovery surface (library / templates / chat compile)
  // may stash a pre-filled spec under `signal:pending-spec` before navigating
  // here. Consume it once on mount so the textarea pre-fills, mirroring the
  // Signal ComposePage behaviour.
  useEffect(() => {
    const pending = sessionStorage.getItem('signal:pending-spec');
    if (pending) {
      setSpec(pending);
      sessionStorage.removeItem('signal:pending-spec');
    }
  }, []);

  useEffect(() => {
    if (!permissions) {
      api
        .getMyPermissions()
        .then((p) => useAuthStore.setState({permissions: p}))
        .catch(() => {});
    }
    api
      .getProjects()
      .then((p) => setProjects(p.filter((x) => !x.archivedAt)))
      .catch(() => {});
  }, [permissions]);

  useEffect(() => {
    api
      .getAvailableModels()
      .then((m) => setProviderOptions(m.providers || []))
      .catch(() => setProviderOptions([]));
  }, []);

  useEffect(() => {
    api
      .getManagedAiBalance()
      .then(setManagedAi)
      .catch(() => setManagedAi(null));
  }, []);

  // Onboarding sample specs — a quick "start from a sample" row.
  useEffect(() => {
    api
      .listOnboardingSamples()
      .then(setSamples)
      .catch(() => setSamples([]));
  }, []);

  // Per-run provider+model pin (Beta, Enterprise-only — 2026-05-22): load the registered models
  // per provider so the ENTERPRISE-only model selector can offer them. modelsByProvider keys are
  // lowercase provider names (e.g. 'anthropic'). Best-effort — selector just hides if it fails.
  useEffect(() => {
    if (tier !== 'ENTERPRISE') {
      setModelsByProvider({});
      setModelLabels({});
      setModelName('');
      return;
    }
    api
      .getOrgModelMatrix()
      .then((m) => {
        setModelsByProvider(m.modelsByProvider || {});
        setModelLabels(m.modelLabels || {});
      })
      .catch(() => {
        setModelsByProvider({});
        setModelLabels({});
      });
  }, [tier]);

  // Reset the per-run model pin to "Default" whenever the provider changes — a model is only valid
  // for its own provider, so a stale selection must not leak across a provider switch.
  useEffect(() => {
    setModelName('');
  }, [provider]);

  useEffect(() => {
    if (user?.preferredAiProvider) {
      setProvider(user.preferredAiProvider.toUpperCase());
    }
  }, [user?.preferredAiProvider]);

  function chooseProvider(next: string) {
    const normalized = next.toUpperCase();
    setProvider(normalized);
    if (user?.preferredAiProvider?.toUpperCase() !== normalized) {
      useAuthStore.setState({user: user ? {...user, preferredAiProvider: normalized} : user});
      api.updateProviderPreference(normalized).catch(() => {
        /* Run submit will still send the selected provider; preference sync is best-effort. */
      });
    }
  }

  async function applySample(sample: api.SampleSpecDto) {
    setSampleBusyId(sample.id);
    setErr('');
    try {
      const picked = await api.pickOnboardingSample(sample.id);
      setSpec(picked.spec || sample.title);
      if (picked.preferredProvider) setProvider(picked.preferredProvider.toUpperCase());
    } catch {
      // Backend tracking is best-effort; still let the user start from the title.
      setSpec(sample.title);
    } finally {
      setSampleBusyId(null);
    }
  }

  // Load repos when the project changes; default to all selected.
  useEffect(() => {
    if (!projectId) {
      setProjectRepos([]);
      setSelectedRepoIds([]);
      return;
    }
    api
      .getProjectRepos(projectId)
      .then((rs) => {
        const list = rs as unknown as Array<Record<string, unknown>>;
        setProjectRepos(list);
        setSelectedRepoIds(list.map((r) => repoIdFor(r)).filter(Boolean));
      })
      .catch(() => {
        setProjectRepos([]);
        setSelectedRepoIds([]);
      });
  }, [projectId]);

  // Debounced p25/p75 estimate. Only meaningful inside a project (the
  // estimator pulls from project history).
  useEffect(() => {
    if (!projectId || spec.trim().length < 12) {
      setEstimate(null);
      setEstimateAt(null);
      return;
    }
    const t = setTimeout(() => {
      api
        .estimateRun(projectId, spec.trim())
        .then((e) => {
          setEstimate(e);
          setEstimateAt(Date.now());
        })
        .catch(() => {
          setEstimate(null);
          setEstimateAt(null);
        });
    }, 600);
    return () => clearTimeout(t);
  }, [projectId, spec]);

  // RUN-7 — flip the estimate card's freshness line to a refresh affordance
  // 60s after the last fetch.
  useEffect(() => {
    setEstimateStale(false);
    if (estimateAt == null) return;
    const t = window.setTimeout(() => setEstimateStale(true), 60_000);
    return () => clearTimeout(t);
  }, [estimateAt]);

  // RUN-7 — manual refresh re-runs the existing estimate fetch immediately.
  function refreshEstimate() {
    if (!projectId || spec.trim().length < 12) return;
    api
      .estimateRun(projectId, spec.trim())
      .then((e) => {
        setEstimate(e);
        setEstimateAt(Date.now());
      })
      .catch(() => {
        /* keep the last estimate on a failed refresh */
      });
  }

  // RUN-1 — picker derivations: Sandbox + the 4 most recently created projects
  // stay visible as chips; everything else lives behind the searchable panel.
  // A selected project outside the top-4 is appended so it stays visible.
  const recentProjects = useMemo(
    () =>
      [...projects]
        .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())
        .slice(0, 4),
    [projects],
  );
  const chipProjects = useMemo(() => {
    const selected = projectId ? projects.find((p) => p.id === projectId) : null;
    if (selected && !recentProjects.some((p) => p.id === selected.id)) {
      return [...recentProjects, selected];
    }
    return recentProjects;
  }, [projects, projectId, recentProjects]);
  const filteredProjects = useMemo(() => {
    const needle = projectQuery.trim().toLowerCase();
    if (!needle) return projects;
    return projects.filter((p) => p.name.toLowerCase().includes(needle));
  }, [projects, projectQuery]);

  const selectableProviders = useMemo(() => {
    const options = providerOptions.length > 0 ? providerOptions : FALLBACK_PROVIDER_OPTIONS;
    return options
      .filter((p) => tier === 'ENTERPRISE' || !ENTERPRISE_PROVIDER_IDS.has(p.id.toUpperCase()))
      .map((p) => ({...p, id: p.id.toUpperCase()}));
  }, [providerOptions, tier]);

  const modelLabelById = useMemo(() => {
    const labels: Record<string, string> = {...modelLabels};
    for (const p of providerOptions) {
      for (const m of p.models || []) {
        if (m.label) labels[m.id] = m.label;
      }
    }
    return labels;
  }, [modelLabels, providerOptions]);

  const providerModels = modelsByProvider[provider.toLowerCase()] || [];
  const managedAiMessage =
    managedAi?.routingState === 'EXHAUSTED'
      ? 'Managed-AI cap is exhausted. New managed-key model calls will be blocked.'
      : managedAi?.routingState === 'CHEAP_ONLY'
        ? 'Cheap-only mode is active. Frontier and balanced calls can step down during execution.'
        : managedAi?.routingState === 'FRONTIER_LOCKED'
          ? 'Frontier models are paused for this cycle; lower-cost routed models remain available.'
          : managedAi?.routingState === 'VALUE_MODE'
            ? 'Value mode is active. New work will prefer lower-cost models when quality is comparable.'
            : managedAi
              ? `${managedAi.aiCreditsRemaining} ES AI credits left this cycle.`
              : 'Managed-AI balance unavailable; backend policy still applies.';

  function pickProject(id: string | null) {
    setProjectId(id);
    setProjectPickerOpen(false);
    setProjectQuery('');
  }

  function toggleRepo(repoId: string) {
    setSelectedRepoIds((prev) =>
      prev.includes(repoId) ? prev.filter((x) => x !== repoId) : [...prev, repoId],
    );
  }

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
      };
      if (projectId) body.projectId = projectId;
      if (selectedRepoIds.length > 0) body.selectedRepoIds = selectedRepoIds;
      if (costCap > 0) body.userCostCap = costCap;
      if (tddMode) body.tddMode = true;
      if (confirmModelUpgrades) body.requireConfirmationBeforeExpensiveContinuation = true;
      body.requireBenchmarkPriorForAutomaticContinuation = requireBenchmarkedUpgrades;
      if (figmaUrl.trim()) body.figmaUrl = figmaUrl.trim();
      // Per-run provider+model pin (Beta, Enterprise-only — 2026-05-22). Only ENTERPRISE may pin,
      // and '' means "Default (org / system matrix)" → send no modelName at all.
      if (tier === 'ENTERPRISE' && modelName) body.modelName = modelName;
      const res = await api.createRun(body);
      navigate(`/plan/${res.runId}`);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Submit failed.');
      setBusy(false);
    }
  }

  const selectedProjectName = projectId
    ? projects.find((p) => p.id === projectId)?.name || 'Selected project'
    : 'Sandbox';
  const mobileCanAdvanceFromSpec = spec.trim().length > 0;
  const mobileNextBlocked = mobileStep === 1 && !mobileCanAdvanceFromSpec;

  return (
    <div className='ide-pane ide-compose' style={{maxWidth: 1060}}>
      <h1 className='ide-h1'>Start work</h1>
      <div className='ide-sub'>
        Describe the change. We will ask before coding and keep the technical defaults out of the
        way. {quotaSubtitle(tier, permissions)}
      </div>

      <div className='ide-compose-steps' aria-label='New work steps'>
        {[
          [1, 'Request'],
          [2, 'Context'],
          [3, 'Setup'],
          [4, 'Review'],
        ].map(([step, label]) => (
          <button
            key={step}
            type='button'
            className={mobileStep === step ? 'active' : ''}
            onClick={() => setMobileStep(step as 1 | 2 | 3 | 4)}
          >
            <span>{step}</span>
            {label}
          </button>
        ))}
      </div>

      <div className='ide-compose-layout'>
        <div className='ide-compose-main'>
          <section className={`ide-compose-section${mobileStep === 1 ? ' active' : ''}`}>
            <div className='ide-card ide-compose-request'>
              <div className='ide-h2' style={{marginTop: 0}}>
                What should change?
              </div>
              <textarea
                className='ide-textarea ide-compose-textarea'
                rows={8}
                placeholder='e.g. Add a health endpoint that returns build SHA and uptime, with tests.'
                value={spec}
                onChange={(e) => setSpec(e.target.value)}
              />
              <div style={{marginTop: 10}}>
                <SpecChat projectId={projectId || undefined} draftSpec={spec} onApply={setSpec} />
              </div>
            </div>
          </section>

          <section className={`ide-compose-section${mobileStep === 2 ? ' active' : ''}`}>
            <div className='ide-card'>
              <div className='ide-h2' style={{marginTop: 0}}>
                Context
              </div>
              <div style={{display: 'flex', flexWrap: 'wrap', gap: 8, marginBottom: 6}}>
                <button
                  className={projectId == null ? 'ide-btn' : 'ide-btn ghost'}
                  onClick={() => pickProject(null)}
                >
                  {projectId == null ? '✓ ' : ''}Sandbox
                </button>
                {chipProjects.map((p) => (
                  <button
                    key={p.id}
                    className={projectId === p.id ? 'ide-btn' : 'ide-btn ghost'}
                    onClick={() => pickProject(p.id)}
                  >
                    {projectId === p.id ? '✓ ' : ''}
                    {p.name}
                  </button>
                ))}
                {projects.length > 4 && (
                  <button
                    className='ide-btn ghost'
                    aria-expanded={projectPickerOpen}
                    onClick={() => {
                      setProjectPickerOpen((v) => !v);
                      setProjectQuery('');
                    }}
                  >
                    All projects... ({projects.length})
                  </button>
                )}
              </div>
              {projectPickerOpen && (
                <div
                  className='ide-card'
                  style={{marginBottom: 8, padding: 10}}
                  onKeyDown={(e) => {
                    if (e.key === 'Escape') setProjectPickerOpen(false);
                  }}
                >
                  <input
                    className='ide-input'
                    autoFocus
                    placeholder='Search projects...'
                    value={projectQuery}
                    onChange={(e) => setProjectQuery(e.target.value)}
                    aria-label='Search projects'
                    style={{marginBottom: 8}}
                  />
                  <div style={{maxHeight: 240, overflowY: 'auto'}}>
                    {filteredProjects.length === 0 ? (
                      <div style={{fontSize: 13, color: 'var(--ide-fg-muted)', padding: '8px 4px'}}>
                        No projects match "{projectQuery.trim()}".
                      </div>
                    ) : (
                      filteredProjects.map((p, i) => (
                        <button
                          key={p.id}
                          type='button'
                          onClick={() => pickProject(p.id)}
                          style={{
                            display: 'block',
                            width: '100%',
                            textAlign: 'left',
                            background: 'transparent',
                            border: 'none',
                            borderBottom:
                              i < filteredProjects.length - 1
                                ? '1px solid var(--ide-border)'
                                : 'none',
                            padding: '8px 6px',
                            fontSize: 13,
                            color: projectId === p.id ? 'var(--ide-accent)' : 'var(--ide-fg)',
                            cursor: 'pointer',
                          }}
                        >
                          {projectId === p.id ? '✓ ' : ''}
                          {p.name}
                        </button>
                      ))
                    )}
                  </div>
                </div>
              )}
              <div className='ide-sub' style={{marginTop: 0, marginBottom: 0}}>
                {projectId
                  ? `${projects.find((p) => p.id === projectId)?.name}. ${selectedRepoIds.length} ${selectedRepoIds.length === 1 ? 'repo' : 'repos'} selected.`
                  : 'Sandbox creates an isolated run without touching a repo.'}
              </div>
            </div>

            {samples.length > 0 && (
              <details className='ide-card ide-compose-samples ide-compose-advanced'>
                <summary>Examples</summary>
                <div style={{display: 'flex', flexWrap: 'wrap', gap: 8, marginTop: 12}}>
                  {samples.map((s) => (
                    <button
                      key={s.id}
                      className='ide-btn ghost'
                      disabled={sampleBusyId != null}
                      onClick={() => void applySample(s)}
                      title={`${s.category} · ${s.complexity}`}
                    >
                      {sampleBusyId === s.id ? '...' : s.title}
                    </button>
                  ))}
                </div>
              </details>
            )}

            {projectId && projectRepos.length > 0 && (
              <div className='ide-card'>
                <div className='ide-h2' style={{marginTop: 0}}>
                  Repos
                </div>
                <div style={{display: 'flex', flexWrap: 'wrap', gap: 8}}>
                  {projectRepos.map((r) => {
                    const rId = repoIdFor(r);
                    const selected = selectedRepoIds.includes(rId);
                    return (
                      <button
                        key={rId}
                        className={selected ? 'ide-btn' : 'ide-btn ghost'}
                        onClick={() => toggleRepo(rId)}
                      >
                        {selected ? '✓ ' : ''}
                        {repoDisplayName(r)}
                      </button>
                    );
                  })}
                </div>
              </div>
            )}

            {/* PAT pre-flight (2026-06-10): GitHub pushes use the CALLER's own PAT (no
          system PAT since 2026-05-23). Without one, a project run completes but
          the PR push fails — previously discovered only on the result page. */}
            {projectId != null && user != null && !user.hasGithubPat && (
              <div
                className='ide-card'
                style={{
                  borderColor: 'var(--ide-yellow)',
                  fontSize: 13,
                  lineHeight: 1.5,
                }}
              >
                No GitHub token connected. The run can generate code, but it cannot open a PR until
                you add one.{' '}
                <Link to='/integrations' style={{color: 'var(--ide-accent)', fontWeight: 600}}>
                  Connect GitHub →
                </Link>
              </div>
            )}
          </section>
        </div>

        <aside className='ide-compose-side'>
          {/* RUN-4 — grid (3 or 4 cols depending on the ENTERPRISE model column) that
          .ide-stack-mobile collapses to one column under 600px. */}
          <section className={`ide-compose-section${mobileStep === 3 ? ' active' : ''}`}>
            <div className='ide-card'>
              <div className='ide-h2' style={{marginTop: 0}}>
                Run setup
              </div>
              <div className='ide-compose-control-grid'>
                <div role='group' aria-labelledby='compose-provider-label' style={{minWidth: 0}}>
                  <div className='ide-field-label' id='compose-provider-label'>
                    Provider
                  </div>
                  <select
                    className='ide-select'
                    value={provider}
                    onChange={(e) => chooseProvider(e.target.value)}
                  >
                    {selectableProviders.map((p) => (
                      <option key={p.id} value={p.id}>
                        {p.label || providerLabel(p.id)}
                        {p.tag ? ` · ${p.tag}` : ''}
                      </option>
                    ))}
                  </select>
                </div>
                {/* Per-run provider+model pin (Beta, Enterprise-only — 2026-05-22). Overrides org &
                  default matrix for this run only. '' = Default → no modelName sent. */}
                {tier === 'ENTERPRISE' && (
                  <div role='group' aria-labelledby='compose-model-label' style={{minWidth: 0}}>
                    <div className='ide-field-label' id='compose-model-label'>
                      Model · Beta
                    </div>
                    <select
                      className='ide-select'
                      value={modelName}
                      onChange={(e) => setModelName(e.target.value)}
                    >
                      <option value=''>Recommended</option>
                      {providerModels.map((m) => (
                        <option key={m} value={m}>
                          {modelLabelById[m] || m}
                        </option>
                      ))}
                    </select>
                    <div style={{fontSize: 11, color: 'var(--ide-fg-muted)', marginTop: 4}}>
                      Overrides this run only
                    </div>
                  </div>
                )}
                <div role='group' aria-labelledby='compose-mode-label' style={{minWidth: 0}}>
                  <div className='ide-field-label' id='compose-mode-label'>
                    Approval
                  </div>
                  <select
                    className='ide-select'
                    value={mode}
                    onChange={(e) => setMode(e.target.value as 'PROTOTYPE' | 'PRODUCTION')}
                  >
                    <option value='PRODUCTION'>Ask before coding</option>
                    <option value='PROTOTYPE'>Start automatically</option>
                  </select>
                </div>
                <div role='group' aria-labelledby='compose-cap-label' style={{minWidth: 0}}>
                  <div className='ide-field-label' id='compose-cap-label'>
                    Cap
                  </div>
                  <div style={{display: 'flex', alignItems: 'center', gap: 6}}>
                    <input
                      className='ide-input'
                      type='number'
                      step='0.5'
                      min='0.5'
                      max={tierMaxCap}
                      value={costCap}
                      aria-labelledby='compose-cap-label'
                      onChange={(e) =>
                        setCostCap(Math.max(0.5, Math.min(tierMaxCap, Number(e.target.value) || 0)))
                      }
                      style={{width: 90}}
                    />
                    <span style={{fontSize: 12, color: 'var(--ide-fg-muted)'}}>
                      {tierMaxCap === 1000 ? 'USD' : `max $${tierMaxCap}`}
                    </span>
                  </div>
                </div>
              </div>

              <div
                style={{
                  marginTop: 12,
                  padding: 12,
                  border: '1px solid var(--ide-border)',
                  borderRadius: 8,
                  fontSize: 12.5,
                  lineHeight: 1.45,
                  background: 'color-mix(in srgb, var(--ide-bg-tab-inactive) 55%, transparent)',
                }}
              >
                <div
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                    gap: 10,
                    marginBottom: 4,
                  }}
                >
                  <strong style={{color: 'var(--ide-fg-bright)'}}>Managed AI routing</strong>
                  {managedAi && (
                    <IdeBadge kind={managedKind(managedAi.routingState)}>
                      {managedAi.routingState}
                    </IdeBadge>
                  )}
                </div>
                <div style={{color: 'var(--ide-fg-muted)'}}>{managedAiMessage}</div>
              </div>

              <details className='ide-compose-advanced'>
                <summary>Advanced options</summary>
                <div className='ide-compose-options'>
                  <label
                    style={{
                      display: 'flex',
                      alignItems: 'center',
                      gap: 8,
                      fontSize: 13,
                      cursor: 'pointer',
                    }}
                  >
                    <input
                      type='checkbox'
                      checked={tddMode}
                      onChange={(e) => setTddMode(e.target.checked)}
                    />
                    TDD mode
                  </label>
                  <label
                    style={{
                      display: 'flex',
                      alignItems: 'center',
                      gap: 8,
                      fontSize: 13,
                      cursor: 'pointer',
                    }}
                  >
                    <input
                      type='checkbox'
                      checked={confirmModelUpgrades}
                      onChange={(e) => setConfirmModelUpgrades(e.target.checked)}
                    />
                    Confirm model upgrades
                  </label>
                  <label
                    style={{
                      display: 'flex',
                      alignItems: 'center',
                      gap: 8,
                      fontSize: 13,
                      cursor: 'pointer',
                    }}
                  >
                    <input
                      type='checkbox'
                      checked={requireBenchmarkedUpgrades}
                      onChange={(e) => setRequireBenchmarkedUpgrades(e.target.checked)}
                    />
                    Require benchmark evidence
                  </label>
                  <div style={{flex: 1, minWidth: 220}}>
                    <label
                      htmlFor='compose-figma-url'
                      style={{
                        display: 'block',
                        fontSize: 12,
                        color: 'var(--ide-fg-muted)',
                        marginBottom: 4,
                      }}
                    >
                      Figma URL
                    </label>
                    <input
                      id='compose-figma-url'
                      className='ide-input'
                      type='url'
                      placeholder='https://figma.com/...'
                      value={figmaUrl}
                      onChange={(e) => setFigmaUrl(e.target.value)}
                      style={{width: '100%'}}
                    />
                  </div>
                </div>
              </details>
            </div>

            {estimate && (
              <div className='ide-card'>
                <div className='ide-h2' style={{marginTop: 0}}>
                  Estimate
                </div>
                {/* RUN-7 — freshness line; flips to a refresh link-button after 60s. */}
                <div style={{fontSize: 11, color: 'var(--ide-fg-muted)', margin: '-6px 0 8px'}}>
                  {estimateStale ? (
                    <button
                      type='button'
                      onClick={refreshEstimate}
                      style={{
                        background: 'none',
                        border: 'none',
                        padding: 0,
                        font: 'inherit',
                        color: 'var(--ide-accent)',
                        cursor: 'pointer',
                      }}
                    >
                      Refresh
                    </button>
                  ) : (
                    'Updated just now'
                  )}
                </div>
                <div className='ide-kv'>
                  <span>Steps</span>
                  <span>
                    {estimate.estimatedTasksLow}-{estimate.estimatedTasksHigh}
                  </span>
                </div>
                <div className='ide-kv'>
                  <span>Cost</span>
                  <span>
                    ${estimate.estimatedCostLow.toFixed(2)}-${estimate.estimatedCostHigh.toFixed(2)}
                  </span>
                </div>
                {estimate.basis && (
                  <div className='ide-sub' style={{marginTop: 6, marginBottom: 0}}>
                    {estimate.basis}
                  </div>
                )}
              </div>
            )}
          </section>

          <section className={`ide-compose-section${mobileStep === 4 ? ' active' : ''}`}>
            <div className='ide-card ide-compose-review'>
              <div className='ide-h2' style={{marginTop: 0}}>
                Review
              </div>
              <div className='ide-kv'>
                <span>Request</span>
                <span>
                  {spec.trim()
                    ? `${spec.trim().slice(0, 90)}${spec.trim().length > 90 ? '...' : ''}`
                    : 'Not described'}
                </span>
              </div>
              <div className='ide-kv'>
                <span>Context</span>
                <span>{selectedProjectName}</span>
              </div>
              <div className='ide-kv'>
                <span>Provider</span>
                <span>{providerLabel(provider)}</span>
              </div>
              <div className='ide-kv'>
                <span>Approval</span>
                <span>{mode === 'PRODUCTION' ? 'Ask before coding' : 'Start automatically'}</span>
              </div>
              <div className='ide-kv'>
                <span>Model upgrades</span>
                <span>{confirmModelUpgrades ? 'Ask first' : 'Automatic within policy'}</span>
              </div>
              <div className='ide-kv'>
                <span>Benchmark guard</span>
                <span>{requireBenchmarkedUpgrades ? 'Required' : 'Can use unbenchmarked'}</span>
              </div>
              <div className='ide-kv'>
                <span>Cap</span>
                <span>${costCap.toFixed(2)}</span>
              </div>
            </div>
            {err && (
              <div style={{color: 'var(--ide-red)', fontSize: 13, marginBottom: 10}}>{err}</div>
            )}
            <div className='ide-run-action'>
              <button className='ide-btn' onClick={submit} disabled={busy}>
                {busy ? 'Submitting...' : 'Create plan'}
              </button>
            </div>
          </section>
        </aside>
      </div>

      <div className='ide-compose-mobile-actions'>
        <button
          type='button'
          className='ide-btn ghost'
          onClick={() => setMobileStep(Math.max(1, mobileStep - 1) as 1 | 2 | 3 | 4)}
          disabled={mobileStep === 1}
        >
          Back
        </button>
        {mobileStep < 4 ? (
          <button
            type='button'
            className='ide-btn'
            onClick={() => setMobileStep(Math.min(4, mobileStep + 1) as 1 | 2 | 3 | 4)}
            disabled={mobileNextBlocked}
            aria-describedby={mobileNextBlocked ? 'compose-mobile-next-help' : undefined}
          >
            Next
          </button>
        ) : (
          <button className='ide-btn' onClick={submit} disabled={busy}>
            {busy ? 'Submitting...' : 'Create plan'}
          </button>
        )}
        {mobileNextBlocked && (
          <div id='compose-mobile-next-help' className='ide-compose-mobile-help'>
            Describe the change before choosing context.
          </div>
        )}
      </div>
    </div>
  );
}

type LiveEntry = {
  id?: string;
  type?: string;
  timestamp?: number;
  data?: {message?: string; payload?: string};
};

// AgentTask.status is one of PENDING | LOCKED | WORKING | COMPLETE | PARTIAL | FAILED | BLOCKED
// (see state/entity/AgentTask.java). The earlier checks for 'COMPLETED'/'DONE'/'RUNNING' matched
// none of these, so the live task list never flipped its indicator. Tolerant + case-insensitive.
function taskIsDone(s?: string | null): boolean {
  const u = (s || '').toUpperCase();
  return u === 'COMPLETE' || u === 'COMPLETED' || u === 'DONE' || u === 'SUCCESS';
}
function taskIsRunning(s?: string | null): boolean {
  const u = (s || '').toUpperCase();
  return u === 'WORKING' || u === 'RUNNING' || u === 'EXECUTING';
}
// Friendly live-log type labels (review/11 IdeLive) — raw enums like TOOL_CALL
// read as plumbing. Colors keep the same keying in the renderer below.
function logTypeLabel(type?: string): string {
  const t = (type || 'log').toLowerCase();
  if (t.includes('tool')) return '🔧 tool';
  if (t.includes('llm') || t.includes('completion')) return '🧠 model';
  if (t.includes('warn')) return '⚠ warning';
  return t;
}

export function IdeLive() {
  const params = useParams();
  const navigate = useNavigate();
  const runId = params.runId || '';
  const [run, setRun] = useState<api.RunDetail | null>(null);
  const [err, setErr] = useState('');
  const [cancelBusy, setCancelBusy] = useState(false);
  const [confirmCancel, setConfirmCancel] = useState(false);
  // Live spend snapshot from GET /runs/{id}/cost. pipeline_run.actual_cost_usd is only flushed at
  // terminal, so reading it mid-run shows $0; the /cost endpoint reflects RunCostTracker live.
  const [liveCost, setLiveCost] = useState<number | null>(null);
  // Live token counts from the same /cost snapshot (RL-2026-05-052, feedback #1).
  const [liveTokens, setLiveTokens] = useState<{in: number; out: number} | null>(null);

  // Real-time event stream — same hook the Signal LivePage uses. Populates
  // useSseStore with tool / LLM / log entries as the agent runs.
  useSse(runId || null);
  const sseEntries = useSseStore((s) => s.entries);
  const sseStatus = useSseStore((s) => s.status);
  const fileTick = useSseStore((s) => s.fileWrittenTickAt);
  const logBottomRef = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    logBottomRef.current?.scrollIntoView({behavior: 'smooth', block: 'end'});
  }, [sseEntries.length]);

  // Background poll as a safety net for status transitions + cost (SSE drives
  // the log; this guarantees we catch the terminal state even if the stream
  // drops). Auto-navigates to /result on terminal.
  useEffect(() => {
    if (!runId) return;
    let cancelled = false;
    let timer: number | undefined;
    async function tick() {
      try {
        const [r, cost] = await Promise.all([
          api.getRun(runId),
          api.getLiveCost(runId).catch(() => null),
        ]);
        if (cancelled) return;
        setRun(r);
        if (cost && typeof cost.costUsd === 'number') setLiveCost(cost.costUsd);
        if (cost && (cost.inputTokens != null || cost.outputTokens != null))
          setLiveTokens({in: cost.inputTokens ?? 0, out: cost.outputTokens ?? 0});
        const s = (r.status || '').toUpperCase();
        if (['COMPLETED', 'COMPLETE', 'PARTIAL'].includes(s)) {
          navigate(`/result/${runId}`, {replace: true});
          return;
        }
        if (['FAILED', 'ERROR', 'CANCELLED'].includes(s)) {
          setErr(`Run ${s.toLowerCase()}.`);
          return;
        }
        timer = window.setTimeout(tick, 8000);
      } catch (e: unknown) {
        if (!cancelled) {
          setErr(e instanceof Error ? e.message : 'Failed to load run.');
          timer = window.setTimeout(tick, 12000);
        }
      }
    }
    void tick();
    return () => {
      cancelled = true;
      if (timer) clearTimeout(timer);
    };
  }, [runId, navigate, fileTick]);

  const terminal = useMemo(() => {
    const s = (run?.status || '').toUpperCase();
    return ['COMPLETE', 'COMPLETED', 'FAILED', 'ERROR', 'CANCELLED', 'PARTIAL'].includes(s);
  }, [run?.status]);

  async function performCancel() {
    if (!runId) return;
    setCancelBusy(true);
    try {
      await api.cancelRun(runId);
      navigate('/');
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Cancel failed.');
      setCancelBusy(false);
    }
  }

  const tasks = run?.tasks ?? [];
  const completed = tasks.filter((t) => taskIsDone(t.status)).length;
  const spent = liveCost ?? run?.actualCostUsd ?? 0;
  const hardCap = run?.hardCapUsd ?? 0;
  const estHigh = run?.costEstimateHigh ?? 0;
  // Tokens: live snapshot while running; persisted run totals as the fallback so the
  // final total survives at terminal (feedback #1 "total in the end").
  const tokIn = liveTokens?.in ?? run?.totalInputTokens ?? 0;
  const tokOut = liveTokens?.out ?? run?.totalOutputTokens ?? 0;
  const elapsed = formatElapsed(runElapsedMs(run));

  // Prefer SSE entries (real-time); they're the only live source here.
  const logList = sseEntries.slice(-100) as LiveEntry[];
  const sseConnected = sseStatus === 'CONNECTED';
  // Transport jargon → plain status (review/11 IdeLive copy).
  const sseLabel = sseConnected
    ? 'Live'
    : sseStatus === 'CONNECTING'
      ? 'Connecting…'
      : sseStatus === 'RECONNECTING'
        ? 'Reconnecting — brief delay…'
        : 'Live (5s refresh)';
  // Mobile hides the task list via CSS — keep a one-line summary visible at
  // all widths (subtle enough to be harmless on desktop too).
  const currentTaskTitle = tasks.find((t) => taskIsRunning(t.status))?.title;

  return (
    <div className='ide-pane' style={{maxWidth: 860}}>
      <div
        style={{
          display: 'flex',
          alignItems: 'baseline',
          justifyContent: 'space-between',
          gap: 12,
          flexWrap: 'wrap',
        }}
      >
        <div>
          <div className='ide-h1' style={{marginBottom: 2}}>
            Work in progress · {shortRunId(runId)}
          </div>
          <div className='ide-sub ide-mono' style={{marginTop: 0}}>
            {tasks.length
              ? `${completed}/${tasks.length} steps`
              : (run?.status || '').toLowerCase() || 'running'}{' '}
            · {elapsed} · ${spent.toFixed(2)}
            {tokIn + tokOut > 0 && ` · ${(tokIn + tokOut).toLocaleString()} tok`}
          </div>
        </div>
        {!terminal && (
          <button
            className='ide-btn ghost'
            style={{color: 'var(--ide-red)'}}
            onClick={() => setConfirmCancel(true)}
            disabled={cancelBusy}
          >
            {cancelBusy ? 'Cancelling…' : 'Cancel work'}
          </button>
        )}
      </div>

      <ConfirmModal
        open={confirmCancel}
        title='Cancel work'
        body='Cancel this work? The agent is interrupted immediately; completed steps are kept, in-flight changes are discarded.'
        confirmLabel='Cancel work'
        cancelLabel='Keep running'
        danger
        busy={cancelBusy}
        onConfirm={performCancel}
        onClose={() => setConfirmCancel(false)}
      />

      {err && <div style={{color: 'var(--ide-red)', fontSize: 13, margin: '8px 0'}}>{err}</div>}

      {!run ? (
        <IdeLoader label='Connecting to work…' />
      ) : (
        <>
          <div className='ide-card'>
            <div style={{display: 'flex', gap: 8, alignItems: 'center', marginBottom: 12}}>
              <IdeBadge kind={statusKind(run.status)}>{phaseLabel(run.status)}</IdeBadge>
              {run.statusReason && (
                <span style={{color: 'var(--ide-fg-muted)', fontSize: 12}}>{run.statusReason}</span>
              )}
            </div>
            {(run.userRequest || run.runSummary) && (
              <div style={{fontSize: 13, lineHeight: 1.6, marginBottom: 12}}>
                {run.runSummary || run.userRequest}
              </div>
            )}
            {estHigh > 0 && (
              <div style={{margin: '10px 0'}}>
                <CostProgressBar spent={spent} cap={estHigh} label='Spend vs estimate' />
              </div>
            )}
            {hardCap > 0 && (
              <div style={{margin: '10px 0'}}>
                <CostProgressBar spent={spent} cap={hardCap} label='Spend vs hard cap' />
              </div>
            )}
            <div className='ide-kv'>
              <span>Provider</span>
              <span>{(run.aiProvider || '—').toLowerCase()}</span>
            </div>
            <div className='ide-kv'>
              <span>Worker model</span>
              <span className='ide-mono' style={{fontSize: 11}}>
                {run.workerModel || '—'}
              </span>
            </div>
            <div className='ide-kv'>
              <span>Tokens</span>
              <span className='ide-mono' style={{fontSize: 11}}>
                {tokIn + tokOut > 0
                  ? `${tokIn.toLocaleString()} in · ${tokOut.toLocaleString()} out`
                  : '—'}
              </span>
            </div>
            {terminal && (
              <button
                className='ide-btn'
                style={{marginTop: 14}}
                onClick={() => navigate(`/result/${runId}`)}
              >
                View result →
              </button>
            )}
          </div>

          {tasks.length > 0 && (
            <>
              <div className='ide-h2'>Steps</div>
              <div className='ide-card'>
                {tasks.map((t, i) => {
                  const u = (t.status || '').toUpperCase();
                  const done = taskIsDone(u);
                  const running = taskIsRunning(u);
                  const failed = u === 'FAILED' || u === 'BLOCKED';
                  const partial = u === 'PARTIAL';
                  const color = done
                    ? 'var(--ide-green)'
                    : running
                      ? 'var(--ide-accent)'
                      : failed
                        ? 'var(--ide-red)'
                        : partial
                          ? 'var(--ide-yellow)'
                          : 'var(--ide-fg-muted)';
                  const icon = done ? '●' : running ? '◉' : failed ? '✕' : partial ? '◐' : '○';
                  return (
                    <div key={t.taskId || i} className='ide-kv' style={{alignItems: 'center'}}>
                      <span style={{display: 'flex', alignItems: 'center', gap: 8}}>
                        <span style={{color}}>{icon}</span>
                        {t.title || `Step ${i + 1}`}
                      </span>
                      <IdeBadge kind={statusKind(t.status)}>{t.status}</IdeBadge>
                    </div>
                  );
                })}
              </div>
            </>
          )}

          <div className='ide-h2'>
            Activity log{logList.length ? ` · ${logList.length}` : ''}{' '}
            <span
              className='ide-mono'
              style={{fontSize: 11, color: 'var(--ide-fg-muted)', fontWeight: 400}}
            >
              · {sseLabel}
            </span>
          </div>
          {tasks.length > 0 && (
            <div style={{fontSize: 12, color: 'var(--ide-fg-muted)', margin: '0 0 6px'}}>
              ▶ {completed}/{tasks.length} steps
              {currentTaskTitle ? ` — ${currentTaskTitle}` : ''}
            </div>
          )}
          {logList.length === 0 ? (
            <div className='ide-card'>
              <IdeLoader label='Waiting for the first tool call… the agent is warming up. Usually 30–60s.' />
            </div>
          ) : (
            <div
              style={{
                maxHeight: '46vh',
                overflowY: 'auto',
                borderRadius: 8,
                border: '1px solid var(--ide-border)',
                background: 'var(--ide-bg-panel)',
                padding: 8,
              }}
            >
              {logList.map((ev, i) => {
                const t = (ev.type || 'log').toLowerCase();
                const color = t.includes('tool')
                  ? 'var(--ide-accent)'
                  : t.includes('llm') || t.includes('completion')
                    ? 'var(--ide-green)'
                    : t.includes('warn')
                      ? 'var(--ide-yellow)'
                      : 'var(--ide-fg-muted)';
                const body = ev.data?.message || ev.data?.payload || '';
                return (
                  <div
                    key={ev.id || i}
                    style={{
                      padding: '6px 8px',
                      borderBottom: '1px solid var(--ide-border)',
                      fontSize: 12,
                      lineHeight: 1.5,
                    }}
                  >
                    <span className='ide-mono' style={{color, marginRight: 8, fontSize: 10}}>
                      {logTypeLabel(ev.type)}
                    </span>
                    {ev.timestamp && (
                      <span
                        className='ide-mono'
                        style={{color: 'var(--ide-fg-muted)', marginRight: 8, fontSize: 10}}
                      >
                        {new Date(ev.timestamp).toLocaleTimeString()}
                      </span>
                    )}
                    <span>{body}</span>
                  </div>
                );
              })}
              <div ref={logBottomRef} />
            </div>
          )}
        </>
      )}
    </div>
  );
}
