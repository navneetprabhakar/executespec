import {useEffect, useMemo, useRef, useState} from 'react';
import {Link, useNavigate, useParams} from 'react-router-dom';
import * as api from '@/lib/api';
import {IdeBadge, IdeLoader, statusKind} from '@/ide/IdeKit';
import {SpecChat} from '@/signal/components/SpecChat';
import {CostProgressBar} from '@/signal/components/CostProgressBar';
import {ConfirmModal} from '@/signal/components/Modal';
import {repoDisplayName, repoIdFor} from '@/signal/lib/runUtils';
import {useAuthStore, selectNormalizedTier} from '@/stores/useAuthStore';
import {useSse} from '@/hooks/useSse';
import {useSseStore} from '@/stores/useSseStore';

function fmtWhen(iso?: string | null): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return isNaN(d.getTime()) ? '—' : d.toLocaleString();
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
function isLiveStatus(s?: string | null): boolean {
  return NON_TERMINAL.has((s || '').toUpperCase());
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

// ───────────────────────── Welcome / workspace home ─────────────────────────
export function IdeWelcome() {
  const permissions = useAuthStore((s) => s.permissions);
  const [runs, setRuns] = useState<api.RunSummary[] | null>(null);
  const [projects, setProjects] = useState<api.ProjectDto[] | null>(null);
  const [liveRun, setLiveRun] = useState<api.RunDetail | null>(null);

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

  const runCap = permissions
    ? permissions.monthlyRunLimit === -1
      ? `${permissions.currentRunCount} runs this cycle`
      : `${permissions.currentRunCount} / ${permissions.monthlyRunLimit} runs this cycle`
    : '— runs this cycle';

  const liveTasks = liveRun?.tasks ?? [];
  const liveDone = liveTasks.filter((t) => t.status === 'COMPLETED' || t.status === 'DONE').length;
  const liveSpent = liveRun?.actualCostUsd ?? 0;

  return (
    <div className='ide-pane'>
      <div className='ide-h1'>Workspace</div>
      <div className='ide-sub'>
        Spec-driven runs across your repos. {runCap}. Press ⌘K to jump anywhere.
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
          ＋ New spec
        </Link>
        <Link className='ide-btn ghost' to='/runs'>
          ◎ All runs
        </Link>
      </div>

      <div className='ide-h2'>Recent runs</div>
      {runs === null ? (
        <IdeLoader label='Loading runs…' />
      ) : runs.length === 0 ? (
        <div className='ide-empty'>No runs yet — start with a new spec.</div>
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
                {r.aiProvider && <span>{r.aiProvider.toLowerCase()}</span>}
                <span>{fmtWhen(r.startedAt)}</span>
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

// ───────────────────────── Compose (new spec) ───────────────────────────────
export function IdeCompose() {
  const navigate = useNavigate();
  const tier = useAuthStore(selectNormalizedTier);
  const permissions = useAuthStore((s) => s.permissions);

  const [spec, setSpec] = useState('');
  const [provider, setProvider] = useState('ANTHROPIC');
  // Per-run provider+model pin (Beta, Enterprise-only — 2026-05-22). '' = Default (org/system
  // matrix), i.e. send no modelName. modelsByProvider keys are lowercase provider names.
  const [modelName, setModelName] = useState('');
  const [modelsByProvider, setModelsByProvider] = useState<Record<string, string[]>>({});
  const [mode, setMode] = useState<'PROTOTYPE' | 'PRODUCTION'>('PRODUCTION');
  const [tddMode, setTddMode] = useState(false);
  const [figmaUrl, setFigmaUrl] = useState('');
  const [projects, setProjects] = useState<api.ProjectDto[]>([]);
  const [projectId, setProjectId] = useState<string | null>(null);
  const [projectRepos, setProjectRepos] = useState<Array<Record<string, unknown>>>([]);
  const [selectedRepoIds, setSelectedRepoIds] = useState<string[]>([]);
  // Per-tier max hard cap (mirrors ComposePage): FREE $3, PRO/TEAM $25,
  // ENTERPRISE effectively unlimited.
  const tierMaxCap = tier === 'FREE' ? 3 : tier === 'ENTERPRISE' ? 1000 : 25;
  const [costCap, setCostCap] = useState<number>(Math.min(2, tierMaxCap));
  const [estimate, setEstimate] = useState<api.LaunchEstimate | null>(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');
  const [samples, setSamples] = useState<api.SampleSpecDto[]>([]);
  const [sampleBusyId, setSampleBusyId] = useState<string | null>(null);

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
    if (tier !== 'ENTERPRISE') return;
    api
      .getOrgModelMatrix()
      .then((m) => setModelsByProvider(m.modelsByProvider || {}))
      .catch(() => setModelsByProvider({}));
  }, [tier]);

  // Reset the per-run model pin to "Default" whenever the provider changes — a model is only valid
  // for its own provider, so a stale selection must not leak across a provider switch.
  useEffect(() => {
    setModelName('');
  }, [provider]);

  async function applySample(sample: api.SampleSpecDto) {
    setSampleBusyId(sample.id);
    setErr('');
    try {
      const picked = await api.pickOnboardingSample(sample.id);
      setSpec(picked.spec || sample.title);
      if (picked.preferredProvider) setProvider(picked.preferredProvider);
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
      return;
    }
    const t = setTimeout(() => {
      api
        .estimateRun(projectId, spec.trim())
        .then(setEstimate)
        .catch(() => setEstimate(null));
    }, 600);
    return () => clearTimeout(t);
  }, [projectId, spec]);

  function toggleRepo(repoId: string) {
    setSelectedRepoIds((prev) =>
      prev.includes(repoId) ? prev.filter((x) => x !== repoId) : [...prev, repoId],
    );
  }

  const isAtCap =
    permissions != null &&
    permissions.monthlyRunLimit !== -1 &&
    permissions.currentRunCount >= permissions.monthlyRunLimit;
  const runsUsed = permissions
    ? `${permissions.currentRunCount} / ${permissions.monthlyRunLimit === -1 ? '∞' : permissions.monthlyRunLimit}`
    : '— / —';

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

  return (
    <div className='ide-pane' style={{maxWidth: 820}}>
      <div className='ide-h1'>New spec</div>
      <div className='ide-sub'>
        Describe the change in plain language. The pipeline plans it, writes the code across your
        repos, builds + tests, and opens a PR with a verified score. · {runsUsed} runs this cycle ·{' '}
        {tier} tier
      </div>

      {isAtCap && (
        <div
          className='ide-card'
          style={{
            borderColor: 'var(--ide-yellow)',
            marginBottom: 12,
            fontSize: 13,
            lineHeight: 1.5,
          }}
        >
          You've used all {permissions?.monthlyRunLimit} runs in this billing cycle.{' '}
          <Link to='/topup' style={{color: 'var(--ide-accent)', fontWeight: 600}}>
            Top up credits →
          </Link>{' '}
          or{' '}
          <Link to='/plans' style={{color: 'var(--ide-accent)', fontWeight: 600}}>
            upgrade your plan →
          </Link>
          .
        </div>
      )}

      <textarea
        className='ide-textarea'
        rows={12}
        placeholder='e.g. Add a /health endpoint that returns build SHA + uptime, with a unit test…'
        value={spec}
        onChange={(e) => setSpec(e.target.value)}
      />
      <div style={{marginTop: 10}}>
        <SpecChat projectId={projectId || undefined} draftSpec={spec} onApply={setSpec} />
      </div>

      {samples.length > 0 && (
        <>
          <div className='ide-h2'>Start from a sample</div>
          <div style={{display: 'flex', flexWrap: 'wrap', gap: 8}}>
            {samples.map((s) => (
              <button
                key={s.id}
                className='ide-btn ghost'
                disabled={sampleBusyId != null}
                onClick={() => void applySample(s)}
                title={`${s.category} · ${s.complexity}`}
              >
                {sampleBusyId === s.id ? '…' : s.title}
              </button>
            ))}
          </div>
        </>
      )}

      <div className='ide-h2'>Project</div>
      <div style={{display: 'flex', flexWrap: 'wrap', gap: 8, marginBottom: 6}}>
        <button
          className={projectId == null ? 'ide-btn' : 'ide-btn ghost'}
          onClick={() => setProjectId(null)}
        >
          {projectId == null ? '✓ ' : ''}Sandbox (no repo)
        </button>
        {projects.map((p) => (
          <button
            key={p.id}
            className={projectId === p.id ? 'ide-btn' : 'ide-btn ghost'}
            onClick={() => setProjectId(p.id)}
          >
            {projectId === p.id ? '✓ ' : ''}
            {p.name}
          </button>
        ))}
      </div>
      <div className='ide-sub' style={{marginTop: 0}}>
        {projectId
          ? `Running against ${projects.find((p) => p.id === projectId)?.name}. ${selectedRepoIds.length} ${selectedRepoIds.length === 1 ? 'repo' : 'repos'} selected.`
          : 'Sandbox mode: a temporary project is created. Pick a real project to run against your codebase.'}
      </div>

      {projectId && projectRepos.length > 0 && (
        <>
          <div className='ide-h2'>Repos the agent may touch</div>
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
        </>
      )}

      <div style={{display: 'flex', gap: 12, margin: '14px 0', flexWrap: 'wrap'}}>
        <div style={{flex: 1, minWidth: 180}}>
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
            {tier === 'ENTERPRISE' && <option value='OPENROUTER'>OpenRouter</option>}
          </select>
        </div>
        {/* Per-run provider+model pin (Beta, Enterprise-only — 2026-05-22). Overrides org &
            default matrix for this run only. '' = Default → no modelName sent. */}
        {tier === 'ENTERPRISE' && (
          <div style={{flex: 1, minWidth: 180}}>
            <div className='ide-h2' style={{marginTop: 0}}>
              Model · Beta
            </div>
            <select
              className='ide-select'
              value={modelName}
              onChange={(e) => setModelName(e.target.value)}
            >
              <option value=''>Default (org / system matrix)</option>
              {(modelsByProvider[provider.toLowerCase()] || []).map((m) => (
                <option key={m} value={m}>
                  {m}
                </option>
              ))}
            </select>
            <div style={{fontSize: 11, color: 'var(--ide-fg-muted)', marginTop: 4}}>
              Overrides org &amp; default matrix for this run
            </div>
          </div>
        )}
        <div style={{flex: 1, minWidth: 180}}>
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
        <div style={{flex: 1, minWidth: 180}}>
          <div className='ide-h2' style={{marginTop: 0}}>
            Spending cap
          </div>
          <div style={{display: 'flex', alignItems: 'center', gap: 6}}>
            <input
              className='ide-input'
              type='number'
              step='0.5'
              min='0.5'
              max={tierMaxCap}
              value={costCap}
              onChange={(e) =>
                setCostCap(Math.max(0.5, Math.min(tierMaxCap, Number(e.target.value) || 0)))
              }
              style={{width: 90}}
            />
            <span style={{fontSize: 12, color: 'var(--ide-fg-muted)'}}>
              USD · max ${tierMaxCap === 1000 ? '∞' : tierMaxCap}
            </span>
          </div>
        </div>
      </div>

      <div
        style={{display: 'flex', gap: 16, flexWrap: 'wrap', alignItems: 'center', marginBottom: 12}}
      >
        <label
          style={{display: 'flex', alignItems: 'center', gap: 8, fontSize: 13, cursor: 'pointer'}}
        >
          <input type='checkbox' checked={tddMode} onChange={(e) => setTddMode(e.target.checked)} />
          TDD mode — write tests first
        </label>
        <div style={{flex: 1, minWidth: 220}}>
          <input
            className='ide-input'
            type='url'
            placeholder='Optional Figma URL (passed to the planner)'
            value={figmaUrl}
            onChange={(e) => setFigmaUrl(e.target.value)}
            style={{width: '100%'}}
          />
        </div>
      </div>

      {estimate && (
        <div className='ide-card' style={{marginBottom: 12}}>
          <div className='ide-h2' style={{marginTop: 0}}>
            Estimate from project history
          </div>
          <div className='ide-kv'>
            <span>Tasks</span>
            <span>
              {estimate.estimatedTasksLow}–{estimate.estimatedTasksHigh}
            </span>
          </div>
          <div className='ide-kv'>
            <span>Cost</span>
            <span>
              ${estimate.estimatedCostLow.toFixed(2)}–${estimate.estimatedCostHigh.toFixed(2)}
            </span>
          </div>
          {estimate.basis && (
            <div className='ide-sub' style={{marginTop: 6}}>
              {estimate.basis}
            </div>
          )}
        </div>
      )}

      {err && <div style={{color: 'var(--ide-red)', fontSize: 13, marginBottom: 10}}>{err}</div>}
      <button className='ide-btn' onClick={submit} disabled={busy || isAtCap}>
        {busy ? 'Submitting…' : '▷ Run spec'}
      </button>
    </div>
  );
}

// ───────────────────────── Live run ─────────────────────────────────────────
type LiveEntry = {
  id?: string;
  type?: string;
  timestamp?: number;
  data?: {message?: string; payload?: string};
};

export function IdeLive() {
  const params = useParams();
  const navigate = useNavigate();
  const runId = params.runId || '';
  const [run, setRun] = useState<api.RunDetail | null>(null);
  const [err, setErr] = useState('');
  const [cancelBusy, setCancelBusy] = useState(false);
  const [confirmCancel, setConfirmCancel] = useState(false);

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
        const r = await api.getRun(runId);
        if (cancelled) return;
        setRun(r);
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
  const completed = tasks.filter((t) => t.status === 'COMPLETED' || t.status === 'DONE').length;
  const spent = run?.actualCostUsd ?? 0;
  const hardCap = run?.hardCapUsd ?? 0;
  const elapsed = formatElapsed(runElapsedMs(run));

  // Prefer SSE entries (real-time); they're the only live source here.
  const logList = sseEntries.slice(-100) as LiveEntry[];
  const sseConnected = sseStatus === 'CONNECTED';
  const sseLabel = sseConnected
    ? 'live · streaming'
    : sseStatus === 'CONNECTING'
      ? 'connecting…'
      : sseStatus === 'RECONNECTING'
        ? 'reconnecting…'
        : 'polling';

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
            Live · {shortRunId(runId)}
          </div>
          <div className='ide-sub ide-mono' style={{marginTop: 0}}>
            {tasks.length
              ? `${completed}/${tasks.length} tasks`
              : (run?.status || '').toLowerCase() || 'running'}{' '}
            · {elapsed} · ${spent.toFixed(2)}
          </div>
        </div>
        {!terminal && (
          <button
            className='ide-btn ghost'
            onClick={() => setConfirmCancel(true)}
            disabled={cancelBusy}
          >
            {cancelBusy ? 'Cancelling…' : '✕ Cancel run'}
          </button>
        )}
      </div>

      <ConfirmModal
        open={confirmCancel}
        title='Cancel run'
        body='Cancel this run? The agent is interrupted immediately; completed tasks are kept, in-flight changes are discarded.'
        confirmLabel='Cancel run'
        cancelLabel='Keep running'
        danger
        busy={cancelBusy}
        onConfirm={performCancel}
        onClose={() => setConfirmCancel(false)}
      />

      {err && <div style={{color: 'var(--ide-red)', fontSize: 13, margin: '8px 0'}}>{err}</div>}

      {!run ? (
        <IdeLoader label='Connecting to run…' />
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
              <div className='ide-h2'>Tasks</div>
              <div className='ide-card'>
                {tasks.map((t, i) => {
                  const done = t.status === 'COMPLETED' || t.status === 'DONE';
                  const running = t.status === 'RUNNING' || t.status === 'EXECUTING';
                  return (
                    <div key={t.taskId || i} className='ide-kv' style={{alignItems: 'center'}}>
                      <span style={{display: 'flex', alignItems: 'center', gap: 8}}>
                        <span
                          style={{
                            color: done
                              ? 'var(--ide-green)'
                              : running
                                ? 'var(--ide-accent)'
                                : 'var(--ide-fg-muted)',
                          }}
                        >
                          {done ? '●' : running ? '◉' : '○'}
                        </span>
                        {t.title || `Task ${i + 1}`}
                      </span>
                      <IdeBadge kind={statusKind(t.status)}>{t.status}</IdeBadge>
                    </div>
                  );
                })}
              </div>
            </>
          )}

          <div className='ide-h2'>
            Live log{logList.length ? ` · ${logList.length}` : ''}{' '}
            <span
              className='ide-mono'
              style={{fontSize: 11, color: 'var(--ide-fg-muted)', fontWeight: 400}}
            >
              · {sseLabel}
            </span>
          </div>
          {logList.length === 0 ? (
            <div className='ide-card'>
              <IdeLoader label='Waiting for the first tool call… the agent is warming up.' />
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
                    <span
                      className='ide-mono'
                      style={{color, marginRight: 8, textTransform: 'uppercase', fontSize: 10}}
                    >
                      {ev.type || 'log'}
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
