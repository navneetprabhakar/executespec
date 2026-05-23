import {useEffect, useMemo, useRef, useState} from 'react';
import {useNavigate, useParams} from 'react-router-dom';
import * as api from '@/lib/api';
import type {ArchitectureDoc} from '@/types/project';
import {IdeBadge, IdeCodeViewer, IdeFileTree, IdeLoader} from '@/ide/IdeKit';
import {ProjectTabs} from '@/ide/IdeProjectTabs';
import {repoDisplayName, repoIdFor} from '@/signal/lib/runUtils';
import {Markdown} from '@/signal/components/Markdown';
import {CodeReviewPanel} from '@/signal/components/CodeReviewPanel';
import {ConfirmModal, PromptModal} from '@/signal/components/Modal';
import {useAuthStore} from '@/stores/useAuthStore';

const tok = () => localStorage.getItem('access_token') ?? '';

// ───────────────────────── shared sub-tab + banner helpers ─────────────────────────

type SubTab = {key: string; label: string};

// Sub-tab strip inside a surface (mirrors the Signal TabsStrip). Reuses the IDE
// result-page tab classes so it reads native in both themes.
function RTabs({
  tabs,
  active,
  onSelect,
}: {
  tabs: SubTab[];
  active: string;
  onSelect: (k: string) => void;
}) {
  return (
    <div className='ide-rtabs' style={{marginBottom: 16, borderRadius: 4}}>
      {tabs.map((t) => (
        <button
          key={t.key}
          type='button'
          className={`ide-rtab${active === t.key ? ' active' : ''}`}
          onClick={() => onSelect(t.key)}
        >
          {t.label}
        </button>
      ))}
    </div>
  );
}

// Inline error / success banners — colours map to the IDE token set.
function Banner({err, msg}: {err?: string; msg?: string}) {
  if (!err && !msg) return null;
  return (
    <div
      className='ide-card'
      style={{
        marginBottom: 12,
        fontSize: 13,
        color: err ? 'var(--ide-red)' : 'var(--ide-green)',
        borderColor: err ? 'var(--ide-red)' : 'var(--ide-green)',
      }}
    >
      {err || msg}
    </div>
  );
}

// Small uppercase metric label used inside the health / browse grids.
function MetaLabel({children}: {children: React.ReactNode}) {
  return (
    <div
      style={{
        fontSize: 10,
        fontWeight: 700,
        color: 'var(--ide-fg-muted)',
        letterSpacing: '0.1em',
        textTransform: 'uppercase',
        marginBottom: 4,
      }}
    >
      {children}
    </div>
  );
}

// ───────────────────────── Chat ─────────────────────────
type ChatBubble = {role: 'user' | 'assistant'; content: string; ts?: string};

export function IdeProjectChat() {
  const {projectId = ''} = useParams();
  const [msgs, setMsgs] = useState<ChatBubble[] | null>(null);
  const [memory, setMemory] = useState<api.ProjectMemoryResponse | null>(null);
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');
  const [confirmClear, setConfirmClear] = useState(false);
  // Backend gates project chat behind TEAM+ (TierConfig.isExecutionViewEnabled).
  // FREE / PRO hit a 403 on chat/info → show an upgrade card instead of a broken shell.
  const [gated, setGated] = useState(false);
  // Memory digest is reference context, not needed every message — collapsed by
  // default so it doesn't dominate the chat pane; click the header to expand.
  const [memOpen, setMemOpen] = useState(false);
  const endRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!projectId) return;
    let cancelled = false;
    let memTimer: number | undefined;
    api
      .getProjectChatInfo(projectId)
      .then(() => {
        if (!cancelled) setGated(false);
      })
      .catch((e: unknown) => {
        const m = e instanceof Error ? e.message : '';
        if (!cancelled && /403|forbidden|team or enterprise/i.test(m)) setGated(true);
      });
    api
      .getProjectChatHistory(projectId)
      .then((rows) => {
        if (cancelled) return;
        setMsgs(
          rows.map((r) => ({
            role: r.role === 'USER' ? 'user' : 'assistant',
            content: r.content,
            ts: r.createdAt,
          })),
        );
      })
      .catch((e: unknown) => {
        if (cancelled) return;
        const m = e instanceof Error ? e.message : '';
        if (/403|forbidden|team or enterprise/i.test(m)) setGated(true);
        setMsgs([]);
      });
    // Memory digest lags terminal by a few seconds — poll every 30s.
    function refreshMemory() {
      api
        .getProjectMemory(projectId)
        .then((mem) => {
          if (!cancelled) setMemory(mem);
        })
        .catch(() => {})
        .finally(() => {
          if (!cancelled) memTimer = window.setTimeout(refreshMemory, 30_000);
        });
    }
    refreshMemory();
    return () => {
      cancelled = true;
      if (memTimer) clearTimeout(memTimer);
    };
  }, [projectId]);

  useEffect(() => endRef.current?.scrollIntoView({behavior: 'smooth'}), [msgs]);

  async function send() {
    const m = text.trim();
    if (!m || busy || gated) return;
    setBusy(true);
    setErr('');
    setText('');
    setMsgs((prev) => [...(prev ?? []), {role: 'user', content: m, ts: new Date().toISOString()}]);
    try {
      const r = await fetch(`/api/projects/${projectId}/chat`, {
        method: 'POST',
        headers: {'Content-Type': 'application/json', Authorization: `Bearer ${tok()}`},
        body: JSON.stringify({message: m}),
      });
      if (!r.ok) throw new Error(`Chat failed (${r.status}).`);
      const data = (await r.json()) as {
        reply?: string;
        message?: string;
        content?: string;
        assistantMessage?: string;
      };
      const reply =
        data.reply || data.message || data.content || data.assistantMessage || '(no reply)';
      setMsgs((prev) => [
        ...(prev ?? []),
        {role: 'assistant', content: reply, ts: new Date().toISOString()},
      ]);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Chat failed.');
    } finally {
      setBusy(false);
    }
  }

  // Compile-to-spec handoff: stash the spec + navigate to the project composer.
  async function compile() {
    setErr('');
    try {
      const r = await api.compileProjectChatToSpec(projectId);
      if (r?.spec) {
        sessionStorage.setItem('signal:pending-spec', r.spec);
        window.location.href = `/projects/${projectId}`;
      }
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Compile failed.');
    }
  }
  async function compact() {
    setErr('');
    try {
      await api.compactProjectChat(projectId);
      setMsgs([]);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Compact failed.');
    }
  }
  async function clearAll() {
    setErr('');
    try {
      await api.clearProjectChat(projectId);
      setMsgs([]);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Clear failed.');
    }
  }

  const memDigest = memory?.fullContext?.trim() || '';
  const memRuns = memory?.stats?.totalRuns ?? 0;

  return (
    <div
      className='ide-pane'
      style={{display: 'flex', flexDirection: 'column', height: '100%', maxWidth: 'none'}}
    >
      <ProjectTabs projectId={projectId} />
      <div style={{display: 'flex', alignItems: 'center', gap: 10}}>
        <div className='ide-h1' style={{margin: 0}}>
          Project chat
        </div>
        <span style={{fontSize: 12, color: 'var(--ide-fg-muted)'}}>
          memory: {memRuns} {memRuns === 1 ? 'run' : 'runs'}
        </span>
        <span style={{flex: 1}} />
        <button className='ide-btn ghost' onClick={compile} disabled={busy || gated}>
          ⌁ Compile to spec
        </button>
        <button className='ide-btn ghost' onClick={compact} disabled={gated}>
          Compact
        </button>
        <button
          className='ide-btn ghost'
          onClick={() => setConfirmClear(true)}
          disabled={gated}
          style={{color: 'var(--ide-red)'}}
        >
          Clear
        </button>
      </div>

      <div style={{marginTop: 12}}>
        <Banner err={err} />
      </div>

      {memDigest && (
        <div
          className='ide-card'
          style={{
            marginBottom: 8,
            borderColor: 'var(--ide-yellow)',
            padding: memOpen ? undefined : '8px 12px',
          }}
        >
          <button
            type='button'
            onClick={() => setMemOpen((v) => !v)}
            aria-expanded={memOpen}
            title={memOpen ? 'Hide memory digest' : 'Show memory digest'}
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 8,
              width: '100%',
              background: 'none',
              border: 'none',
              padding: 0,
              cursor: 'pointer',
              textAlign: 'left',
              fontSize: 11,
              fontWeight: 700,
              color: 'var(--ide-yellow)',
              letterSpacing: '0.14em',
              textTransform: 'uppercase',
            }}
          >
            <span
              style={{
                display: 'inline-block',
                transition: 'transform 0.15s',
                transform: memOpen ? 'rotate(90deg)' : 'none',
              }}
            >
              ▸
            </span>
            Memory digest · {memRuns} runs
            <span
              style={{
                marginLeft: 'auto',
                fontWeight: 400,
                letterSpacing: 0,
                textTransform: 'none',
                color: 'var(--ide-fg-muted)',
              }}
            >
              {memOpen ? 'hide' : 'show'}
            </span>
          </button>
          {memOpen && (
            <div style={{fontSize: 13, lineHeight: 1.5, whiteSpace: 'pre-wrap', marginTop: 8}}>
              {memDigest.slice(0, 1200)}
            </div>
          )}
        </div>
      )}

      <div style={{flex: 1, overflowY: 'auto', minHeight: 0, margin: '12px 0'}}>
        {gated ? (
          <div className='ide-card' style={{borderColor: 'var(--ide-blue)'}}>
            <div
              style={{
                fontSize: 11,
                fontWeight: 700,
                color: 'var(--ide-blue)',
                letterSpacing: '0.14em',
                textTransform: 'uppercase',
                marginBottom: 6,
              }}
            >
              Project chat is a TEAM+ feature
            </div>
            <div style={{fontSize: 13, lineHeight: 1.5}}>
              The agent uses your project's memory + codemap + linked docs to answer questions —
              gated to TEAM and ENTERPRISE plans.{' '}
              <a href='/plans' style={{color: 'var(--ide-accent)'}}>
                Compare plans →
              </a>
            </div>
          </div>
        ) : msgs === null ? (
          <IdeLoader label='Loading chat…' />
        ) : msgs.length === 0 && !busy ? (
          <div className='ide-empty'>No messages yet — ask about your codebase.</div>
        ) : (
          msgs.map((m, i) => (
            <div
              key={i}
              className='ide-card'
              style={{
                marginBottom: 8,
                borderColor: m.role === 'user' ? 'var(--ide-accent)' : undefined,
              }}
            >
              <div style={{fontSize: 11, color: 'var(--ide-fg-muted)', marginBottom: 4}}>
                {m.role === 'user' ? 'You' : 'Assistant'}
                {m.ts && ` · ${new Date(m.ts).toLocaleTimeString()}`}
              </div>
              <div className='signal-md' style={{fontSize: 13.5}}>
                <Markdown>{m.content}</Markdown>
              </div>
            </div>
          ))
        )}
        {busy && (
          <div style={{fontSize: 13, color: 'var(--ide-fg-muted)', padding: '4px 0'}}>
            Thinking…
          </div>
        )}
        <div ref={endRef} />
      </div>

      <div style={{display: 'flex', gap: 8}}>
        <input
          className='ide-input'
          placeholder={
            gated ? 'Upgrade to TEAM to enable project chat.' : 'Ask about the codebase…'
          }
          value={text}
          disabled={gated}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && send()}
        />
        <button className='ide-btn' onClick={send} disabled={busy || gated}>
          {busy ? '…' : 'Send'}
        </button>
      </div>

      <ConfirmModal
        open={confirmClear}
        title='Clear chat history'
        body='Clear chat history? All messages are deleted permanently.'
        confirmLabel='Clear'
        danger
        onConfirm={clearAll}
        onClose={() => setConfirmClear(false)}
      />
    </div>
  );
}

// ───────────────────────── Code (Browse · Tech debt · Code review · Health · Standards · Docs) ─────────────────────────
type CodeSub = 'browse' | 'debt' | 'review' | 'health' | 'standards' | 'docs';

export function IdeProjectCode() {
  const {projectId = ''} = useParams();
  const user = useAuthStore((s) => s.user);
  const [sub, setSub] = useState<CodeSub>('browse');
  const [repos, setRepos] = useState<Array<Record<string, unknown>>>([]);
  const [busy, setBusy] = useState('');
  const [err, setErr] = useState('');
  const [msg, setMsg] = useState('');

  // Browse state
  const [repoId, setRepoId] = useState('');
  const [files, setFiles] = useState<string[] | null>(null);
  const [active, setActive] = useState<string | null>(null);
  const [content, setContent] = useState<Record<string, string>>({});

  // Other tabs
  const [debt, setDebt] = useState<api.TechDebtReport | null | undefined>(undefined);
  const [docs, setDocs] = useState<api.GeneratedDocRow[] | null>(null);
  const [health, setHealth] = useState<api.WorkspaceHealthReport | null | undefined>(undefined);
  const [standards, setStandards] = useState<api.ComplianceReport | null | undefined>(undefined);

  useEffect(() => {
    if (!projectId) return;
    api
      .getProjectRepos(projectId)
      .then((l) => {
        const arr = l as unknown as Array<Record<string, unknown>>;
        setRepos(arr);
        if (arr[0]) setRepoId(repoIdFor(arr[0]));
      })
      .catch(() => setRepos([]));
    api
      .getTechDebt(projectId, 30)
      .then(setDebt)
      .catch(() => setDebt(null));
    Promise.all([
      api.getProjectDoc(projectId, 'EXPLANATION').catch(() => null),
      api.getProjectDoc(projectId, 'SETUP').catch(() => null),
    ])
      .then(([e, s]) => setDocs([e, s].filter(Boolean) as api.GeneratedDocRow[]))
      .catch(() => setDocs([]));
  }, [projectId]);

  // Browse — file list per selected repo.
  useEffect(() => {
    if (sub !== 'browse' || !repoId) return;
    setFiles(null);
    setActive(null);
    api
      .listRepoFiles(projectId, repoId)
      .then((r) => setFiles(r.files ?? []))
      .catch(() => setFiles([]));
  }, [projectId, repoId, sub]);

  // Health — lazy on tab open.
  useEffect(() => {
    if (sub !== 'health' || health !== undefined) return;
    api
      .getWorkspaceHealth(projectId)
      .then(setHealth)
      .catch(() => setHealth(null));
  }, [sub, projectId, health]);

  // Standards (TEAM+) — needs orgId.
  useEffect(() => {
    if (sub !== 'standards' || standards !== undefined) return;
    const orgId = user?.orgId;
    if (!orgId) {
      setStandards(null);
      return;
    }
    api
      .getStandardsReport(projectId, orgId, 30)
      .then(setStandards)
      .catch(() => setStandards(null));
  }, [sub, projectId, standards, user?.orgId]);

  async function open(path: string) {
    setActive(path);
    if (content[path]) return;
    try {
      const f = await api.readRepoFile(projectId, repoId, path);
      setContent((c) => ({...c, [path]: f.content ?? ''}));
    } catch {
      setContent((c) => ({...c, [path]: '// could not load'}));
    }
  }

  async function autofixCategory(category: string) {
    setBusy(`fix-${category}`);
    setErr('');
    setMsg('');
    try {
      const res = await api.dispatchTechDebtFix(projectId, category);
      setMsg(res.runId ? `Autofix dispatched: R-${res.runId.slice(0, 6)}` : 'Autofix dispatched.');
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Autofix failed.');
    } finally {
      setBusy('');
    }
  }

  async function genDoc(t: api.GeneratedDocType) {
    setBusy(`gen-${t}`);
    setErr('');
    setMsg('');
    try {
      await api.generateProjectDoc(projectId, t);
      setMsg(`${t.toLowerCase()} doc regeneration started.`);
      const [e, s] = await Promise.all([
        api.getProjectDoc(projectId, 'EXPLANATION').catch(() => null),
        api.getProjectDoc(projectId, 'SETUP').catch(() => null),
      ]);
      setDocs([e, s].filter(Boolean) as api.GeneratedDocRow[]);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Doc generation failed.');
    } finally {
      setBusy('');
    }
  }

  async function dlDoc(t: api.GeneratedDocType) {
    setBusy(`dl-${t}`);
    try {
      await api.downloadProjectDoc(projectId, t);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Download failed.');
    } finally {
      setBusy('');
    }
  }

  // Tech-debt rollup is keyed by category (Record<string, count>).
  const debtCats = useMemo(() => {
    if (!debt) return [] as Array<{name: string; count: number}>;
    return Object.entries(debt.issuesByCategory || {})
      .map(([name, count]) => ({name, count}))
      .sort((a, b) => b.count - a.count);
  }, [debt]);

  // Code-review target — the repo currently chosen in the shared header
  // <select> (falls back to the first linked repo). The full review surface
  // (scan / severity-grouped issues / autofix / report) is per-repo.
  const reviewRepoId = repoId || (repos[0] ? repoIdFor(repos[0]) : '');
  const reviewRepo = useMemo(
    () => repos.find((r) => repoIdFor(r) === reviewRepoId) ?? null,
    [repos, reviewRepoId],
  );

  const browseIsSplit = sub === 'browse';

  return (
    <div
      className='ide-pane'
      style={{
        display: 'flex',
        flexDirection: 'column',
        height: '100%',
        maxWidth: 'none',
        padding: browseIsSplit ? 0 : undefined,
      }}
    >
      <div style={browseIsSplit ? {padding: '16px 16px 0'} : undefined}>
        <ProjectTabs projectId={projectId} />
        <div style={{display: 'flex', alignItems: 'center', gap: 10, marginBottom: 8}}>
          <div className='ide-h1' style={{margin: 0}}>
            Code &amp; quality
          </div>
          {(sub === 'browse' || sub === 'review') && repos.length > 0 && (
            <select
              className='ide-select'
              value={repoId}
              onChange={(e) => setRepoId(e.target.value)}
              style={{width: 'auto'}}
            >
              {repos.map((r) => (
                <option key={repoIdFor(r)} value={repoIdFor(r)}>
                  {repoDisplayName(r)}
                </option>
              ))}
            </select>
          )}
        </div>
        <RTabs
          active={sub}
          onSelect={(k) => setSub(k as CodeSub)}
          tabs={[
            {key: 'browse', label: `Browse · ${repos.length}`},
            {key: 'debt', label: `Tech debt · ${debtCats.length}`},
            {key: 'review', label: 'Code review'},
            {key: 'health', label: 'Health'},
            {key: 'standards', label: 'Standards'},
            {key: 'docs', label: `Docs · ${docs?.length ?? 0}`},
          ]}
        />
        {sub !== 'browse' && <Banner err={err} msg={msg} />}
      </div>

      {/* ── Browse ── */}
      {sub === 'browse' &&
        (repos.length === 0 ? (
          <div className='ide-empty'>No repos linked.</div>
        ) : (
          <div style={{flex: 1, minHeight: 0, position: 'relative'}}>
            <div className='ide-split'>
              <div className='ide-split-files' style={{width: 260}}>
                {files === null ? (
                  <IdeLoader label='Loading files…' pad={20} />
                ) : (
                  <IdeFileTree files={files} activePath={active} onOpen={open} />
                )}
              </div>
              <div className='ide-split-main'>
                {active ? (
                  <IdeCodeViewer filename={active} content={content[active] ?? '…'} />
                ) : (
                  <div className='ide-empty'>Select a file to browse the live source.</div>
                )}
              </div>
            </div>
          </div>
        ))}

      {/* ── Tech debt ── */}
      {sub === 'debt' && (
        <div style={{overflowY: 'auto', minHeight: 0}}>
          {debt === undefined ? (
            <IdeLoader label='Loading tech debt…' />
          ) : debtCats.length === 0 ? (
            <div className='ide-empty'>
              No tech debt detected. Run a code-review scan to surface issues.
            </div>
          ) : (
            debtCats.map((c) => (
              <div key={c.name} className='ide-card' style={{marginBottom: 8}}>
                <div style={{display: 'flex', alignItems: 'center', gap: 10}}>
                  <span style={{flex: 1, fontWeight: 600, color: 'var(--ide-fg-bright)'}}>
                    {c.name}
                  </span>
                  <IdeBadge kind='warn'>{c.count}</IdeBadge>
                  <button
                    className='ide-btn'
                    style={{padding: '4px 10px'}}
                    disabled={busy === `fix-${c.name}`}
                    onClick={() => autofixCategory(c.name)}
                  >
                    {busy === `fix-${c.name}` ? 'Dispatching…' : `Autofix ${c.count} →`}
                  </button>
                </div>
              </div>
            ))
          )}
        </div>
      )}

      {/* ── Code review (full whole-repo surface) ── */}
      {sub === 'review' && (
        <div style={{overflowY: 'auto', minHeight: 0}}>
          {repos.length === 0 ? (
            <div className='ide-empty'>No repos linked — link one to enable per-repo review.</div>
          ) : !reviewRepo ? (
            <IdeLoader label='Loading…' />
          ) : (
            <CodeReviewPanel
              key={reviewRepoId}
              projectId={projectId}
              repoId={reviewRepoId}
              repoFullName={repoDisplayName(reviewRepo)}
              variant='ide'
            />
          )}
        </div>
      )}

      {/* ── Health ── */}
      {sub === 'health' && (
        <div style={{overflowY: 'auto', minHeight: 0}}>
          {health === undefined ? (
            <IdeLoader label='Loading workspace health…' />
          ) : !health || (health.repos || []).length === 0 ? (
            <div className='ide-empty'>No repos linked.</div>
          ) : (
            health.repos.map((r) => {
              const drift = r.isStale;
              return (
                <div key={r.repoId} className='ide-card' style={{marginBottom: 8}}>
                  <div style={{display: 'flex', alignItems: 'center', gap: 10, marginBottom: 10}}>
                    <div style={{flex: 1, minWidth: 0}}>
                      <div style={{fontWeight: 600, color: 'var(--ide-fg-bright)'}}>
                        {r.repoName}
                      </div>
                      <div
                        className='ide-mono'
                        style={{fontSize: 11, color: 'var(--ide-fg-muted)'}}
                      >
                        {(r.currentOriginSha || '').slice(0, 7) || '—'}{' '}
                        {drift ? '· stale vs origin' : '· in sync'}
                      </div>
                    </div>
                    <IdeBadge kind={drift ? 'warn' : 'ok'}>{drift ? 'Drift' : 'Healthy'}</IdeBadge>
                  </div>
                  <div
                    style={{
                      display: 'grid',
                      gridTemplateColumns: 'repeat(4, 1fr)',
                      gap: 10,
                      paddingTop: 10,
                      borderTop: '1px solid var(--ide-border)',
                      fontSize: 12.5,
                    }}
                  >
                    <div>
                      <MetaLabel>Branch</MetaLabel>
                      <div className='ide-mono' style={{fontWeight: 600}}>
                        {r.defaultBranch || '—'}
                      </div>
                    </div>
                    <div>
                      <MetaLabel>Codemap</MetaLabel>
                      <div
                        className='ide-mono'
                        style={{fontWeight: 600, color: drift ? 'var(--ide-yellow)' : undefined}}
                      >
                        {(r.lastCodeMapSha || '').slice(0, 7) || '—'}
                      </div>
                    </div>
                    <div>
                      <MetaLabel>Webhook</MetaLabel>
                      <div
                        style={{
                          fontWeight: 600,
                          color: r.webhookInstalled ? 'var(--ide-green)' : 'var(--ide-red)',
                        }}
                      >
                        {r.webhookInstalled ? '✓' : '○'}
                      </div>
                    </div>
                    <div>
                      <MetaLabel>Open PRs</MetaLabel>
                      <div style={{fontWeight: 600}}>{r.openPrCount ?? 0}</div>
                    </div>
                  </div>
                </div>
              );
            })
          )}
        </div>
      )}

      {/* ── Standards (TEAM+) ── */}
      {sub === 'standards' && (
        <div style={{overflowY: 'auto', minHeight: 0}}>
          {!user?.orgId ? (
            <div className='ide-empty'>Standards compliance requires an org.</div>
          ) : standards === undefined ? (
            <IdeLoader label='Loading standards compliance…' />
          ) : (
            <>
              <div className='ide-card' style={{marginBottom: 12, borderColor: 'var(--ide-blue)'}}>
                <div
                  style={{
                    fontSize: 11,
                    fontWeight: 700,
                    color: 'var(--ide-blue)',
                    letterSpacing: '0.14em',
                    textTransform: 'uppercase',
                    marginBottom: 6,
                  }}
                >
                  Org standards · last 30 days
                </div>
                <div style={{fontSize: 13, lineHeight: 1.5}}>
                  Naming conventions, lint rules, coverage SLAs and other policies your org has set.
                </div>
              </div>
              {(() => {
                const viols = standards?.recentViolations || [];
                if (viols.length === 0) {
                  return (
                    <div className='ide-empty'>No standards violations in the last 30 days.</div>
                  );
                }
                return viols.map((v) => {
                  const sev = (v.severity || 'MINOR').toUpperCase();
                  return (
                    <div
                      key={v.id}
                      className='ide-card'
                      style={{
                        display: 'flex',
                        alignItems: 'center',
                        gap: 10,
                        marginBottom: 6,
                      }}
                    >
                      <div style={{flex: 1, minWidth: 0}}>
                        <div style={{fontWeight: 600}}>{v.standardId || 'Standard violation'}</div>
                        <div style={{fontSize: 12, color: 'var(--ide-fg-muted)'}}>
                          {v.summary?.slice(0, 80)}
                        </div>
                      </div>
                      <IdeBadge
                        kind={sev === 'BLOCKING' ? 'bad' : sev === 'WARNING' ? 'warn' : 'ok'}
                      >
                        {sev}
                      </IdeBadge>
                    </div>
                  );
                });
              })()}
            </>
          )}
        </div>
      )}

      {/* ── Docs ── */}
      {sub === 'docs' && (
        <div style={{overflowY: 'auto', minHeight: 0}}>
          {(docs || []).length === 0 ? (
            <div className='ide-empty'>No docs generated yet. Use the buttons below.</div>
          ) : (
            (docs || []).map((d) => (
              <div
                key={d.docType}
                className='ide-card'
                style={{display: 'flex', alignItems: 'center', gap: 10, marginBottom: 6}}
              >
                <span className='ide-mono' style={{flex: 1}}>
                  {d.docType}.md
                </span>
                <span style={{fontSize: 11, color: 'var(--ide-fg-muted)'}}>
                  {d.generatedAt
                    ? `updated ${new Date(d.generatedAt).toLocaleDateString()}`
                    : 'fresh'}
                </span>
                <button
                  className='ide-btn ghost'
                  style={{padding: '4px 10px'}}
                  disabled={busy === `dl-${d.docType}`}
                  onClick={() => dlDoc(d.docType)}
                >
                  Download
                </button>
                <button
                  className='ide-btn ghost'
                  style={{padding: '4px 10px'}}
                  disabled={busy === `gen-${d.docType}`}
                  onClick={() => genDoc(d.docType)}
                >
                  Regen
                </button>
              </div>
            ))
          )}
          <div style={{display: 'flex', gap: 8, marginTop: 8}}>
            <button
              className='ide-btn ghost'
              disabled={busy === 'gen-EXPLANATION'}
              onClick={() => genDoc('EXPLANATION')}
            >
              {busy === 'gen-EXPLANATION' ? 'Generating…' : 'Generate EXPLANATION.md'}
            </button>
            <button
              className='ide-btn ghost'
              disabled={busy === 'gen-SETUP'}
              onClick={() => genDoc('SETUP')}
            >
              {busy === 'gen-SETUP' ? 'Generating…' : 'Generate SETUP.md'}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

// ───────────────────────── Architecture (Current · Versions · Invariants · ADRs · Contracts) ─────────────────────────
type ArchSub = 'current' | 'versions' | 'invariants' | 'adrs' | 'contracts';

// Split a markdown doc into a preamble (text before the first `##`) + top-level (`##`) sections.
// Heading text is lifted out so the section header can be a clickable disclosure; the body keeps
// everything else (incl. `###` subsections, code, tables) for Markdown.
function parseArchDoc(md: string): {preamble: string; sections: {heading: string; body: string}[]} {
  const sections: {heading: string; body: string[]}[] = [];
  const preamble: string[] = [];
  for (const line of md.split('\n')) {
    const m = /^##\s+(.*)$/.exec(line);
    if (m) sections.push({heading: m[1].trim(), body: []});
    else if (sections.length > 0) sections[sections.length - 1].body.push(line);
    else preamble.push(line);
  }
  return {
    preamble: preamble.join('\n'),
    sections: sections.map((s) => ({heading: s.heading, body: s.body.join('\n')})),
  };
}

// Architecture-doc viewer — lazy + section-collapsible so the browser is never handed one giant
// markdown blob. Small docs (<40k chars, ≤10 sections) render fully expanded (no behaviour change);
// larger docs collapse all but the first section and mount each section's Markdown only on expand.
// Keyed by version at the call site, so it re-inits cleanly when a new doc loads.
function ArchDocView({md}: {md: string}) {
  const {preamble, sections} = useMemo(() => parseArchDoc(md), [md]);
  const large = md.length > 40_000 || sections.length > 10;
  const [open, setOpen] = useState<Set<number>>(
    () => new Set(large ? [0] : sections.map((_, i) => i)),
  );
  const toggle = (i: number) =>
    setOpen((s) => {
      const n = new Set(s);
      if (n.has(i)) n.delete(i);
      else n.add(i);
      return n;
    });
  const allOpen = sections.length > 0 && open.size === sections.length;

  // No top-level headings — fall back to a single Markdown render.
  if (sections.length === 0) {
    return (
      <div className='signal-md' style={{fontSize: 13.5, lineHeight: 1.6}}>
        <Markdown>{md}</Markdown>
      </div>
    );
  }
  return (
    <div>
      {preamble.trim() && (
        <div className='signal-md' style={{fontSize: 13.5, lineHeight: 1.6, marginBottom: 8}}>
          <Markdown>{preamble}</Markdown>
        </div>
      )}
      {sections.length > 1 && (
        <div style={{display: 'flex', justifyContent: 'flex-end', marginBottom: 6}}>
          <button
            type='button'
            className='ide-btn ghost'
            style={{padding: '3px 10px', fontSize: 12}}
            onClick={() => setOpen(allOpen ? new Set() : new Set(sections.map((_, i) => i)))}
          >
            {allOpen ? 'Collapse all' : 'Expand all'}
          </button>
        </div>
      )}
      {sections.map((s, i) => {
        const isOpen = open.has(i);
        return (
          <div
            key={i}
            className='ide-card'
            style={{padding: 0, marginBottom: 8, overflow: 'hidden'}}
          >
            <button
              type='button'
              onClick={() => toggle(i)}
              aria-expanded={isOpen}
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 8,
                width: '100%',
                padding: '10px 14px',
                background: 'none',
                border: 'none',
                cursor: 'pointer',
                textAlign: 'left',
                color: 'var(--ide-fg-bright)',
                fontWeight: 600,
                fontSize: 13.5,
              }}
            >
              <span className='ide-tree-chev' style={{color: 'var(--ide-fg-muted)'}}>
                {isOpen ? '▾' : '▸'}
              </span>
              <span>{s.heading}</span>
            </button>
            {isOpen && (
              <div
                className='signal-md'
                style={{padding: '0 14px 12px', fontSize: 13.5, lineHeight: 1.6}}
              >
                <Markdown>{s.body}</Markdown>
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}

export function IdeProjectArchitecture() {
  const {projectId = ''} = useParams();
  const [sub, setSub] = useState<ArchSub>('current');
  const [busy, setBusy] = useState('');
  const [err, setErr] = useState('');
  const [msg, setMsg] = useState('');

  const [arch, setArch] = useState<ArchitectureDoc | null | undefined>(undefined);
  const [staleness, setStaleness] = useState<api.StalenessReport | null>(null);
  const [versions, setVersions] = useState<ArchitectureDoc[] | null>(null);
  const [invariants, setInvariants] = useState<api.InvariantSummary[] | null>(null);
  const [violations, setViolations] = useState<api.InvariantViolation[]>([]);
  const [adrs, setAdrs] = useState<api.AdrSummary[] | null>(null);
  const [repos, setRepos] = useState<Array<Record<string, unknown>>>([]);
  const [coverage, setCoverage] = useState<Record<
    string,
    api.ContractCoverageResponse | null
  > | null>(null);
  const [contractBusy, setContractBusy] = useState('');
  // Contract whose drift the user is dismissing (drives the PromptModal).
  const [dismissFor, setDismissFor] = useState<string | null>(null);

  // Version diff picker
  const [diffFrom, setDiffFrom] = useState<number | null>(null);
  const [diffTo, setDiffTo] = useState<number | null>(null);
  const [diffReport, setDiffReport] = useState<api.ArchDiffReport | null>(null);
  const [diffBusy, setDiffBusy] = useState(false);

  function refresh() {
    if (!projectId) return;
    Promise.allSettled([
      api.getArchitectureDoc(projectId),
      api.getArchitectureVersions(projectId),
      api.listProjectInvariants(projectId),
      api.listProjectInvariantViolations(projectId),
      api.listProjectAdrs(projectId, false),
      api.getArchitectureStaleness(projectId),
      api.getProjectRepos(projectId),
    ]).then(async ([a, v, inv, viol, adr, st, rp]) => {
      setArch(a.status === 'fulfilled' ? a.value : null);
      setVersions(v.status === 'fulfilled' ? (v.value as ArchitectureDoc[]) : []);
      setInvariants(inv.status === 'fulfilled' ? inv.value : []);
      if (viol.status === 'fulfilled') setViolations(viol.value);
      setAdrs(adr.status === 'fulfilled' ? adr.value : []);
      if (st.status === 'fulfilled') setStaleness(st.value);
      if (rp.status === 'fulfilled') {
        const list = rp.value as unknown as Array<Record<string, unknown>>;
        setRepos(list);
        const entries = await Promise.all(
          list.map(async (r) => {
            const rid = repoIdFor(r);
            try {
              return [rid, await api.getContractCoverage(projectId, rid)] as const;
            } catch {
              return [rid, null] as const;
            }
          }),
        );
        setCoverage(Object.fromEntries(entries));
      }
    });
  }

  useEffect(() => {
    refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId]);

  async function regenerate() {
    setBusy('regen');
    setErr('');
    setMsg('');
    try {
      await api.generateArchitecture(projectId);
      setMsg('Architecture regeneration started.');
      refresh();
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Regenerate failed.');
    } finally {
      setBusy('');
    }
  }

  async function compare() {
    if (diffFrom == null || diffTo == null) return;
    setDiffBusy(true);
    setErr('');
    try {
      setDiffReport(await api.getArchitectureDiff(projectId, diffFrom, diffTo));
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Diff failed.');
    } finally {
      setDiffBusy(false);
    }
  }

  async function rescanCoverage(repoId: string) {
    setContractBusy(`scan-${repoId}`);
    setErr('');
    setMsg('');
    try {
      const next = await api.rescanContractCoverage(projectId, repoId);
      setCoverage((p) => ({...(p ?? {}), [repoId]: next}));
      setMsg('Coverage rescan complete.');
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Rescan failed.');
    } finally {
      setContractBusy('');
    }
  }

  async function drift(repoId: string) {
    setContractBusy(`drift-${repoId}`);
    setErr('');
    setMsg('');
    try {
      const r = await api.checkRepoDrift(projectId, repoId);
      const n = r.filter((d) => d.hasDrift).length;
      setMsg(n > 0 ? `${n} drift result${n === 1 ? '' : 's'} detected.` : 'No drift detected.');
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Drift check failed.');
    } finally {
      setContractBusy('');
    }
  }

  async function refreshContract(contractId: string) {
    setContractBusy(`refresh-${contractId}`);
    setErr('');
    setMsg('');
    try {
      const r = await api.refreshContractFromSource(projectId, contractId);
      setMsg(r.prUrl ? `Refresh PR opened: ${r.branchName}` : 'Contract refreshed.');
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Refresh failed.');
    } finally {
      setContractBusy('');
    }
  }

  async function dismissDrift(contractId: string, rationale: string) {
    setContractBusy(`dismiss-${contractId}`);
    setErr('');
    setMsg('');
    try {
      const r = await api.dismissContractDrift(projectId, contractId, rationale);
      setMsg(`${r.dismissedCount} drift event${r.dismissedCount === 1 ? '' : 's'} dismissed.`);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Dismiss failed.');
    } finally {
      setContractBusy('');
    }
  }

  async function checkAllDrift() {
    setContractBusy('all');
    setErr('');
    setMsg('');
    try {
      await api.checkProjectDrift(projectId);
      setMsg('Project-wide drift check started.');
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Drift check failed.');
    } finally {
      setContractBusy('');
    }
  }

  const violByInv = useMemo(() => {
    const m = new Map<string, number>();
    for (const v of violations) {
      if (v.invariantUuid) m.set(v.invariantUuid, (m.get(v.invariantUuid) ?? 0) + 1);
    }
    return m;
  }, [violations]);

  const docMd = arch?.content || '';

  return (
    <div className='ide-pane'>
      <ProjectTabs projectId={projectId} />
      <div style={{display: 'flex', alignItems: 'center', gap: 10}}>
        <div className='ide-h1' style={{margin: 0}}>
          Architecture
        </div>
        {arch?.version != null && (
          <span style={{fontSize: 12, color: 'var(--ide-fg-muted)'}}>v{arch.version}</span>
        )}
        <span style={{flex: 1}} />
        <button className='ide-btn ghost' onClick={regenerate} disabled={busy === 'regen'}>
          {busy === 'regen' ? 'Regenerating…' : '↻ Regenerate'}
        </button>
      </div>
      <div style={{marginTop: 12}}>
        <RTabs
          active={sub}
          onSelect={(k) => setSub(k as ArchSub)}
          tabs={[
            {key: 'current', label: 'Current'},
            {key: 'versions', label: `Versions · ${versions?.length ?? 0}`},
            {key: 'invariants', label: `Invariants · ${invariants?.length ?? 0}`},
            {key: 'adrs', label: `ADRs · ${adrs?.length ?? 0}`},
            {key: 'contracts', label: 'Contracts'},
          ]}
        />
      </div>
      <Banner err={err} msg={msg} />

      {/* ── Current ── */}
      {sub === 'current' &&
        (arch === undefined ? (
          <IdeLoader label='Loading architecture…' />
        ) : (
          <>
            <div className='ide-card'>
              <div style={{display: 'flex', alignItems: 'center', marginBottom: 12, gap: 10}}>
                <div style={{flex: 1}}>
                  <div
                    style={{
                      fontSize: 11,
                      fontWeight: 700,
                      color: 'var(--ide-fg-muted)',
                      letterSpacing: '0.14em',
                      textTransform: 'uppercase',
                    }}
                  >
                    Architecture
                  </div>
                  <div style={{fontSize: 15, fontWeight: 600, color: 'var(--ide-fg-bright)'}}>
                    {arch?.version ? `v${arch.version}` : 'Latest'}
                  </div>
                </div>
                <IdeBadge kind={staleness?.stale ? 'warn' : 'ok'}>
                  {staleness?.stale ? 'Stale' : 'Up to date'}
                </IdeBadge>
              </div>
              {docMd ? (
                <div style={{maxHeight: '70vh', overflow: 'auto'}}>
                  <ArchDocView key={`v${arch?.version ?? 0}`} md={docMd} />
                </div>
              ) : (
                <div className='ide-empty' style={{padding: '12px 0'}}>
                  No architecture doc yet — click Regenerate to build one from your repos.
                </div>
              )}
            </div>
            <button
              className='ide-btn ghost'
              style={{marginTop: 12}}
              onClick={regenerate}
              disabled={busy === 'regen'}
            >
              {busy === 'regen' ? 'Regenerating…' : 'Regenerate codemap + arch doc'}
            </button>
          </>
        ))}

      {/* ── Versions ── */}
      {sub === 'versions' &&
        (versions === null ? (
          <IdeLoader label='Loading versions…' />
        ) : versions.length === 0 ? (
          <div className='ide-empty'>No previous versions yet.</div>
        ) : (
          <>
            <div className='ide-card' style={{marginBottom: 12}}>
              <div className='ide-h2' style={{marginTop: 0}}>
                Compare versions
              </div>
              <div style={{display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap'}}>
                <select
                  className='ide-select'
                  style={{width: 'auto'}}
                  value={diffFrom ?? ''}
                  onChange={(e) => setDiffFrom(e.target.value ? Number(e.target.value) : null)}
                >
                  <option value=''>From version…</option>
                  {versions.map((v) => (
                    <option key={v.version} value={v.version}>
                      v{v.version}
                    </option>
                  ))}
                </select>
                <span style={{color: 'var(--ide-fg-muted)'}}>→</span>
                <select
                  className='ide-select'
                  style={{width: 'auto'}}
                  value={diffTo ?? ''}
                  onChange={(e) => setDiffTo(e.target.value ? Number(e.target.value) : null)}
                >
                  <option value=''>To version…</option>
                  {versions.map((v) => (
                    <option key={v.version} value={v.version}>
                      v{v.version}
                    </option>
                  ))}
                </select>
                <button
                  className='ide-btn'
                  disabled={diffBusy || diffFrom == null || diffTo == null}
                  onClick={compare}
                >
                  {diffBusy ? 'Comparing…' : 'Compare'}
                </button>
              </div>
              {diffReport && (
                <div style={{marginTop: 12, fontSize: 13}}>
                  <div style={{fontWeight: 600, marginBottom: 6, color: 'var(--ide-fg-bright)'}}>
                    v{diffReport.fromVersion} → v{diffReport.toVersion} · {diffReport.totalFiles}{' '}
                    file{diffReport.totalFiles === 1 ? '' : 's'} changed
                  </div>
                  {diffReport.totalFiles === 0 ? (
                    <span style={{color: 'var(--ide-fg-muted)'}}>No source-file changes.</span>
                  ) : (
                    <div style={{maxHeight: 260, overflowY: 'auto'}}>
                      {diffReport.perRepo.map((pr) => (
                        <div key={pr.repoFullName} style={{marginBottom: 8}}>
                          <div
                            className='ide-mono'
                            style={{fontSize: 11, color: 'var(--ide-fg-muted)', marginBottom: 4}}
                          >
                            {pr.repoFullName}
                          </div>
                          <ul style={{listStyle: 'none', padding: 0, margin: 0}}>
                            {pr.files.map((f, i) => {
                              const status = (f.status || 'modified').toUpperCase();
                              const color =
                                status === 'ADDED'
                                  ? 'var(--ide-green)'
                                  : status === 'REMOVED'
                                    ? 'var(--ide-red)'
                                    : 'var(--ide-yellow)';
                              return (
                                <li
                                  key={i}
                                  className='ide-mono'
                                  style={{
                                    fontSize: 12,
                                    padding: '3px 0',
                                    whiteSpace: 'nowrap',
                                    overflow: 'hidden',
                                    textOverflow: 'ellipsis',
                                  }}
                                >
                                  <span style={{color, fontWeight: 700, marginRight: 8}}>
                                    {status[0]}
                                  </span>
                                  {f.filename}
                                </li>
                              );
                            })}
                          </ul>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              )}
            </div>
            <div className='ide-card' style={{padding: 0}}>
              {versions.map((v, i) => (
                <div
                  key={v.version ?? i}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 10,
                    padding: '11px 16px',
                    borderBottom: i < versions.length - 1 ? '1px solid var(--ide-border)' : 'none',
                  }}
                >
                  <span style={{flex: 1, fontWeight: 600}}>v{v.version ?? i + 1}</span>
                  <span style={{fontSize: 12, color: 'var(--ide-fg-muted)'}}>
                    {v.generatedAt ? new Date(v.generatedAt).toLocaleString() : ''}
                  </span>
                </div>
              ))}
            </div>
          </>
        ))}

      {/* ── Invariants ── */}
      {sub === 'invariants' &&
        (invariants === null ? (
          <IdeLoader label='Loading invariants…' />
        ) : invariants.length === 0 ? (
          <div className='ide-empty'>No invariants declared yet.</div>
        ) : (
          <div className='ide-card' style={{padding: 0}}>
            {invariants.map((inv, i) => {
              const count = violByInv.get(inv.id) ?? 0;
              return (
                <div
                  key={inv.id}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 10,
                    padding: '11px 16px',
                    borderBottom:
                      i < invariants.length - 1 ? '1px solid var(--ide-border)' : 'none',
                  }}
                >
                  <span className='ide-mono' style={{flex: 1, fontWeight: 600}}>
                    {inv.invariantId}
                  </span>
                  <IdeBadge kind={count > 0 ? 'bad' : 'ok'}>
                    {count > 0 ? `${count} FAIL` : 'PASS'}
                  </IdeBadge>
                </div>
              );
            })}
          </div>
        ))}

      {/* ── ADRs ── */}
      {sub === 'adrs' &&
        (adrs === null ? (
          <IdeLoader label='Loading ADRs…' />
        ) : adrs.length === 0 ? (
          <div className='ide-empty'>No ADRs yet.</div>
        ) : (
          <div className='ide-card' style={{padding: 0}}>
            {adrs.map((adr, i) => {
              const status = (adr.status || 'PROPOSED').toUpperCase();
              return (
                <div
                  key={adr.id}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 10,
                    padding: '11px 16px',
                    borderBottom: i < adrs.length - 1 ? '1px solid var(--ide-border)' : 'none',
                  }}
                >
                  <div style={{flex: 1, minWidth: 0}}>
                    <span style={{fontWeight: 600}}>
                      ADR {String(adr.adrIdInRepo ?? '').padStart(4, '0')} ·{' '}
                      {adr.title || 'untitled'}
                    </span>
                    {adr.decisionDate && (
                      <span style={{fontSize: 12, color: 'var(--ide-fg-muted)', marginLeft: 8}}>
                        {new Date(adr.decisionDate).toLocaleDateString()}
                      </span>
                    )}
                  </div>
                  <IdeBadge
                    kind={status === 'ACCEPTED' ? 'ok' : status === 'DEPRECATED' ? 'bad' : 'warn'}
                  >
                    {status}
                  </IdeBadge>
                </div>
              );
            })}
          </div>
        ))}

      {/* ── Contracts ── */}
      {sub === 'contracts' &&
        (repos.length === 0 ? (
          <div className='ide-empty'>
            Link repos to detect contract schemas (OpenAPI / GraphQL / gRPC).
          </div>
        ) : coverage === null ? (
          <IdeLoader label='Loading coverage…' />
        ) : (
          <>
            {repos.map((rep) => {
              const repoId = repoIdFor(rep);
              const repoName = repoDisplayName(rep);
              const cov = coverage[repoId];
              const detectors = cov?.scans ?? [];
              const gaps = cov?.knownGaps ?? [];
              const scanned = detectors.filter((d) => d.status === 'SCANNED').length;
              const failed = detectors.filter((d) => d.status === 'FAILED').length;
              const totalContracts = detectors.reduce(
                (a, d) => a + (d.contractIds?.length ?? 0),
                0,
              );
              const driftIds = Array.from(
                new Set(detectors.flatMap((d) => d.contractIds ?? [])),
              ).slice(0, 1);
              return (
                <div key={repoId} className='ide-card' style={{marginBottom: 8}}>
                  <div style={{display: 'flex', alignItems: 'center', gap: 10, marginBottom: 8}}>
                    <div style={{flex: 1, minWidth: 0}}>
                      <div style={{fontWeight: 600, color: 'var(--ide-fg-bright)'}}>{repoName}</div>
                      <div style={{fontSize: 12, color: 'var(--ide-fg-muted)'}}>
                        {cov == null
                          ? 'No coverage data — run a scan'
                          : `${scanned} detector${scanned === 1 ? '' : 's'} scanned${failed > 0 ? ` · ${failed} failed` : ''} · ${totalContracts} contract${totalContracts === 1 ? '' : 's'}`}
                      </div>
                    </div>
                    <button
                      className='ide-btn ghost'
                      style={{padding: '4px 10px'}}
                      disabled={contractBusy === `scan-${repoId}`}
                      onClick={() => rescanCoverage(repoId)}
                    >
                      {contractBusy === `scan-${repoId}` ? 'Scanning…' : 'Rescan'}
                    </button>
                    <button
                      className='ide-btn ghost'
                      style={{padding: '4px 10px'}}
                      disabled={contractBusy === `drift-${repoId}`}
                      onClick={() => drift(repoId)}
                    >
                      {contractBusy === `drift-${repoId}` ? 'Checking…' : 'Check drift'}
                    </button>
                  </div>
                  {detectors.length > 0 && (
                    <div
                      style={{
                        display: 'flex',
                        gap: 6,
                        flexWrap: 'wrap',
                        marginBottom: gaps.length ? 8 : 0,
                      }}
                    >
                      {detectors.map((d, i) => (
                        <IdeBadge
                          key={`${repoId}-${d.detector}-${i}`}
                          kind={
                            d.status === 'SCANNED' ? 'ok' : d.status === 'FAILED' ? 'bad' : 'warn'
                          }
                        >
                          {d.type}
                          {d.contractIds?.length ? ` · ${d.contractIds.length}` : ''}
                        </IdeBadge>
                      ))}
                    </div>
                  )}
                  {gaps.length > 0 && (
                    <div
                      style={{
                        borderTop: '1px solid var(--ide-border)',
                        paddingTop: 8,
                        fontSize: 12,
                        color: 'var(--ide-fg-muted)',
                      }}
                    >
                      <div
                        style={{fontWeight: 600, color: 'var(--ide-fg-bright)', marginBottom: 4}}
                      >
                        {gaps.length} known gap{gaps.length === 1 ? '' : 's'}
                      </div>
                      {gaps.slice(0, 3).map((g, i) => (
                        <div key={i}>· {g.explanation || g.category}</div>
                      ))}
                    </div>
                  )}
                  {driftIds.length > 0 && (
                    <div style={{display: 'flex', gap: 6, marginTop: 8}}>
                      <button
                        className='ide-btn ghost'
                        style={{padding: '3px 9px', fontSize: 12}}
                        disabled={contractBusy === `refresh-${driftIds[0]}`}
                        onClick={() => refreshContract(driftIds[0])}
                      >
                        {contractBusy === `refresh-${driftIds[0]}`
                          ? 'Refreshing…'
                          : 'Refresh from source'}
                      </button>
                      <button
                        className='ide-btn ghost'
                        style={{padding: '3px 9px', fontSize: 12}}
                        disabled={contractBusy === `dismiss-${driftIds[0]}`}
                        onClick={() => setDismissFor(driftIds[0])}
                      >
                        Dismiss drift
                      </button>
                    </div>
                  )}
                </div>
              );
            })}
            <button
              className='ide-btn ghost'
              style={{marginTop: 8}}
              disabled={contractBusy === 'all'}
              onClick={checkAllDrift}
            >
              {contractBusy === 'all' ? 'Checking…' : 'Check drift across all repos'}
            </button>
          </>
        ))}

      <PromptModal
        open={!!dismissFor}
        title='Dismiss drift'
        fields={[
          {
            name: 'rationale',
            label: 'Why dismiss this drift? (≥10 chars)',
            multiline: true,
            required: true,
          },
        ]}
        submitLabel='Dismiss'
        // eslint-disable-next-line no-restricted-syntax -- PromptModal callback prop (modal owns submit lifecycle); ≥10-char guard preserved below.
        onSubmit={async (v) => {
          const contractId = dismissFor;
          setDismissFor(null);
          const rationale = v.rationale.trim();
          if (!contractId || !rationale || rationale.length < 10) return;
          await dismissDrift(contractId, rationale);
        }}
        onClose={() => setDismissFor(null)}
      />
    </div>
  );
}

// ───────────────────────── Members (team · invites · share links) ─────────────────────────
const INVITE_ROLES: api.ProjectInviteRole[] = [
  'PROJECT_VIEWER',
  'PROJECT_DEVELOPER',
  'PROJECT_ADMIN',
];

export function IdeProjectMembers() {
  const {projectId = ''} = useParams();
  const user = useAuthStore((s) => s.user);
  const [members, setMembers] = useState<api.ProjectMemberDto[] | null>(null);
  const [shareLinks, setShareLinks] = useState<api.ShareLinkDto[] | null>(null);
  const [externalSharingEnabled, setExternalSharingEnabled] = useState<boolean | null>(null);
  const [inviteEmail, setInviteEmail] = useState('');
  const [inviteRole, setInviteRole] = useState<api.ProjectInviteRole>('PROJECT_DEVELOPER');
  const [busy, setBusy] = useState('');
  const [err, setErr] = useState('');
  const [msg, setMsg] = useState('');
  const [revokeFor, setRevokeFor] = useState<string | null>(null);
  const [removeFor, setRemoveFor] = useState<string | null>(null);

  function load() {
    api
      .getProjectMembers(projectId)
      .then(setMembers)
      .catch(() => setMembers([]));
    api
      .listShareLinks(projectId)
      .then((r) => setShareLinks(r.links ?? []))
      .catch(() => setShareLinks([]));
  }
  useEffect(() => {
    if (!projectId) return;
    load();
    api
      .getMyOrg()
      .then((o) => setExternalSharingEnabled(!!o.externalSharingEnabled))
      .catch(() => setExternalSharingEnabled(false));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId]);

  const isSuperAdmin = user?.globalRole === 'SUPER_ADMIN';
  const canManage = isSuperAdmin || user?.orgRole === 'ORG_ADMIN';
  const canInviteExternals = isSuperAdmin || externalSharingEnabled === true;

  async function invite() {
    const email = inviteEmail.trim();
    if (!email) return;
    setBusy('invite');
    setErr('');
    setMsg('');
    try {
      await api.createProjectInvite(projectId, {email, role: inviteRole});
      setMsg(`Invite sent to ${email}.`);
      setInviteEmail('');
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Invite failed.');
    } finally {
      setBusy('');
    }
  }

  async function makeShareLink() {
    setBusy('share');
    setErr('');
    setMsg('');
    try {
      await api.createShareLink(projectId, {surface: 'PROJECT_OVERVIEW', expiresInDays: 30});
      setMsg('Share link created.');
      const r = await api.listShareLinks(projectId);
      setShareLinks(r.links ?? []);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Create failed.');
    } finally {
      setBusy('');
    }
  }

  async function revokeLink(linkId: string) {
    try {
      await api.revokeShareLink(projectId, linkId);
      setShareLinks((p) => (p ?? []).filter((l) => l.id !== linkId));
      setMsg('Share link revoked.');
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Revoke failed.');
    }
  }

  async function remove(userId: string) {
    try {
      await api.removeProjectMember(projectId, userId);
      load();
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Remove failed.');
    }
  }

  return (
    <div className='ide-pane'>
      <ProjectTabs projectId={projectId} />
      <div className='ide-h1'>Members &amp; sharing</div>
      <Banner err={err} msg={msg} />

      <div className='ide-h2'>Team · {members?.length ?? 0}</div>
      {members === null ? (
        <IdeLoader label='Loading members…' />
      ) : members.length === 0 ? (
        <div className='ide-empty'>No members.</div>
      ) : (
        <div className='ide-card' style={{padding: 0}}>
          {members.map((m, i) => {
            const isExternal = (m as unknown as {external?: boolean}).external === true;
            const isMe = m.userId === user?.id;
            const role = m.projectRole || 'PROJECT_DEVELOPER';
            return (
              <div
                key={m.userId}
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 10,
                  padding: '11px 16px',
                  borderBottom: i < members.length - 1 ? '1px solid var(--ide-border)' : 'none',
                }}
              >
                <span style={{flex: 1, fontWeight: 500}}>
                  {[m.firstName, m.lastName].filter(Boolean).join(' ') || m.email}
                  {isMe && <span style={{color: 'var(--ide-fg-muted)'}}> · you</span>}
                  {isExternal && (
                    <span
                      style={{
                        display: 'inline-block',
                        marginLeft: 6,
                        padding: '1px 6px',
                        background: 'var(--ide-bg-input)',
                        color: 'var(--ide-blue)',
                        fontSize: 9,
                        fontWeight: 700,
                        letterSpacing: '0.06em',
                        borderRadius: 3,
                      }}
                    >
                      EXT
                    </span>
                  )}
                </span>
                <span style={{fontSize: 12, color: 'var(--ide-fg-muted)'}}>{m.email}</span>
                <IdeBadge kind='ok'>{role}</IdeBadge>
                {canManage && !isMe && (
                  <button
                    className='ide-btn ghost'
                    style={{padding: '3px 8px', color: 'var(--ide-red)'}}
                    onClick={() => setRemoveFor(m.userId)}
                  >
                    Remove
                  </button>
                )}
              </div>
            );
          })}
        </div>
      )}

      {canManage &&
        (canInviteExternals ? (
          <>
            <div className='ide-h2'>Invite teammate</div>
            <div className='ide-card'>
              <div style={{fontSize: 12, color: 'var(--ide-fg-muted)', marginBottom: 10}}>
                Any email works — the recipient gets a magic link.
              </div>
              <input
                className='ide-input'
                type='email'
                placeholder='teammate@company.com'
                value={inviteEmail}
                onChange={(e) => setInviteEmail(e.target.value)}
                style={{marginBottom: 8}}
              />
              <div style={{display: 'flex', gap: 6, marginBottom: 10, flexWrap: 'wrap'}}>
                {INVITE_ROLES.map((r) => (
                  <button
                    key={r}
                    type='button'
                    className={`ide-btn ghost`}
                    style={{
                      padding: '4px 10px',
                      borderColor: r === inviteRole ? 'var(--ide-accent)' : undefined,
                      color: r === inviteRole ? 'var(--ide-accent)' : undefined,
                    }}
                    onClick={() => setInviteRole(r)}
                  >
                    {r.replace('PROJECT_', '').toLowerCase()}
                  </button>
                ))}
              </div>
              <button
                className='ide-btn'
                disabled={busy === 'invite' || !inviteEmail.trim()}
                onClick={invite}
              >
                {busy === 'invite' ? 'Inviting…' : 'Send invite'}
              </button>
            </div>
          </>
        ) : (
          <>
            <div className='ide-h2'>Invite teammate</div>
            <div className='ide-card' style={{borderColor: 'var(--ide-blue)'}}>
              <div
                style={{
                  fontSize: 11,
                  fontWeight: 700,
                  color: 'var(--ide-blue)',
                  letterSpacing: '0.14em',
                  textTransform: 'uppercase',
                  marginBottom: 6,
                }}
              >
                External sharing is off
              </div>
              <div style={{fontSize: 13, lineHeight: 1.55}}>
                Your org admin has not enabled inviting people from outside the org. Enable it in{' '}
                <a href='/org/settings' style={{color: 'var(--ide-accent)'}}>
                  Org settings → External sharing
                </a>
                .
              </div>
            </div>
          </>
        ))}

      <div className='ide-h2'>Share links · {shareLinks?.length ?? 0}</div>
      {shareLinks === null ? (
        <IdeLoader label='Loading share links…' />
      ) : shareLinks.length === 0 ? (
        <div className='ide-empty'>
          No share links yet. Create one to give read-only access without an account.
        </div>
      ) : (
        <div className='ide-card' style={{padding: 0}}>
          {shareLinks.map((s, i) => {
            const url = s.token ? `${window.location.origin}/shared/${s.token}` : '';
            return (
              <div
                key={s.id}
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 10,
                  padding: '11px 16px',
                  borderBottom: i < shareLinks.length - 1 ? '1px solid var(--ide-border)' : 'none',
                }}
              >
                <div style={{flex: 1, minWidth: 0}}>
                  <div style={{fontWeight: 600}}>{s.surface}</div>
                  <div
                    className='ide-mono'
                    style={{
                      fontSize: 11,
                      color: 'var(--ide-fg-muted)',
                      overflow: 'hidden',
                      textOverflow: 'ellipsis',
                      whiteSpace: 'nowrap',
                    }}
                  >
                    {url}
                    {s.expiresAt && ` · expires ${new Date(s.expiresAt).toLocaleDateString()}`}
                  </div>
                </div>
                <button
                  className='ide-btn ghost'
                  style={{padding: '3px 8px'}}
                  onClick={() =>
                    void navigator.clipboard.writeText(url).then(() => setMsg('Link copied.'))
                  }
                >
                  Copy
                </button>
                <button
                  className='ide-btn ghost'
                  style={{padding: '3px 8px', color: 'var(--ide-red)'}}
                  onClick={() => setRevokeFor(s.id)}
                >
                  Revoke
                </button>
              </div>
            );
          })}
        </div>
      )}
      <button
        className='ide-btn ghost'
        style={{marginTop: 10}}
        onClick={makeShareLink}
        disabled={busy === 'share'}
      >
        {busy === 'share' ? 'Creating…' : 'Create project snapshot link'}
      </button>

      <ConfirmModal
        open={!!revokeFor}
        title='Revoke share link'
        body='Revoke this share link?'
        confirmLabel='Revoke'
        danger
        onConfirm={async () => {
          const id = revokeFor;
          if (id) await revokeLink(id);
        }}
        onClose={() => setRevokeFor(null)}
      />
      <ConfirmModal
        open={!!removeFor}
        title='Remove member'
        body='Remove this member from the project?'
        confirmLabel='Remove'
        danger
        onConfirm={async () => {
          const id = removeFor;
          if (id) await remove(id);
        }}
        onClose={() => setRemoveFor(null)}
      />
    </div>
  );
}

// ───────────────────────── Activity ─────────────────────────
export function IdeProjectActivity() {
  const {projectId = ''} = useParams();
  const [feed, setFeed] = useState<Array<Record<string, unknown>> | null>(null);

  useEffect(() => {
    if (!projectId) return;
    api
      .getActivityFeed(projectId, {sinceDays: 30})
      .then((f) =>
        setFeed(
          ((f as unknown as {events?: unknown[]}).events as Array<Record<string, unknown>>) ??
            (Array.isArray(f) ? (f as Array<Record<string, unknown>>) : []),
        ),
      )
      .catch(() => setFeed([]));
  }, [projectId]);

  return (
    <div className='ide-pane'>
      <ProjectTabs projectId={projectId} />
      <div className='ide-h1'>Activity</div>
      {feed === null ? (
        <IdeLoader label='Loading activity…' />
      ) : feed.length === 0 ? (
        <div className='ide-empty'>No recent activity.</div>
      ) : (
        <div className='ide-card' style={{padding: 0}}>
          {feed.map((e, i) => (
            <div
              key={i}
              style={{
                display: 'flex',
                gap: 10,
                padding: '10px 16px',
                borderBottom: i < feed.length - 1 ? '1px solid var(--ide-border)' : 'none',
                fontSize: 13,
              }}
            >
              <span style={{color: 'var(--ide-accent)', fontSize: 11, width: 110, flexShrink: 0}}>
                {String(e.eventType || e.type || 'event')}
              </span>
              <span style={{flex: 1}}>{String(e.summary || e.description || e.message || '')}</span>
              <span style={{color: 'var(--ide-fg-muted)', fontSize: 11}}>
                {String(e.actorName || e.actor || '')}
              </span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

// ───────────────────────── Settings (Settings · Documents (RAG) · Memory) ─────────────────────────
type SettingsSub = 'settings' | 'documents' | 'memory';

export function IdeProjectSettings() {
  const {projectId = ''} = useParams();
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);
  const canEdit = user?.globalRole === 'SUPER_ADMIN' || user?.orgRole === 'ORG_ADMIN';

  const [sub, setSub] = useState<SettingsSub>('settings');
  const [project, setProject] = useState<api.ProjectDto | null>(null);
  const [busy, setBusy] = useState('');
  const [err, setErr] = useState('');
  const [msg, setMsg] = useState('');
  const [deleteDocFor, setDeleteDocFor] = useState<{docId: string; label: string} | null>(null);
  // Danger-zone project action awaiting confirmation.
  const [projectAction, setProjectAction] = useState<'archive' | 'delete' | null>(null);

  // Settings form
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [targetBranch, setTargetBranch] = useState('');
  const [branchNaming, setBranchNaming] = useState('');
  const [githubOwner, setGithubOwner] = useState('');
  const [provider, setProvider] = useState('ANTHROPIC');

  // Documents (RAG)
  const [docs, setDocs] = useState<api.ProjectDocumentRow[] | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);

  // Memory
  const [memory, setMemory] = useState<api.ProjectMemoryResponse | null>(null);

  useEffect(() => {
    if (!projectId) return;
    api
      .getProject(projectId)
      .then((p) => {
        setProject(p);
        setName(p.name || '');
        setDescription(p.description || '');
        setTargetBranch(p.targetBranch || 'main');
        setBranchNaming(p.branchNamingPattern || 'execspec/{slug}-{date}');
        setGithubOwner(p.githubOwner || '');
        if (p.aiProvider) setProvider(p.aiProvider.toUpperCase());
      })
      .catch(() => {});
    refreshDocs();
    api
      .getProjectMemory(projectId)
      .then(setMemory)
      .catch(() => setMemory(null));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId]);

  function refreshDocs() {
    api
      .listProjectDocuments(projectId)
      .then(setDocs)
      .catch(() => setDocs([]));
  }

  async function saveSettings() {
    setBusy('save');
    setErr('');
    setMsg('');
    try {
      // Backend PATCH /projects/{id}/settings (same endpoint updateProjectSettings hits)
      // accepts the extended field set. Raw fetch for the fuller payload — no new api fn.
      const r = await fetch(`/api/projects/${projectId}/settings`, {
        method: 'PATCH',
        headers: {'Content-Type': 'application/json', Authorization: `Bearer ${tok()}`},
        body: JSON.stringify({
          name: name.trim() || undefined,
          description: description.trim() || undefined,
          targetBranch: targetBranch.trim() || undefined,
          branchNamingPattern: branchNaming.trim() || undefined,
          githubOwner: githubOwner.trim() || undefined,
          aiProvider: provider,
        }),
      });
      if (!r.ok) throw new Error(`Save failed (${r.status}).`);
      setMsg('Settings saved.');
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Save failed.');
    } finally {
      setBusy('');
    }
  }

  async function onPickFile(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (!file) return;
    setBusy('upload');
    setErr('');
    setMsg('');
    try {
      await api.uploadProjectDocument(projectId, file);
      setMsg(`Uploaded ${file.name}. Indexing has started.`);
      refreshDocs();
    } catch (e2: unknown) {
      setErr(e2 instanceof Error ? e2.message : 'Upload failed.');
    } finally {
      setBusy('');
      if (fileInput.current) fileInput.current.value = '';
    }
  }

  async function deleteDoc(docId: string) {
    try {
      await api.deleteProjectDocument(projectId, docId);
      refreshDocs();
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Delete failed.');
    }
  }

  async function compactMemory() {
    setBusy('compact');
    setErr('');
    setMsg('');
    try {
      await api.compactProjectMemory(projectId);
      setMemory(await api.getProjectMemory(projectId));
      setMsg('Memory compacted.');
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Compact failed.');
    } finally {
      setBusy('');
    }
  }

  return (
    <div className='ide-pane' style={{maxWidth: 820}}>
      <ProjectTabs projectId={projectId} />
      <div className='ide-h1'>Settings</div>
      {!project ? (
        <IdeLoader label='Loading settings…' />
      ) : (
        <>
          <RTabs
            active={sub}
            onSelect={(k) => setSub(k as SettingsSub)}
            tabs={[
              {key: 'settings', label: 'Settings'},
              {key: 'documents', label: `Documents · ${docs?.length ?? '—'}`},
              {key: 'memory', label: 'Memory'},
            ]}
          />
          <Banner err={err} msg={msg} />

          {/* ── Settings ── */}
          {sub === 'settings' && (
            <>
              <div className='ide-card'>
                <Field label='Project name'>
                  <input
                    className='ide-input'
                    value={name}
                    disabled={!canEdit}
                    onChange={(e) => setName(e.target.value)}
                  />
                </Field>
                <Field label='Description'>
                  <textarea
                    className='ide-textarea'
                    value={description}
                    disabled={!canEdit}
                    rows={3}
                    onChange={(e) => setDescription(e.target.value)}
                  />
                </Field>
                <Field label='Default AI provider'>
                  <select
                    className='ide-select'
                    value={provider}
                    disabled={!canEdit}
                    onChange={(e) => setProvider(e.target.value)}
                  >
                    <option value='ANTHROPIC'>Anthropic</option>
                    <option value='OPENAI'>OpenAI</option>
                    <option value='GEMINI'>Gemini</option>
                  </select>
                </Field>
                <Field label='Target branch'>
                  <input
                    className='ide-input'
                    value={targetBranch}
                    disabled={!canEdit}
                    onChange={(e) => setTargetBranch(e.target.value)}
                  />
                </Field>
                <Field label='Branch naming pattern'>
                  <input
                    className='ide-input'
                    value={branchNaming}
                    disabled={!canEdit}
                    onChange={(e) => setBranchNaming(e.target.value)}
                  />
                  <div style={{fontSize: 11, color: 'var(--ide-fg-muted)', marginTop: 4}}>
                    Variables: {'{slug}'}, {'{date}'}, {'{runId}'}
                  </div>
                </Field>
                <Field label='Default GitHub owner'>
                  <input
                    className='ide-input'
                    value={githubOwner}
                    disabled={!canEdit}
                    placeholder='e.g. acme-co'
                    onChange={(e) => setGithubOwner(e.target.value)}
                  />
                </Field>
                {canEdit && (
                  <button
                    className='ide-btn'
                    style={{marginTop: 6}}
                    disabled={busy === 'save'}
                    onClick={saveSettings}
                  >
                    {busy === 'save' ? 'Saving…' : 'Save settings'}
                  </button>
                )}
              </div>

              <div className='ide-h2'>Metadata</div>
              <div className='ide-card'>
                <div className='ide-kv'>
                  <span>Project ID</span>
                  <span className='ide-mono'>{project.id}</span>
                </div>
                <div className='ide-kv'>
                  <span>Org ID</span>
                  <span className='ide-mono'>{project.orgId || '—'}</span>
                </div>
                <div className='ide-kv'>
                  <span>Status</span>
                  <span>{project.status || '—'}</span>
                </div>
                <div className='ide-kv' style={{borderBottom: 'none'}}>
                  <span>Created</span>
                  <span>
                    {project.createdAt ? new Date(project.createdAt).toLocaleString() : '—'}
                  </span>
                </div>
              </div>

              <div className='ide-h2'>Danger zone</div>
              <div className='ide-card' style={{display: 'flex', gap: 8}}>
                <button className='ide-btn ghost' onClick={() => setProjectAction('archive')}>
                  Archive
                </button>
                <button
                  className='ide-btn ghost'
                  style={{color: 'var(--ide-red)'}}
                  onClick={() => setProjectAction('delete')}
                >
                  Delete
                </button>
              </div>
            </>
          )}

          {/* ── Documents (RAG) ── */}
          {sub === 'documents' && (
            <>
              <div className='ide-card' style={{marginBottom: 12, borderColor: 'var(--ide-blue)'}}>
                <div style={{fontSize: 13, lineHeight: 1.55}}>
                  Upload markdown, PDF, or plain-text docs to give the planner and chat extra
                  context. Documents are chunked, embedded, and retrieved at run time.
                </div>
              </div>
              <input
                ref={fileInput}
                type='file'
                accept='.md,.txt,.pdf,.markdown,.rst'
                style={{display: 'none'}}
                onChange={onPickFile}
              />
              <button
                className='ide-btn'
                style={{marginBottom: 12}}
                disabled={busy === 'upload'}
                onClick={() => fileInput.current?.click()}
              >
                {busy === 'upload' ? 'Uploading…' : 'Upload document'}
              </button>
              {docs === null ? (
                <IdeLoader label='Loading documents…' />
              ) : docs.length === 0 ? (
                <div className='ide-empty'>No documents yet. Upload one above to start.</div>
              ) : (
                <div className='ide-card' style={{padding: 0}}>
                  {docs.map((d, i) => {
                    const status = (d.status || 'READY').toUpperCase();
                    const sizeKb = d.sizeBytes ? `${(d.sizeBytes / 1024).toFixed(1)} KB` : '—';
                    const label = d.filename || d.pathRelative || `Document ${d.id.slice(0, 6)}`;
                    return (
                      <div
                        key={d.id}
                        style={{
                          display: 'flex',
                          alignItems: 'center',
                          gap: 10,
                          padding: '11px 16px',
                          borderBottom:
                            i < docs.length - 1 ? '1px solid var(--ide-border)' : 'none',
                        }}
                      >
                        <div style={{flex: 1, minWidth: 0}}>
                          <div
                            style={{
                              fontWeight: 500,
                              overflow: 'hidden',
                              textOverflow: 'ellipsis',
                              whiteSpace: 'nowrap',
                            }}
                          >
                            {label}
                          </div>
                          <div style={{fontSize: 11, color: 'var(--ide-fg-muted)'}}>
                            {sizeKb}
                            {d.mimeType ? ` · ${d.mimeType}` : ''}
                          </div>
                        </div>
                        <IdeBadge
                          kind={status === 'READY' ? 'ok' : status === 'FAILED' ? 'bad' : 'warn'}
                        >
                          {status}
                        </IdeBadge>
                        <button
                          className='ide-btn ghost'
                          style={{padding: '3px 8px', color: 'var(--ide-red)'}}
                          onClick={() => setDeleteDocFor({docId: d.id, label})}
                        >
                          ×
                        </button>
                      </div>
                    );
                  })}
                </div>
              )}
            </>
          )}

          {/* ── Memory ── */}
          {sub === 'memory' && (
            <>
              <div className='ide-card'>
                <div className='ide-h2' style={{marginTop: 0}}>
                  Project memory
                </div>
                <div className='ide-kv'>
                  <span>Total runs in ledger</span>
                  <span>{memory?.stats?.totalRuns ?? '—'}</span>
                </div>
                <div className='ide-kv'>
                  <span>Snapshot version</span>
                  <span>{memory?.stats?.snapshotVersion ?? '—'}</span>
                </div>
                <div className='ide-kv'>
                  <span>Recent entries</span>
                  <span>{memory?.stats?.recentEntryCount ?? '—'}</span>
                </div>
                <div className='ide-kv' style={{borderBottom: 'none'}}>
                  <span>Tokens / budget</span>
                  <span>
                    {memory?.stats
                      ? `${memory.stats.currentTokens} / ${memory.stats.tokenBudget}`
                      : '—'}
                  </span>
                </div>
                <button
                  className='ide-btn ghost'
                  style={{marginTop: 12}}
                  disabled={busy === 'compact'}
                  onClick={compactMemory}
                >
                  {busy === 'compact' ? 'Compacting…' : 'Compact memory'}
                </button>
              </div>

              <div className='ide-h2'>What the planner remembers</div>
              <div className='ide-card'>
                <div style={{fontSize: 13, lineHeight: 1.55, whiteSpace: 'pre-wrap'}}>
                  {memory?.fullContext?.trim() ||
                    'No memory snapshot yet. The planner builds context after the first ~5 completed runs.'}
                </div>
              </div>
            </>
          )}
        </>
      )}

      <ConfirmModal
        open={!!deleteDocFor}
        title='Delete document'
        body={deleteDocFor ? `Delete "${deleteDocFor.label}" from the RAG index?` : ''}
        confirmLabel='Delete'
        danger
        onConfirm={async () => {
          const d = deleteDocFor;
          if (d) await deleteDoc(d.docId);
        }}
        onClose={() => setDeleteDocFor(null)}
      />
      <ConfirmModal
        open={projectAction === 'archive'}
        title='Archive project'
        body='Archive this project?'
        confirmLabel='Archive'
        onConfirm={() => {
          setProjectAction(null);
          api
            .archiveProject(projectId)
            .then(() => navigate('/projects'))
            .catch((e) => setErr(e instanceof Error ? e.message : 'Archive failed.'));
        }}
        onClose={() => setProjectAction(null)}
      />
      <ConfirmModal
        open={projectAction === 'delete'}
        title='Delete project'
        body='Delete this project? This cannot be undone.'
        confirmLabel='Delete'
        danger
        onConfirm={() => {
          setProjectAction(null);
          api
            .deleteProject(projectId)
            .then(() => navigate('/projects'))
            .catch((e) => setErr(e instanceof Error ? e.message : 'Delete failed.'));
        }}
        onClose={() => setProjectAction(null)}
      />
    </div>
  );
}

function Field({label, children}: {label: string; children: React.ReactNode}) {
  return (
    <div style={{marginBottom: 14}}>
      <div
        style={{
          fontSize: 11,
          fontWeight: 700,
          letterSpacing: '0.14em',
          textTransform: 'uppercase',
          color: 'var(--ide-fg-muted)',
          marginBottom: 6,
        }}
      >
        {label}
      </div>
      {children}
    </div>
  );
}
