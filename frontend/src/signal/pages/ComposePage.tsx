import {useEffect, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {TopBar, SectionHeader} from '@/signal/components/Chrome';
import {Feed, Row, Chip, KvList, Card} from '@/signal/components/Primitives';
import {ComposerCard} from '@/signal/components/Compose';
import * as api from '@/lib/api';
import {useAuthStore, selectNormalizedTier} from '@/stores/useAuthStore';

type Provider = 'anthropic' | 'openai' | 'gemini';

const PROVIDER_LABEL: Record<Provider, string> = {
  anthropic: 'Anthropic',
  openai: 'OpenAI',
  gemini: 'Gemini',
};

// Backend's validator accepts upper-case ANTHROPIC | OPENAI | GEMINI | GOOGLE.
// We send the provider only; model selection is server-side per the YAML
// model-matrix + tier router.
function providerForBackend(p: Provider): string {
  return p.toUpperCase();
}

export default function ComposePage() {
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);
  const tier = useAuthStore(selectNormalizedTier);
  const permissions = useAuthStore((s) => s.permissions);

  const [spec, setSpec] = useState('');
  const [provider, setProvider] = useState<Provider>(
    ((user as unknown as {defaultProvider?: string})?.defaultProvider?.toLowerCase() === 'openai' ? 'openai' :
      (user as unknown as {defaultProvider?: string})?.defaultProvider?.toLowerCase() === 'gemini' ? 'gemini' : 'anthropic'),
  );
  const [projects, setProjects] = useState<api.ProjectDto[]>([]);
  const [projectId, setProjectId] = useState<string | null>(null);
  const [projectRepos, setProjectRepos] = useState<Array<Record<string, unknown>>>([]);
  const [selectedRepoIds, setSelectedRepoIds] = useState<string[]>([]);
  // Per-tier max hard cap. FREE caps at $3, PRO/TEAM at $25, ENTERPRISE
  // unlimited. The default is the lower of "$2 sensible default" and the
  // tier ceiling. Matches the CLAUDE.md tier ladder.
  const tierMaxCap = tier === 'FREE' ? 3 : tier === 'ENTERPRISE' ? 1000 : 25;
  const [costCap, setCostCap] = useState<number>(Math.min(2, tierMaxCap));
  const [estimate, setEstimate] = useState<api.LaunchEstimate | null>(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');

  // Bootstrap: load projects, hydrate from sandbox-picker handoff.
  useEffect(() => {
    const pending = sessionStorage.getItem('signal:pending-spec');
    if (pending) {
      setSpec(pending);
      sessionStorage.removeItem('signal:pending-spec');
    }
    api.getProjects().then((p) => setProjects(p.filter((x) => !x.archivedAt))).catch(() => {});
  }, []);

  // Load repos when project changes.
  useEffect(() => {
    if (!projectId) {
      setProjectRepos([]);
      setSelectedRepoIds([]);
      return;
    }
    api.getProjectRepos(projectId)
      .then((rs) => {
        setProjectRepos(rs as unknown as Array<Record<string, unknown>>);
        const allIds = (rs as unknown as Array<Record<string, unknown>>)
          .map((r) => String((r as {repoId?: string; id?: string}).repoId || (r as {id?: string}).id || ''))
          .filter(Boolean);
        setSelectedRepoIds(allIds);
      })
      .catch(() => {
        setProjectRepos([]);
        setSelectedRepoIds([]);
      });
  }, [projectId]);

  // Debounced estimate: when the spec stabilises, ask the backend for a
  // p25/p75 cost+task estimate. Only meaningful inside a project (the
  // estimator pulls from project history).
  useEffect(() => {
    if (!projectId || spec.trim().length < 12) {
      setEstimate(null);
      return;
    }
    const t = setTimeout(() => {
      api.estimateRun(projectId, spec.trim()).then(setEstimate).catch(() => setEstimate(null));
    }, 600);
    return () => clearTimeout(t);
  }, [projectId, spec]);

  function toggleRepo(repoId: string) {
    setSelectedRepoIds((prev) => prev.includes(repoId) ? prev.filter((x) => x !== repoId) : [...prev, repoId]);
  }

  async function onSubmit() {
    if (!spec.trim()) {
      setErr('Spec cannot be empty.');
      return;
    }
    setErr('');
    setBusy(true);
    try {
      const body: Record<string, unknown> = {
        userRequest: spec.trim(),
        aiProvider: providerForBackend(provider),
        executionMode: 'PRODUCTION',
      };
      if (projectId) body.projectId = projectId;
      if (selectedRepoIds.length > 0) body.selectedRepoIds = selectedRepoIds;
      if (costCap > 0) body.userCostCap = costCap;
      const resp = await api.createRun(body as Parameters<typeof api.createRun>[0]);
      navigate(`/plan/${resp.runId}`);
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : 'Run submission failed.';
      setErr(msg);
    } finally {
      setBusy(false);
    }
  }

  const runsUsed = permissions ? `${permissions.currentRunCount} / ${permissions.monthlyRunLimit === -1 ? '∞' : permissions.monthlyRunLimit}` : '— / —';
  const isAtCap = permissions != null && permissions.monthlyRunLimit !== -1 && permissions.currentRunCount >= permissions.monthlyRunLimit;
  const estimateCells = estimate ? [
    {k: 'Tasks (p25)', v: String((estimate as unknown as {p25Tasks?: number}).p25Tasks ?? '—')},
    {k: 'Tasks (p75)', v: String((estimate as unknown as {p75Tasks?: number}).p75Tasks ?? '—')},
    {k: 'Cost (p25)', v: (estimate as unknown as {p25CostUsd?: number}).p25CostUsd != null ? `$${(estimate as unknown as {p25CostUsd: number}).p25CostUsd.toFixed(2)}` : '—'},
    {k: 'Cost (p75)', v: (estimate as unknown as {p75CostUsd?: number}).p75CostUsd != null ? `$${(estimate as unknown as {p75CostUsd: number}).p75CostUsd.toFixed(2)}` : '—'},
  ] : null;

  return (
    <>
      <TopBar title='New spec' sub={`${PROVIDER_LABEL[provider]} · ${tier} tier`} back backTo='/home' />
      <Feed>
        {err && (
          <div
            style={{
              background: 'var(--signal-coral-soft)',
              color: 'var(--signal-coral-text)',
              border: '1px solid var(--signal-coral)',
              borderRadius: 10,
              padding: '10px 14px',
              marginBottom: 12,
              fontSize: 13,
            }}
          >
            {err}
          </div>
        )}

        {isAtCap && (
          <Card style={{background: 'var(--signal-y-soft)', borderColor: 'transparent', marginBottom: 12}}>
            <div style={{fontSize: 13, color: 'var(--signal-y-text)', lineHeight: 1.5}}>
              You've used all <strong>{permissions?.monthlyRunLimit}</strong> runs in this billing cycle.{' '}
              <a href='/topup' style={{color: 'var(--signal-coral)', fontWeight: 600}}>Top up credits →</a>{' '}
              or{' '}
              <a href='/plans' style={{color: 'var(--signal-coral)', fontWeight: 600}}>upgrade your plan →</a>.
            </div>
          </Card>
        )}

        <ComposerCard
          est={runsUsed === '— / —' ? 'est cost' : runsUsed + ' runs this cycle'}
          value={spec}
          onChange={setSpec}
          onSubmit={onSubmit}
          submitBusy={busy}
          chips={
            <>
              <Chip kind={provider === 'anthropic' ? 'active' : 'default'} onClick={() => setProvider('anthropic')}>
                Anthropic
              </Chip>
              <Chip kind={provider === 'openai' ? 'active' : 'default'} onClick={() => setProvider('openai')}>
                OpenAI
              </Chip>
              <Chip kind={provider === 'gemini' ? 'active' : 'default'} onClick={() => setProvider('gemini')}>
                Gemini
              </Chip>
            </>
          }
        />

        <SectionHeader>Project</SectionHeader>
        <div className='signal-composer-card' style={{padding: 14}}>
          <div className='composer-chips'>
            <Chip kind={projectId == null ? 'active' : 'default'} onClick={() => setProjectId(null)}>
              ✓ Sandbox (no repo)
            </Chip>
            {projects.map((p) => (
              <Chip key={p.id} kind={projectId === p.id ? 'active' : 'default'} onClick={() => setProjectId(p.id)}>
                {p.name}
              </Chip>
            ))}
          </div>
          <div style={{fontSize: 12, color: 'var(--signal-text-dim)', marginTop: 8}}>
            {projectId
              ? `Running against ${projects.find((p) => p.id === projectId)?.name}. ${selectedRepoIds.length} ${selectedRepoIds.length === 1 ? 'repo' : 'repos'} selected.`
              : 'Sandbox mode: a temporary project is created. Pick a real project to run against your codebase.'}
          </div>
        </div>

        {projectId && projectRepos.length > 0 && (
          <>
            <SectionHeader>Repos the agent may touch</SectionHeader>
            <div className='signal-composer-card' style={{padding: 14}}>
              <div className='composer-chips'>
                {projectRepos.map((r) => {
                  const rId = String((r as {repoId?: string; id?: string}).repoId || (r as {id?: string}).id || '');
                  const rName = String((r as {repoName?: string; name?: string}).repoName || (r as {name?: string}).name || rId);
                  const selected = selectedRepoIds.includes(rId);
                  return (
                    <Chip key={rId} kind={selected ? 'active' : 'default'} onClick={() => toggleRepo(rId)}>
                      {selected ? '✓ ' : ''}{rName}
                    </Chip>
                  );
                })}
              </div>
            </div>
          </>
        )}

        <SectionHeader>Cost &amp; cap</SectionHeader>
        <Card>
          <KvList
            pairs={[
              {k: 'Provider', v: PROVIDER_LABEL[provider]},
              {k: 'Plan strategy', v: 'AUTO (classifier)'},
              {k: 'Mode', v: 'PRODUCTION (plan gate)'},
              {
                k: 'Hard cap',
                v: (
                  <div style={{display: 'flex', alignItems: 'center', gap: 6}}>
                    <input
                      type='number'
                      step='0.5'
                      min='0.5'
                      max={tierMaxCap}
                      value={costCap}
                      onChange={(e) => setCostCap(Math.max(0.5, Math.min(tierMaxCap, Number(e.target.value) || 0)))}
                      style={{
                        width: 80,
                        background: 'var(--signal-card-elev)',
                        border: '1px solid var(--signal-hairline)',
                        borderRadius: 6,
                        padding: '4px 8px',
                        fontSize: 13,
                        fontFamily: "'JetBrains Mono', monospace",
                        color: 'var(--signal-ink)',
                        outline: 'none',
                      }}
                    />
                    <span style={{fontSize: 12, color: 'var(--signal-text-dim)'}}>USD · max ${tierMaxCap === 1000 ? '∞' : tierMaxCap} ({tier})</span>
                  </div>
                ),
              },
            ]}
          />
        </Card>

        {estimateCells && (
          <>
            <SectionHeader>Estimate from project history</SectionHeader>
            <Card><KvList pairs={estimateCells} /></Card>
          </>
        )}

        <SectionHeader>From your memory</SectionHeader>
        <Row
          to='/templates'
          dot='lavender'
          title='Saved templates'
          meta={<span>Pre-fill the composer from a project template</span>}
          showChev
        />
      </Feed>
    </>
  );
}
