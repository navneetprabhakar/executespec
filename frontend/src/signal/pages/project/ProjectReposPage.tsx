import {useEffect, useState} from 'react';
import {useParams} from 'react-router-dom';
import {TopBar, IconButton} from '@/signal/components/Chrome';
import {Feed, Card, KvList, Button, StatusPill} from '@/signal/components/Primitives';
import {LoadingState} from '@/signal/components/Loaders';
import {ConfirmModal} from '@/signal/components/Modal';
import {ProjectTabs} from './ProjectTabs';
import {Plus} from '@/signal/icons/icons';
import {useProjectMeta} from '@/signal/hooks/useProjectMeta';
import {repoDisplayName, repoIdFor} from '@/signal/lib/runUtils';
import * as api from '@/lib/api';

type RepoRow = Record<string, unknown>;

export default function ProjectReposPage() {
  const {projectId} = useParams();
  const id = projectId || '';
  const project = useProjectMeta(id);
  const [repos, setRepos] = useState<RepoRow[] | null>(null);
  const [health, setHealth] = useState<api.WorkspaceHealthReport | null>(null);
  const [available, setAvailable] = useState<RepoRow[] | null>(null);
  const [showLink, setShowLink] = useState(false);
  const [err, setErr] = useState('');
  const [msg, setMsg] = useState('');
  const [busy, setBusy] = useState(false);
  const [confirmUnlink, setConfirmUnlink] = useState<{repoId: string; name: string} | null>(null);

  async function refresh() {
    if (!id) return;
    const [rp, h] = await Promise.all([
      api.getProjectRepos(id).catch(() => [] as unknown as RepoRow[]),
      api.getWorkspaceHealth(id).catch(() => null),
    ]);
    setRepos(rp as unknown as RepoRow[]);
    setHealth(h);
  }

  useEffect(() => {
    void refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id]);

  async function openLinkPanel() {
    setShowLink(true);
    if (!available) {
      try {
        const r = await api.listRepos('ALL');
        setAvailable(r as unknown as RepoRow[]);
      } catch (e: unknown) {
        setErr(e instanceof Error ? e.message : 'Failed to load repos.');
      }
    }
  }

  async function link(repoId: string) {
    setBusy(true);
    setErr('');
    try {
      await api.linkProjectRepo(id, repoId);
      setMsg('Repo linked.');
      setShowLink(false);
      await refresh();
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Link failed.');
    } finally {
      setBusy(false);
    }
  }

  async function performUnlink(repoId: string) {
    setBusy(true);
    try {
      await api.unlinkProjectRepo(id, repoId);
      setMsg('Repo unlinked.');
      await refresh();
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Unlink failed.');
    } finally {
      setBusy(false);
      setConfirmUnlink(null);
    }
  }

  async function installWebhook(repoId: string) {
    try {
      await api.installRepoWebhook(repoId);
      setMsg('Webhook installed.');
      await refresh();
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Webhook install failed.');
    }
  }

  async function syncOne(repoId: string) {
    try {
      await api.syncRepo(repoId);
      setMsg('Sync started.');
      await refresh();
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Sync failed.');
    }
  }

  const healthMap = new Map<string, Record<string, unknown>>();
  (health?.repos || []).forEach((r) => {
    const key = String(
      (r as unknown as {repoId?: string}).repoId || (r as unknown as {name?: string}).name || '',
    );
    if (key) healthMap.set(key, r as unknown as Record<string, unknown>);
  });

  return (
    <>
      <TopBar
        title='Repos'
        sub={`${project?.name || 'project'} · ${repos?.length ?? 0} linked`}
        back
        backTo={`/projects/${id}`}
        actions={
          <IconButton ariaLabel='Add' onClick={openLinkPanel}>
            <Plus size={18} />
          </IconButton>
        }
      />
      <Feed>
        <ProjectTabs active='repos' />

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
        {msg && (
          <div
            style={{
              background: 'var(--signal-mint-soft)',
              color: 'var(--signal-mint-text-on-light)',
              border: '1px solid var(--signal-mint)',
              borderRadius: 10,
              padding: '10px 14px',
              marginBottom: 12,
              fontSize: 13,
            }}
          >
            {msg}
          </div>
        )}

        {showLink && (
          <Card>
            <div style={{fontSize: 14.5, fontWeight: 600, marginBottom: 8}}>Available repos</div>
            {available == null ? (
              <p style={{fontSize: 13, color: 'var(--signal-text-dim)', margin: 0}}>
                Loading repos…
              </p>
            ) : available.length === 0 ? (
              <p style={{fontSize: 13, color: 'var(--signal-text-dim)', margin: 0}}>
                No connected repos yet. Connect GitHub from{' '}
                <a href='/integrations' style={{color: 'var(--signal-coral)'}}>
                  Integrations
                </a>
                .
              </p>
            ) : (
              <div style={{display: 'flex', flexDirection: 'column', gap: 8}}>
                {available.map((r) => {
                  const rId = repoIdFor(r);
                  const isLinked = (repos || []).some((p) => repoIdFor(p) === rId);
                  const repoName = repoDisplayName(r);
                  return (
                    <div
                      key={rId}
                      style={{
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'space-between',
                        padding: '8px 12px',
                        background: 'var(--signal-card-elev)',
                        borderRadius: 8,
                      }}
                    >
                      <span style={{fontFamily: "'JetBrains Mono', monospace", fontSize: 12.5}}>
                        {repoName}
                      </span>
                      <Button
                        kind={isLinked ? 'ghost' : 'primary'}
                        sm
                        disabled={isLinked || busy}
                        onClick={() => link(rId)}
                      >
                        {isLinked ? 'Linked' : 'Link'}
                      </Button>
                    </div>
                  );
                })}
              </div>
            )}
            <Button
              kind='ghost'
              block
              sm
              style={{marginTop: 10}}
              onClick={() => setShowLink(false)}
            >
              Close
            </Button>
          </Card>
        )}

        {repos === null ? (
          <LoadingState label='Loading repos…' />
        ) : repos.length === 0 ? (
          <Card>
            <p style={{fontSize: 14, color: 'var(--signal-text-dim)', margin: 0, lineHeight: 1.5}}>
              No repos linked. Click the + above to link a repo.
            </p>
          </Card>
        ) : (
          repos.map((rep) => {
            const repoId = repoIdFor(rep);
            const name = repoDisplayName(rep);
            const language = (rep as {language?: string}).language;
            const loc = (rep as {loc?: number}).loc;
            const head = (rep as {headSha?: string}).headSha;
            const branch = (rep as {defaultBranch?: string}).defaultBranch || 'main';
            const emptyHealth: Record<string, unknown> = {};
            const h = healthMap.get(repoId) || healthMap.get(name) || emptyHealth;
            const commitsBehind = (h as {commitsBehind?: number}).commitsBehind ?? 0;
            const webhookOk = (h as {webhookInstalled?: boolean}).webhookInstalled === true;
            const openPrs = (h as {openPrs?: number}).openPrs ?? 0;
            const archStatus =
              (rep as {architectureStatus?: string}).architectureStatus ||
              (h as {architectureStatus?: string}).architectureStatus;
            const status: 'shipped' | 'needs' | 'failed' = !webhookOk
              ? 'failed'
              : commitsBehind > 0
                ? 'needs'
                : 'shipped';
            const statusLabel = !webhookOk ? 'No webhook' : commitsBehind > 0 ? 'Drift' : 'Healthy';
            return (
              <Card key={repoId}>
                <div style={{display: 'flex', gap: 12, alignItems: 'center', marginBottom: 14}}>
                  <div
                    style={{
                      width: 10,
                      height: 10,
                      borderRadius: '50%',
                      background:
                        status === 'shipped'
                          ? 'var(--signal-mint)'
                          : status === 'needs'
                            ? 'var(--signal-y)'
                            : 'var(--signal-coral)',
                    }}
                  />
                  <div style={{flex: 1, minWidth: 0}}>
                    <div style={{fontSize: 15, fontWeight: 600}}>{name}</div>
                    <div
                      style={{
                        fontSize: 11.5,
                        color: 'var(--signal-text-dim)',
                        fontFamily: "'JetBrains Mono', monospace",
                      }}
                    >
                      {language || '—'}
                      {loc ? ` · ${(loc / 1000).toFixed(1)}K LOC` : ''}
                    </div>
                  </div>
                  <StatusPill kind={status}>{statusLabel}</StatusPill>
                </div>
                <KvList
                  pairs={[
                    {k: 'HEAD', v: head ? `${head.slice(0, 7)} · ${branch}` : branch},
                    {
                      k: 'Drift',
                      v: (
                        <span
                          style={{
                            color:
                              commitsBehind > 0
                                ? 'var(--signal-y-text)'
                                : 'var(--signal-mint-text-on-light)',
                          }}
                        >
                          {commitsBehind > 0 ? `${commitsBehind} commits behind origin` : 'in sync'}
                        </span>
                      ),
                    },
                    {
                      k: 'Webhook',
                      v: (
                        <span
                          style={{
                            color: webhookOk
                              ? 'var(--signal-mint-text-on-light)'
                              : 'var(--signal-coral-text)',
                          }}
                        >
                          {webhookOk ? '✓ installed' : '○ not installed'}
                        </span>
                      ),
                    },
                    {k: 'Open PRs', v: String(openPrs)},
                    ...(archStatus
                      ? [
                          {
                            k: 'Architecture',
                            v: (
                              <span
                                style={{
                                  color: archStatus.includes('STALE')
                                    ? 'var(--signal-y-text)'
                                    : 'var(--signal-mint-text-on-light)',
                                }}
                              >
                                {archStatus}
                              </span>
                            ),
                          },
                        ]
                      : []),
                  ]}
                />
                <div style={{display: 'flex', gap: 6, marginTop: 12}}>
                  <Button kind='secondary' sm to={`/projects/${id}/architecture`} style={{flex: 1}}>
                    View architecture
                  </Button>
                  {!webhookOk && (
                    <Button
                      kind='primary'
                      sm
                      style={{flex: 1}}
                      onClick={() => installWebhook(repoId)}
                    >
                      Install webhook
                    </Button>
                  )}
                  {commitsBehind > 0 && (
                    <Button kind='secondary' sm style={{flex: 1}} onClick={() => syncOne(repoId)}>
                      Sync
                    </Button>
                  )}
                  <Button
                    kind='ghost'
                    sm
                    style={{flex: 1, color: 'var(--signal-coral-text)'}}
                    onClick={() => setConfirmUnlink({repoId, name})}
                  >
                    Unlink
                  </Button>
                </div>
              </Card>
            );
          })
        )}

        <Button
          kind='secondary'
          block
          style={{marginTop: 12}}
          onClick={openLinkPanel}
          disabled={busy}
        >
          <Plus size={16} /> Link another repo
        </Button>
      </Feed>

      <ConfirmModal
        open={!!confirmUnlink}
        title='Unlink this repo?'
        body={`"${confirmUnlink?.name || 'this repo'}" will no longer be cloned or referenced by future runs. Existing runs and PRs are unaffected.`}
        confirmLabel='Unlink'
        danger
        busy={busy}
        onClose={() => setConfirmUnlink(null)}
        onConfirm={() => {
          if (confirmUnlink) void performUnlink(confirmUnlink.repoId);
        }}
      />
    </>
  );
}
