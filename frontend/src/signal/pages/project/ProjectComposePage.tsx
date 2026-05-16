import {useEffect, useState} from 'react';
import {useNavigate, useParams} from 'react-router-dom';
import {TopBar, SectionHeader, IconButton} from '@/signal/components/Chrome';
import {Feed, Row, Card, Chip, KvList, Button, StatusPill} from '@/signal/components/Primitives';
import {ComposerCard, LiveHero, LiveHeroAction} from '@/signal/components/Compose';
import {SkeletonRow} from '@/signal/components/Loaders';
import {SpecChat} from '@/signal/components/SpecChat';
import {Share, More} from '@/signal/icons/icons';
import {ProjectTabs} from './ProjectTabs';
import {useProjectMeta} from '@/signal/hooks/useProjectMeta';
import {useAuthStore, selectNormalizedTier} from '@/stores/useAuthStore';
import * as api from '@/lib/api';

type Provider = 'anthropic' | 'openai' | 'gemini';

const PROVIDER_LABEL: Record<Provider, string> = {
  anthropic: 'Anthropic',
  openai: 'OpenAI',
  gemini: 'Gemini',
};

function relTime(input?: string): string {
  if (!input) return '—';
  const t = new Date(input).getTime();
  if (!t) return '—';
  const s = Math.round((Date.now() - t) / 1000);
  if (s < 60) return `${s}s ago`;
  const m = Math.round(s / 60);
  if (m < 60) return `${m}m ago`;
  const h = Math.round(m / 60);
  if (h < 24) return `${h}h ago`;
  const d = Math.round(h / 24);
  return `${d}d ago`;
}

/**
 * Per-project Compose landing page — v3 parity. This is the DEFAULT tab
 * when a user opens a project (matching dev.executespec.ai behaviour),
 * because the most common action in a project is "ship another spec".
 *
 * The Pulse / metrics view is still one tab over.
 */
export default function ProjectComposePage() {
  const {projectId} = useParams();
  const id = projectId || '';
  const project = useProjectMeta(id);
  const navigate = useNavigate();
  const tier = useAuthStore(selectNormalizedTier);
  const permissions = useAuthStore((s) => s.permissions);

  const [spec, setSpec] = useState('');
  const [provider, setProvider] = useState<Provider>(
    ((project?.aiProvider || '').toLowerCase() === 'openai' ? 'openai' :
     (project?.aiProvider || '').toLowerCase() === 'gemini' ? 'gemini' : 'anthropic'),
  );
  const [repos, setRepos] = useState<Array<Record<string, unknown>>>([]);
  const [selectedRepoIds, setSelectedRepoIds] = useState<string[]>([]);
  const [estimate, setEstimate] = useState<api.LaunchEstimate | null>(null);
  // null = still loading; [] = loaded with no results.
  const [recent, setRecent] = useState<api.RunSummary[] | null>(null);
  const [liveRun, setLiveRun] = useState<api.RunSummary | null>(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');

  // Set the provider chip to the project's default whenever the project
  // resolves.
  useEffect(() => {
    if (!project) return;
    const p = (project.aiProvider || '').toLowerCase();
    if (p === 'openai' || p === 'gemini' || p === 'anthropic') setProvider(p);
  }, [project]);

  // Sandbox-picker handoff (welcome page) — pre-fill spec.
  useEffect(() => {
    const pending = sessionStorage.getItem('signal:pending-spec');
    if (pending) {
      setSpec(pending);
      sessionStorage.removeItem('signal:pending-spec');
    }
  }, []);

  // Project repos — checked by default so a spec lands against all linked code.
  useEffect(() => {
    if (!id) return;
    api.getProjectRepos(id)
      .then((rs) => {
        const list = rs as unknown as Array<Record<string, unknown>>;
        setRepos(list);
        const ids = list
          .map((r) => String((r as {repoId?: string; id?: string}).repoId || (r as {id?: string}).id || ''))
          .filter(Boolean);
        setSelectedRepoIds(ids);
      })
      .catch(() => {
        setRepos([]);
        setSelectedRepoIds([]);
      });
  }, [id]);

  // Recent project runs — drives the right-rail "Recent runs" preview so the
  // user has context about what's already shipped before writing the next spec.
  useEffect(() => {
    if (!id) return;
    api.getRuns({projectId: id, page: 0, size: 5}).then((p) => {
      setRecent(p.content);
      const live = p.content.find((r) => {
        const s = (r.status || '').toUpperCase();
        return s === 'CLASSIFYING' || s === 'PLANNING' || s === 'EXECUTING' || s === 'AWAITING_APPROVAL' || s === 'RUNNING';
      });
      setLiveRun(live ?? null);
    }).catch(() => { setRecent([]); });
  }, [id]);

  // Estimate p25/p75 cost + tasks for the current spec.
  useEffect(() => {
    if (!id || spec.trim().length < 12) {
      setEstimate(null);
      return;
    }
    const t = setTimeout(() => {
      api.estimateRun(id, spec.trim()).then(setEstimate).catch(() => setEstimate(null));
    }, 600);
    return () => clearTimeout(t);
  }, [id, spec]);

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
        aiProvider: provider.toUpperCase(),
        executionMode: 'PRODUCTION',
        projectId: id,
      };
      if (selectedRepoIds.length > 0) body.selectedRepoIds = selectedRepoIds;
      const resp = await api.createRun(body as Parameters<typeof api.createRun>[0]);
      navigate(`/plan/${resp.runId}`);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Run submission failed.');
    } finally {
      setBusy(false);
    }
  }

  const projectName = project?.name || 'project';
  const repoSummary = repos.length === 0 ? '0 repos' : `${repos.length} ${repos.length === 1 ? 'repo' : 'repos'}`;
  const runsUsed = permissions ? `${permissions.currentRunCount} / ${permissions.monthlyRunLimit === -1 ? '∞' : permissions.monthlyRunLimit}` : '— / —';

  return (
    <>
      <TopBar
        title={projectName}
        sub={`${repoSummary} · ${tier} tier`}
        back
        backTo='/projects'
        actions={
          <>
            <IconButton ariaLabel='Share' to={`/projects/${id}/members`}><Share size={18} /></IconButton>
            <IconButton ariaLabel='More' to={`/projects/${id}/settings`}><More size={18} /></IconButton>
          </>
        }
      />
      <Feed>
        <ProjectTabs active='compose' />

        {err && <div style={{background: 'var(--signal-coral-soft)', color: 'var(--signal-coral-text)', border: '1px solid var(--signal-coral)', borderRadius: 10, padding: '10px 14px', marginBottom: 12, fontSize: 13}}>{err}</div>}

        {liveRun && (
          <LiveHero
            runId={`R-${liveRun.runId.slice(0, 6)}`}
            title={liveRun.userRequest?.slice(0, 120) || '—'}
            tasks='—'
            elapsed={relTime(liveRun.startedAt)}
            spent={(liveRun as unknown as {actualCostUsd?: number}).actualCostUsd != null
              ? `$${((liveRun as unknown as {actualCostUsd: number}).actualCostUsd).toFixed(2)}`
              : '$0.00'}
            actions={
              <>
                <LiveHeroAction to={`/plan/${liveRun.runId}`}>Plan</LiveHeroAction>
                <LiveHeroAction to={`/live/${liveRun.runId}`} primary>Watch live</LiveHeroAction>
              </>
            }
          />
        )}

        <ComposerCard
          est={runsUsed === '— / —' ? 'est cost' : runsUsed + ' runs this cycle'}
          value={spec}
          onChange={setSpec}
          onSubmit={onSubmit}
          submitBusy={busy}
          chips={
            <>
              <Chip kind={provider === 'anthropic' ? 'active' : 'default'} onClick={() => setProvider('anthropic')}>Anthropic</Chip>
              <Chip kind={provider === 'openai' ? 'active' : 'default'} onClick={() => setProvider('openai')}>OpenAI</Chip>
              <Chip kind={provider === 'gemini' ? 'active' : 'default'} onClick={() => setProvider('gemini')}>Gemini</Chip>
            </>
          }
        />

        <SpecChat
          projectId={id}
          draftSpec={spec}
          onApply={(refined) => setSpec(refined)}
        />

        {repos.length > 0 && (
          <>
            <SectionHeader>Repos the agent may touch</SectionHeader>
            <div className='signal-composer-card' style={{padding: 14}}>
              <div className='composer-chips'>
                {repos.map((r) => {
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
              <div style={{fontSize: 12, color: 'var(--signal-text-dim)', marginTop: 8}}>
                {selectedRepoIds.length === 0
                  ? 'No repos selected — agent will sandbox.'
                  : `${selectedRepoIds.length} ${selectedRepoIds.length === 1 ? 'repo' : 'repos'} selected.`}
              </div>
            </div>
          </>
        )}

        {estimate && (
          <>
            <SectionHeader>Estimate from project history</SectionHeader>
            <Card>
              <KvList
                pairs={[
                  {k: 'Tasks (p25)', v: String((estimate as unknown as {p25Tasks?: number}).p25Tasks ?? '—')},
                  {k: 'Tasks (p75)', v: String((estimate as unknown as {p75Tasks?: number}).p75Tasks ?? '—')},
                  {k: 'Cost (p25)', v: (estimate as unknown as {p25CostUsd?: number}).p25CostUsd != null ? `$${(estimate as unknown as {p25CostUsd: number}).p25CostUsd.toFixed(2)}` : '—'},
                  {k: 'Cost (p75)', v: (estimate as unknown as {p75CostUsd?: number}).p75CostUsd != null ? `$${(estimate as unknown as {p75CostUsd: number}).p75CostUsd.toFixed(2)}` : '—'},
                ]}
              />
            </Card>
          </>
        )}

        <SectionHeader action={<a href={`/projects/${id}/runs`}>All runs</a>}>Recent runs in this project</SectionHeader>
        {recent === null ? (
          <>
            <SkeletonRow lines={2} />
            <SkeletonRow lines={2} />
          </>
        ) : recent.length === 0 ? (
          <Card><p style={{fontSize: 13.5, color: 'var(--signal-text-dim)', margin: 0}}>No runs yet. Submit a spec above to start.</p></Card>
        ) : (
          recent.slice(0, 4).map((r) => {
            const s = (r.status || '').toUpperCase();
            const score = (r as unknown as {verifiedScore?: number}).verifiedScore;
            const cost = (r as unknown as {actualCostUsd?: number}).actualCostUsd;
            const isLive = s === 'CLASSIFYING' || s === 'PLANNING' || s === 'EXECUTING' || s === 'AWAITING_APPROVAL' || s === 'RUNNING';
            return (
              <Row
                key={r.runId}
                to={isLive ? `/live/${r.runId}` : `/result/${r.runId}`}
                dot={isLive ? 'live' : s === 'COMPLETED' ? 'mint' : s === 'FAILED' || s === 'CANCELLED' ? 'coral' : 'lavender'}
                title={r.userRequest || (r as unknown as {runTitle?: string}).runTitle || `Run R-${r.runId.slice(0, 6)}`}
                meta={
                  <>
                    <span className='mono'>R-{r.runId.slice(0, 6)}</span>
                    <span>·</span>
                    <span>{relTime(r.startedAt)}</span>
                  </>
                }
                trailing={score != null ? `${Math.round(score)}%` : isLive ? 'live' : s.toLowerCase()}
                trailingKind={score == null ? 'default' : score >= 85 ? 'good' : score >= 70 ? 'warn' : 'fail'}
                trailingLabel={cost != null ? `$${cost.toFixed(2)}` : undefined}
              />
            );
          })
        )}

        <SectionHeader>This project</SectionHeader>
        <KvList
          pairs={[
            {k: 'Provider', v: PROVIDER_LABEL[provider]},
            {k: 'Tier', v: <StatusPill kind={tier === 'FREE' ? 'needs' : 'shipped'}>{tier}</StatusPill>},
            {k: 'Mode', v: 'PRODUCTION (plan gate)'},
            {k: 'Repos linked', v: String(repos.length)},
          ]}
        />
        <div style={{display: 'flex', gap: 8, marginTop: 10}}>
          <Button kind='secondary' block sm to={`/projects/${id}/pulse`}>Pulse</Button>
          <Button kind='secondary' block sm to={`/projects/${id}/code`}>Code</Button>
          <Button kind='secondary' block sm to={`/projects/${id}/chat`}>Chat</Button>
          <Button kind='secondary' block sm to={`/projects/${id}/settings`}>Settings</Button>
        </div>
      </Feed>
    </>
  );
}
