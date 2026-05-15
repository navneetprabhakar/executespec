import {useEffect, useMemo, useState} from 'react';
import {TopBar, TabBar, IconButton} from '@/signal/components/Chrome';
import {Feed, Card, StatusPill, Button, TabsStrip} from '@/signal/components/Primitives';
import {Plus, Search} from '@/signal/icons/icons';
import {useNavigate} from 'react-router-dom';
import * as api from '@/lib/api';
import {useAuthStore} from '@/stores/useAuthStore';

type Tab = 'mine' | 'shared' | 'archived';

function initialsFor(name: string): string {
  const parts = (name || '').split(/[\s\-_]+/).filter(Boolean);
  if (parts.length === 0) return 'PR';
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
}

function gradientFor(seed: string): string {
  const palettes = [
    'linear-gradient(135deg, var(--signal-lavender), var(--signal-ocean))',
    'linear-gradient(135deg, var(--signal-ocean), var(--signal-mint))',
    'linear-gradient(135deg, var(--signal-y), var(--signal-coral))',
    'linear-gradient(135deg, var(--signal-coral), var(--signal-lavender))',
    'linear-gradient(135deg, var(--signal-mint), var(--signal-y))',
  ];
  let hash = 0;
  for (let i = 0; i < seed.length; i++) hash = (hash * 31 + seed.charCodeAt(i)) >>> 0;
  return palettes[hash % palettes.length];
}

export default function ProjectsPage() {
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);
  const [all, setAll] = useState<api.ProjectDto[] | null>(null);
  const [err, setErr] = useState('');
  const [tab, setTab] = useState<Tab>('mine');
  const [creating, setCreating] = useState(false);

  useEffect(() => {
    let cancelled = false;
    api
      .getProjects()
      .then((p) => {
        if (!cancelled) setAll(p);
      })
      .catch((e: unknown) => {
        if (!cancelled) setErr(e instanceof Error ? e.message : 'Failed to load projects.');
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const filtered = useMemo(() => {
    const list = all || [];
    const myOrg = user?.orgId;
    if (tab === 'archived') return list.filter((p) => p.archivedAt);
    if (tab === 'shared') {
      return list.filter((p) => !p.archivedAt && myOrg && (p as unknown as {ownerOrgId?: string}).ownerOrgId &&
        (p as unknown as {ownerOrgId?: string}).ownerOrgId !== myOrg);
    }
    return list.filter((p) => !p.archivedAt);
  }, [all, tab, user?.orgId]);

  async function quickCreate() {
    const name = window.prompt('Project name?');
    if (!name?.trim()) return;
    setCreating(true);
    try {
      const created = await api.createProject({name: name.trim()});
      navigate(`/projects/${created.id}`);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Could not create project.');
    } finally {
      setCreating(false);
    }
  }

  const mineCount = (all || []).filter((p) => !p.archivedAt).length;
  const archivedCount = (all || []).filter((p) => p.archivedAt).length;

  return (
    <>
      <TopBar
        title='Projects'
        sub={`${mineCount} active · ${user?.email ?? ''}`}
        back
        backTo='/home'
        actions={
          <>
            <IconButton to='/search' ariaLabel='Search'>
              <Search size={18} />
            </IconButton>
            <IconButton ariaLabel='New project' onClick={quickCreate}>
              <Plus size={18} />
            </IconButton>
          </>
        }
      />
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

        <TabsStrip
          items={[
            {label: `Mine · ${mineCount}`, active: tab === 'mine', onClick: () => setTab('mine')},
            {label: 'Shared with me', active: tab === 'shared', onClick: () => setTab('shared')},
            {label: `Archived · ${archivedCount}`, active: tab === 'archived', onClick: () => setTab('archived')},
          ]}
        />

        {all === null ? (
          <Card>
            <p style={{fontSize: 13.5, color: 'var(--signal-text-dim)', margin: 0}}>Loading projects…</p>
          </Card>
        ) : filtered.length === 0 ? (
          <Card>
            <p style={{fontSize: 14, color: 'var(--signal-text-dim)', margin: 0, lineHeight: 1.5}}>
              {tab === 'archived'
                ? 'No archived projects.'
                : tab === 'shared'
                  ? 'No projects shared with you yet.'
                  : 'No projects yet. Create one to start running specs against real code.'}
            </p>
          </Card>
        ) : (
          <div className='signal-grid cols-2'>
            {filtered.map((p) => {
              const repoCount = (p as unknown as {repoCount?: number}).repoCount ?? 0;
              const totalRuns = (p as unknown as {totalRuns?: number}).totalRuns ?? 0;
              const openPrs = (p as unknown as {openPrCount?: number}).openPrCount ?? 0;
              const avg = (p as unknown as {avgScore?: number}).avgScore;
              const spent = (p as unknown as {totalSpent?: number; spent30d?: number}).totalSpent ??
                (p as unknown as {spent30d?: number}).spent30d;
              const debtCount = (p as unknown as {techDebtCount?: number}).techDebtCount ?? 0;
              const debtKind = debtCount >= 3 ? 'fail' : debtCount > 0 ? 'warn' : 'good';
              const status = p.archivedAt ? undefined : (openPrs > 0 ? 'live' : totalRuns > 0 ? 'shipped' : undefined);
              const statusLabel = p.archivedAt ? 'Archived' : openPrs > 0 ? 'Live' : totalRuns > 0 ? 'Healthy' : 'New';
              return (
                <Card key={p.id} to={`/projects/${p.id}`}>
                  <div style={{display: 'flex', gap: 14, marginBottom: 14}}>
                    <div
                      style={{
                        width: 44,
                        height: 44,
                        borderRadius: 12,
                        background: gradientFor(p.name || p.id),
                        display: 'grid',
                        placeItems: 'center',
                        color: 'var(--signal-ink)',
                        fontWeight: 700,
                        fontSize: 17,
                        flexShrink: 0,
                      }}
                    >
                      {initialsFor(p.name || 'PR')}
                    </div>
                    <div style={{flex: 1, minWidth: 0}}>
                      <div style={{fontSize: 16, fontWeight: 600, marginBottom: 2}}>{p.name}</div>
                      <div style={{fontSize: 12, color: 'var(--signal-text-dim)', fontFamily: "'JetBrains Mono', monospace"}}>
                        {repoCount} {repoCount === 1 ? 'repo' : 'repos'} · {totalRuns} runs · {openPrs} {openPrs === 1 ? 'PR' : 'PRs'} open
                      </div>
                    </div>
                    <StatusPill kind={status as 'live' | 'shipped' | 'needs' | 'failed' | undefined}>{statusLabel}</StatusPill>
                  </div>
                  <div
                    style={{
                      display: 'grid',
                      gridTemplateColumns: 'repeat(3, 1fr)',
                      gap: 10,
                      paddingTop: 12,
                      borderTop: '1px solid var(--signal-hairline)',
                      fontSize: 12,
                    }}
                  >
                    <div>
                      <div style={{color: 'var(--signal-text-dim)', fontSize: 11}}>Avg score</div>
                      <div style={{fontWeight: 600, fontSize: 15, marginTop: 2}}>{avg != null ? `${Math.round(avg)}%` : '—'}</div>
                    </div>
                    <div>
                      <div style={{color: 'var(--signal-text-dim)', fontSize: 11}}>Spent</div>
                      <div style={{fontWeight: 600, fontSize: 15, marginTop: 2}}>{spent != null ? `$${spent.toFixed(2)}` : '—'}</div>
                    </div>
                    <div>
                      <div style={{color: 'var(--signal-text-dim)', fontSize: 11}}>Tech debt</div>
                      <div
                        style={{
                          fontWeight: 600,
                          fontSize: 15,
                          marginTop: 2,
                          color:
                            debtKind === 'fail'
                              ? 'var(--signal-coral)'
                              : debtKind === 'warn'
                              ? 'var(--signal-y-text)'
                              : undefined,
                        }}
                      >
                        {debtCount > 0 ? debtCount : '—'}
                      </div>
                    </div>
                  </div>
                </Card>
              );
            })}
          </div>
        )}

        <Button kind='secondary' block style={{marginTop: 12}} onClick={quickCreate} disabled={creating}>
          <Plus size={16} />
          {creating ? 'Creating…' : 'New project'}
        </Button>
      </Feed>
      <TabBar />
    </>
  );
}
