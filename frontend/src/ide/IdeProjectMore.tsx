import {useEffect, useMemo, useRef, useState} from 'react';
import {Link, useNavigate, useParams} from 'react-router-dom';
import * as api from '@/lib/api';
import type {ArchitectureDoc} from '@/types/project';
import {IdeBadge, IdeCodeViewer, IdeFileTree, IdeLoader} from '@/ide/IdeKit';
import {ProjectTabs} from '@/ide/IdeProjectTabs';
import {repoDisplayName, repoIdFor} from '@/signal/lib/runUtils';
import {CodeReviewPanel} from '@/signal/components/CodeReviewPanel';
import {ConfirmModal, PromptModal} from '@/signal/components/Modal';
import {useAuthStore} from '@/stores/useAuthStore';
import {uiSlashSuggestions, uiSlashUsage} from '@/lib/uiSlash';
import {selectScopedAgentSession, webAgentSessionRequest} from '@/ide/agentSession';
import {streamWebAgentChatReply} from '@/ide/agentChatStream';
import {ArchDocView} from '@/ide/projectArchitecturePanels';
import {
  agentTurnToChatBubbles,
  sortAgentChatBubbles,
  type AgentChatBubble,
} from '@/ide/agentChatHistory';
import {
  contractDetectorSummary,
  docStamp,
  filterProjectFiles,
  invariantViolationCounts,
  looksLikeArtifact,
  techDebtCategories,
} from '@/ide/projectMoreUtils';
import {
  formatProjectList,
  formatProjectRunList,
  resolveProjectChatSlashAction,
} from '@/ide/projectChatUtils';
import {
  ProjectBanner as Banner,
  ProjectChatMessageCard as ChatMessageCard,
  ProjectMetaLabel as MetaLabel,
  ProjectRouteTabs as RTabs,
} from '@/ide/projectUi';

type ChatBubble = AgentChatBubble;
type ChatPanel = 'chat' | 'artifacts' | 'memory';

export function IdeProjectChat() {
  const {projectId = ''} = useParams();
  const navigate = useNavigate();
  const [msgs, setMsgs] = useState<ChatBubble[] | null>(null);
  const [agentSessionId, setAgentSessionId] = useState<string | null>(null);
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
  const [mobilePanel, setMobilePanel] = useState<ChatPanel>('chat');
  const [mobileActionsOpen, setMobileActionsOpen] = useState(false);
  const endRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!projectId) return;
    let cancelled = false;
    let memTimer: number | undefined;
    async function loadAgentTurns() {
      const sessions = await api.listAgentSessions(100);
      const session = selectScopedAgentSession(sessions, {projectId});
      if (!session) return [] as ChatBubble[];
      if (!cancelled) setAgentSessionId(session.id);
      const turns = await api.listAgentTurns(session.id, {limit: 50});
      return turns.flatMap(agentTurnToChatBubbles);
    }
    api
      .getProjectChatInfo(projectId)
      .then(() => {
        if (!cancelled) setGated(false);
      })
      .catch((e: unknown) => {
        const m = e instanceof Error ? e.message : '';
        if (!cancelled && /403|forbidden|team or enterprise/i.test(m)) setGated(true);
      });
    Promise.all([api.getProjectChatHistory(projectId), loadAgentTurns().catch(() => [])])
      .then(([rows, agentRows]) => {
        if (cancelled) return;
        const legacyRows = rows.map((r) => ({
          role: r.role === 'USER' ? 'user' : 'assistant',
          content: r.content,
          ts: r.createdAt,
        })) satisfies ChatBubble[];
        setMsgs([...legacyRows, ...agentRows].sort(sortAgentChatBubbles));
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
    try {
      if (await handleSlashCommand(m)) return;
      setMsgs((prev) => [
        ...(prev ?? []),
        {role: 'user', content: m, ts: new Date().toISOString()},
      ]);
      const session = await ensureProjectAgentSession();
      const reply = await streamWebAgentChatReply(session.id, m, {
        label: 'project agent',
        href: `/projects/${projectId}/agent`,
      });
      setMsgs((prev) => [
        ...(prev ?? []),
        {
          role: 'assistant',
          content: reply,
          ts: new Date().toISOString(),
        },
      ]);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Chat failed.');
    } finally {
      setBusy(false);
    }
  }

  async function ensureProjectAgentSession(): Promise<api.AgentSessionResponse> {
    if (agentSessionId) {
      try {
        return await api.getAgentSession(agentSessionId);
      } catch {
        setAgentSessionId(null);
      }
    }
    const sessions = await api.listAgentSessions(100);
    const existing = selectScopedAgentSession(sessions, {projectId});
    if (existing) {
      setAgentSessionId(existing.id);
      return existing;
    }
    const created = await api.createAgentSession(webAgentSessionRequest({projectId}));
    setAgentSessionId(created.id);
    return created;
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
      const created = await api.createAgentSession(webAgentSessionRequest({projectId}));
      setAgentSessionId(created.id);
      setMsgs([]);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Clear failed.');
    }
  }

  const memDigest = memory?.fullContext?.trim() || '';
  const memRuns = memory?.stats?.totalRuns ?? 0;
  const artifactMessages = (msgs ?? []).filter(
    (m) => m.role === 'assistant' && looksLikeArtifact(m.content),
  );
  const slashSuggestions = uiSlashSuggestions(text, 'project');

  function note(content: string) {
    setMsgs((prev) => [
      ...(prev ?? []),
      {role: 'assistant', content, ts: new Date().toISOString()},
    ]);
  }

  async function handleSlashCommand(raw: string): Promise<boolean> {
    const action = resolveProjectChatSlashAction(raw, {projectId, memRuns, memDigest});
    switch (action.kind) {
      case 'none':
        return false;
      case 'note':
        note(action.content);
        return true;
      case 'navigate':
        navigate(action.to);
        return true;
      case 'clear':
        await clearAll();
        return true;
      case 'compile':
        await compile();
        return true;
      case 'compact':
        await compact();
        return true;
      case 'memory':
        setMobilePanel('memory');
        setMemOpen(true);
        note(action.content);
        return true;
      case 'list-projects': {
        const projects = await api.getProjects();
        note(formatProjectList(projects));
        return true;
      }
      case 'list-runs': {
        const runs = await api.getRuns({scope: 'mine', projectId, size: 8});
        note(formatProjectRunList(runs.content));
        return true;
      }
      case 'provider': {
        const saved = await api.updateProviderPreference(action.provider);
        note(`Provider preference saved: ${saved.provider}`);
        return true;
      }
      case 'provider-usage':
        note(action.content);
        return true;
      default:
        return false;
    }
  }

  return (
    <div
      className='ide-pane'
      style={{display: 'flex', flexDirection: 'column', height: '100%', maxWidth: 'none'}}
    >
      <ProjectTabs projectId={projectId} />
      <div className='ide-project-chat-head'>
        <div className='ide-project-chat-title'>
          <h1 className='ide-h1' style={{margin: 0}}>
            Ask about project
          </h1>
          <span style={{fontSize: 12, color: 'var(--ide-fg-muted)'}}>
            context: {memRuns} completed work item{memRuns === 1 ? '' : 's'}
          </span>
        </div>
        <button
          type='button'
          className='ide-project-chat-mobile-actions'
          aria-expanded={mobileActionsOpen}
          onClick={() => setMobileActionsOpen((v) => !v)}
        >
          Actions
        </button>
        <div className={`ide-project-chat-actions${mobileActionsOpen ? ' open' : ''}`}>
          <button
            className='ide-btn ghost'
            onClick={compile}
            disabled={busy || gated}
            title='Turns this conversation into a ready-to-review work request'
          >
            Create work from chat
          </button>
          <button
            className='ide-btn ghost'
            onClick={compact}
            disabled={gated}
            title='Summarize older messages to free up the chat token budget (keeps recent messages verbatim)'
          >
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
      </div>

      <div className='ide-project-chat-segments' role='tablist' aria-label='Project chat panels'>
        {[
          ['chat', 'Chat'],
          ['artifacts', `Artifacts · ${artifactMessages.length}`],
          ['memory', `Context · ${memRuns}`],
        ].map(([key, label]) => (
          <button
            key={key}
            type='button'
            className={mobilePanel === key ? 'active' : ''}
            onClick={() => setMobilePanel(key as ChatPanel)}
          >
            {label}
          </button>
        ))}
      </div>

      <div className='ide-project-chat-shell' data-mobile-panel={mobilePanel}>
        <section className='ide-project-chat-main'>
          <Banner err={err} />
          <div className='ide-project-chat-scroll'>
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
                  The agent uses project context, code maps, and linked docs to answer questions.
                  This is gated to TEAM and ENTERPRISE plans.{' '}
                  <a href='/plans' style={{color: 'var(--ide-accent)'}}>
                    Compare plans →
                  </a>
                </div>
              </div>
            ) : msgs === null ? (
              <IdeLoader label='Loading chat…' />
            ) : msgs.length === 0 && !busy ? (
              <div className='ide-card ide-project-chat-empty'>
                <div className='ide-project-chat-empty-title'>Ask about this project</div>
                {[
                  'Summarize the current system map.',
                  'What should I review before the next work item?',
                  'Find risky files or unresolved code issues.',
                  'Turn this discussion into a ready work request.',
                ].map((prompt) => (
                  <button key={prompt} type='button' onClick={() => setText(prompt)}>
                    {prompt}
                  </button>
                ))}
              </div>
            ) : (
              msgs.map((m, i) => <ChatMessageCard key={i} message={m} />)
            )}
            {busy && (
              <div style={{fontSize: 13, color: 'var(--ide-fg-muted)', padding: '4px 0'}}>
                Thinking…
              </div>
            )}
            <div ref={endRef} />
          </div>

          <div className='ide-project-chat-composer'>
            <div style={{display: 'grid', gap: 6, flex: 1}}>
              <textarea
                className='ide-input'
                rows={2}
                placeholder={
                  gated
                    ? 'Upgrade to TEAM to enable project chat.'
                    : 'Ask about the codebase…  (Enter to send · Shift+Enter for a new line)'
                }
                value={text}
                disabled={gated}
                onChange={(e) => setText(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && !e.shiftKey) {
                    e.preventDefault();
                    void send();
                  }
                }}
                style={{resize: 'vertical', minHeight: 40, maxHeight: 160, fontFamily: 'inherit'}}
              />
              {slashSuggestions.length > 0 && (
                <div className='ide-card' style={{padding: 8, display: 'grid', gap: 4}}>
                  {slashSuggestions.map((s) => (
                    <button
                      key={s.command}
                      type='button'
                      onClick={() => setText(s.args ? `${s.command} ` : s.command)}
                      style={{
                        border: 0,
                        background: 'transparent',
                        color: 'var(--ide-fg)',
                        display: 'flex',
                        justifyContent: 'space-between',
                        gap: 12,
                        textAlign: 'left',
                        cursor: 'pointer',
                      }}
                    >
                      <span className='ide-mono'>{uiSlashUsage(s)}</span>
                      <span style={{color: 'var(--ide-fg-muted)'}}>{s.description}</span>
                    </button>
                  ))}
                </div>
              )}
            </div>
            <button className='ide-btn' onClick={send} disabled={busy || gated}>
              {busy ? '…' : 'Send'}
            </button>
          </div>
        </section>

        <aside className='ide-project-chat-rail'>
          <div className='ide-card ide-project-chat-memory-card'>
            <button
              type='button'
              onClick={() => setMemOpen((v) => !v)}
              aria-expanded={memOpen}
              title={memOpen ? 'Hide memory digest' : 'Show memory digest'}
              className='ide-project-chat-rail-head'
              style={{minHeight: 32}}
            >
              Project context · {memRuns} item{memRuns === 1 ? '' : 's'}
              <span>{memOpen ? 'hide' : 'show'}</span>
            </button>
            {memDigest ? (
              <div className='ide-project-chat-rail-body'>
                {(memOpen ? memDigest : memDigest.slice(0, 420)).trim()}
                {!memOpen && memDigest.length > 420 ? '…' : ''}
              </div>
            ) : (
              <div className='ide-project-chat-rail-body muted'>
                No compacted project context yet. Completed work will build this context.
              </div>
            )}
          </div>

          <div className='ide-card ide-project-chat-artifacts-card'>
            <div className='ide-project-chat-rail-head static'>
              Artifacts
              <span>{artifactMessages.length}</span>
            </div>
            {artifactMessages.length === 0 ? (
              <div className='ide-project-chat-rail-body muted'>
                Long generated answers and code-heavy outputs will appear here.
              </div>
            ) : (
              artifactMessages.map((m, i) => (
                <div key={i} className='ide-project-chat-artifact-row'>
                  <div>Assistant artifact {i + 1}</div>
                  <button
                    type='button'
                    onClick={() => void navigator.clipboard.writeText(m.content)}
                  >
                    Copy
                  </button>
                </div>
              ))
            )}
          </div>
        </aside>
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

// Code (Files · Issues · Review · Repo health · Standards · Generated docs)
type CodeSub = 'browse' | 'debt' | 'review' | 'health' | 'standards' | 'docs';

export function IdeProjectCode() {
  const {projectId = ''} = useParams();
  const user = useAuthStore((s) => s.user);
  const [sub, setSub] = useState<CodeSub>('browse');
  const [repos, setRepos] = useState<Array<Record<string, unknown>>>([]);
  const [busy, setBusy] = useState('');
  const [err, setErr] = useState('');
  const [msg, setMsg] = useState('');
  // C8 — tech-debt autofix dispatch awaiting confirmation.
  const [fixFor, setFixFor] = useState<{category: string; count: number} | null>(null);
  // §3.4 Docs (P1) — doc generation in flight; drives the 3s poll below.
  const [genPending, setGenPending] = useState<{
    type: api.GeneratedDocType;
    baseline: string;
  } | null>(null);

  // Browse state
  const [repoId, setRepoId] = useState('');
  const [files, setFiles] = useState<string[] | null>(null);
  const [active, setActive] = useState<string | null>(null);
  const [content, setContent] = useState<Record<string, string>>({});
  // Mobile tree search query. The mobile view still renders the real folder
  // tree so file/folder icons and hierarchy are preserved.
  const [fileQuery, setFileQuery] = useState('');
  const [mobileCodeMode, setMobileCodeMode] = useState<'tree' | 'file'>('tree');

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
    setMobileCodeMode('file');
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
      setMsg(res.runId ? `Fix work started: R-${res.runId.slice(0, 6)}` : 'Fix work started.');
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Fix work failed.');
    } finally {
      setBusy('');
    }
  }

  async function genDoc(t: api.GeneratedDocType) {
    setBusy(`gen-${t}`);
    setErr('');
    setMsg('');
    try {
      // Stamp the current row BEFORE dispatch so the poll below can detect the
      // fresh generation landing (the row has no status field).
      const baseline = docStamp((docs || []).find((d) => d.docType === t));
      await api.generateProjectDoc(projectId, t);
      setMsg(`${t.toLowerCase()} doc regeneration started.`);
      setGenPending({type: t, baseline});
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

  // §3.4 Docs (P1) — generation is async and previously never polled, so
  // "regeneration started" could sit forever. Poll the doc every 3s (max 60)
  // until a fresh version lands; skipped while the tab is hidden, cleared on
  // terminal/unmount.
  useEffect(() => {
    if (!genPending || !projectId) return;
    let polls = 0;
    const id = window.setInterval(() => {
      if (document.hidden) return;
      polls += 1;
      if (polls > 60) {
        setGenPending(null);
        setMsg('Doc generation is taking longer than expected — check back shortly.');
        return;
      }
      void api
        .getProjectDoc(projectId, genPending.type)
        .then((row) => {
          if (row && docStamp(row) !== genPending.baseline) {
            setDocs((prev) => [...(prev ?? []).filter((d) => d.docType !== genPending.type), row]);
            setGenPending(null);
            setMsg(`${genPending.type} doc generated.`);
          }
        })
        .catch(() => {
          // Transient poll failure — keep polling until the cap.
        });
    }, 3000);
    return () => window.clearInterval(id);
  }, [genPending, projectId]);

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
  const debtCats = useMemo(() => techDebtCategories(debt), [debt]);

  // Code-review target — the repo currently chosen in the shared header
  // <select> (falls back to the first linked repo). The full review surface
  // (scan / severity-grouped issues / autofix / report) is per-repo.
  const reviewRepoId = repoId || (repos[0] ? repoIdFor(repos[0]) : '');
  const reviewRepo = useMemo(
    () => repos.find((r) => repoIdFor(r) === reviewRepoId) ?? null,
    [repos, reviewRepoId],
  );

  const browseIsSplit = sub === 'browse';
  const mobileTreeFiles = useMemo(() => filterProjectFiles(files, fileQuery), [files, fileQuery]);

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
        <div
          className='ide-code-head'
          style={{display: 'flex', alignItems: 'center', gap: 10, marginBottom: 8}}
        >
          <div>
            <h1 className='ide-h1' style={{margin: 0}}>
              Codebase
            </h1>
            <div className='ide-sub' style={{margin: 0}}>
              Browse linked repo files, review code issues, and regenerate project docs.
            </div>
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
            {key: 'browse', label: files == null ? 'Files' : `Files · ${files.length}`},
            {key: 'debt', label: `Issues · ${debtCats.length}`},
            {key: 'review', label: 'Review'},
            {key: 'health', label: 'Repo health'},
            {key: 'standards', label: 'Standards'},
            {key: 'docs', label: `Generated docs · ${docs?.length ?? 0}`},
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
                {files?.length ? (
                  <div className='ide-mobile-file-tree' data-mode={mobileCodeMode}>
                    <div className='ide-mobile-code-switch'>
                      <button
                        type='button'
                        className={mobileCodeMode === 'tree' ? 'active' : ''}
                        onClick={() => setMobileCodeMode('tree')}
                      >
                        Tree
                      </button>
                      <button
                        type='button'
                        className={mobileCodeMode === 'file' ? 'active' : ''}
                        onClick={() => setMobileCodeMode('file')}
                        disabled={!active}
                      >
                        File
                      </button>
                    </div>
                    <input
                      className='ide-input'
                      type='search'
                      placeholder='Search files and folders…'
                      value={fileQuery}
                      onChange={(e) => setFileQuery(e.target.value)}
                    />
                    <div className='ide-mobile-file-tree-list'>
                      {mobileTreeFiles.length === 0 ? (
                        <div
                          style={{
                            fontSize: 12,
                            color: 'var(--ide-fg-muted)',
                            padding: '6px 2px',
                          }}
                        >
                          No files match “{fileQuery.trim()}”.
                        </div>
                      ) : (
                        <IdeFileTree files={mobileTreeFiles} activePath={active} onOpen={open} />
                      )}
                    </div>
                    {active && (
                      <div className='ide-mobile-active-file ide-mono' title={active}>
                        {active}
                      </div>
                    )}
                  </div>
                ) : null}
                {active ? (
                  <div className='ide-mobile-file-view'>
                    <button
                      type='button'
                      className='ide-btn ghost ide-mobile-file-back'
                      onClick={() => setMobileCodeMode('tree')}
                    >
                      ← File tree
                    </button>
                    <IdeCodeViewer filename={active} content={content[active] ?? '…'} />
                  </div>
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
            <IdeLoader label='Loading code issues…' />
          ) : debtCats.length === 0 ? (
            <div className='ide-empty'>
              No code issues found yet. Start a review scan to surface risks.
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
                    // C8 — credit-spending dispatch goes through a confirm modal.
                    onClick={() => setFixFor({category: c.name, count: c.count})}
                  >
                    {busy === `fix-${c.name}` ? 'Starting…' : `Start fix work · ${c.count}`}
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
            <div className='ide-empty'>No repos linked — link one to review code.</div>
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
                    {drift && (
                      // §3.4 — codemap-stale recovery CTA: Sync/Regenerate live on Repos.
                      <Link
                        to={`/projects/${projectId}/repos`}
                        className='ide-btn ghost'
                        style={{padding: '3px 10px', fontSize: 12, textDecoration: 'none'}}
                        title='Open Repos to sync metadata or refresh the system map'
                      >
                        Sync / Refresh →
                      </Link>
                    )}
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
                      <MetaLabel>System map</MetaLabel>
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
                      <span title='BLOCKING = fails the work item · WARNING = reported · INFO = advisory'>
                        <IdeBadge
                          kind={sev === 'BLOCKING' ? 'bad' : sev === 'WARNING' ? 'warn' : 'ok'}
                        >
                          {sev}
                        </IdeBadge>
                      </span>
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
            <div className='ide-empty'>No generated docs yet. Use the buttons below.</div>
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
                  disabled={busy === `gen-${d.docType}` || genPending?.type === d.docType}
                  onClick={() => genDoc(d.docType)}
                >
                  {genPending?.type === d.docType ? 'Generating…' : 'Regenerate'}
                </button>
              </div>
            ))
          )}
          <div style={{display: 'flex', gap: 8, marginTop: 8}}>
            <button
              className='ide-btn ghost'
              disabled={busy === 'gen-EXPLANATION' || genPending?.type === 'EXPLANATION'}
              onClick={() => genDoc('EXPLANATION')}
            >
              {busy === 'gen-EXPLANATION' || genPending?.type === 'EXPLANATION'
                ? 'Generating…'
                : 'Generate explanation doc'}
            </button>
            <button
              className='ide-btn ghost'
              disabled={busy === 'gen-SETUP' || genPending?.type === 'SETUP'}
              onClick={() => genDoc('SETUP')}
            >
              {busy === 'gen-SETUP' || genPending?.type === 'SETUP'
                ? 'Generating…'
                : 'Generate setup doc'}
            </button>
          </div>
        </div>
      )}

      {/* C8 — confirm before the paid tech-debt autofix dispatch; the existing
          success message + run id inside autofixCategory() is untouched. */}
      <ConfirmModal
        open={!!fixFor}
        title='Start fix work'
        body={
          fixFor
            ? `This starts new work targeting '${fixFor.category}' issues (${fixFor.count} finding${fixFor.count === 1 ? '' : 's'}). It consumes credits and opens a pull request. Continue?`
            : ''
        }
        confirmLabel='Start fix work'
        busy={!!fixFor && busy === `fix-${fixFor.category}`}
        onConfirm={async () => {
          const f = fixFor;
          if (f) await autofixCategory(f.category);
        }}
        onClose={() => setFixFor(null)}
      />
    </div>
  );
}

// System map (Current map · History · Rules · Decisions · Contracts)
type ArchSub = 'current' | 'versions' | 'invariants' | 'adrs' | 'contracts';

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
      setMsg('System map refresh started.');
      refresh();
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Refresh failed.');
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

  const violByInv = useMemo(() => invariantViolationCounts(violations), [violations]);

  const docMd = arch?.content || '';

  return (
    <div className='ide-pane'>
      <ProjectTabs projectId={projectId} />
      <div style={{display: 'flex', alignItems: 'center', gap: 10}}>
        <h1 className='ide-h1' style={{margin: 0}}>
          System map
        </h1>
        {arch?.version != null && (
          <span style={{fontSize: 12, color: 'var(--ide-fg-muted)'}}>v{arch.version}</span>
        )}
        <span style={{flex: 1}} />
        <button className='ide-btn ghost' onClick={regenerate} disabled={busy === 'regen'}>
          {busy === 'regen' ? 'Refreshing…' : 'Refresh map'}
        </button>
      </div>
      <div style={{marginTop: 12}}>
        <RTabs
          active={sub}
          onSelect={(k) => setSub(k as ArchSub)}
          tabs={[
            {key: 'current', label: 'Current map'},
            {key: 'versions', label: `History · ${versions?.length ?? 0}`},
            {key: 'invariants', label: `Rules · ${invariants?.length ?? 0}`},
            {key: 'adrs', label: `Decisions · ${adrs?.length ?? 0}`},
            {key: 'contracts', label: 'Contracts'},
          ]}
        />
      </div>
      <Banner err={err} msg={msg} />

      {/* ── Current ── */}
      {sub === 'current' &&
        (arch === undefined ? (
          <IdeLoader label='Loading system map…' />
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
                    System map
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
                // C9 — cap the scroll container so it can't swallow a phone
                // viewport (70vh of one card at 375×812).
                <div style={{maxHeight: 'min(70vh, 520px)', overflow: 'auto'}}>
                  <ArchDocView key={`v${arch?.version ?? 0}`} md={docMd} />
                </div>
              ) : (
                <div className='ide-empty' style={{padding: '12px 0'}}>
                  No system map yet — refresh it to build one from your repos.
                </div>
              )}
            </div>
            <button
              className='ide-btn ghost'
              style={{marginTop: 12}}
              onClick={regenerate}
              disabled={busy === 'regen'}
            >
              {busy === 'regen' ? 'Refreshing…' : 'Refresh system map'}
            </button>
          </>
        ))}

      {/* ── Versions ── */}
      {sub === 'versions' &&
        (versions === null ? (
          <IdeLoader label='Loading history…' />
        ) : versions.length === 0 ? (
          <div className='ide-empty'>No previous map versions yet.</div>
        ) : (
          <>
            <div className='ide-card' style={{marginBottom: 12}}>
              <div className='ide-h2' style={{marginTop: 0}}>
                Compare history
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
          <IdeLoader label='Loading rules…' />
        ) : invariants.length === 0 ? (
          <div className='ide-empty'>No rules declared yet.</div>
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

      {/* ── Decisions ── */}
      {sub === 'adrs' &&
        (adrs === null ? (
          <IdeLoader label='Loading decisions…' />
        ) : adrs.length === 0 ? (
          <div className='ide-empty'>No architecture decisions yet.</div>
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
              const detectorSummary = contractDetectorSummary(detectors);
              const driftIds = detectorSummary.driftCandidateIds.slice(0, 1);
              return (
                <div key={repoId} className='ide-card' style={{marginBottom: 8}}>
                  <div style={{display: 'flex', alignItems: 'center', gap: 10, marginBottom: 8}}>
                    <div style={{flex: 1, minWidth: 0}}>
                      <div style={{fontWeight: 600, color: 'var(--ide-fg-bright)'}}>{repoName}</div>
                      <div style={{fontSize: 12, color: 'var(--ide-fg-muted)'}}>
                        {cov == null
                          ? 'No coverage data — start a scan'
                          : `${detectorSummary.scanned} detector${detectorSummary.scanned === 1 ? '' : 's'} scanned${detectorSummary.failed > 0 ? ` · ${detectorSummary.failed} failed` : ''} · ${detectorSummary.totalContracts} contract${detectorSummary.totalContracts === 1 ? '' : 's'}`}
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
        body='Why dismiss this drift? e.g. "intentional API expansion", "interface being deprecated". Minimum 10 characters.'
        fields={[
          {
            name: 'rationale',
            label: 'Why dismiss this drift?',
            placeholder: 'e.g. intentional API expansion (minimum 10 characters)',
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
