import {useEffect, useRef, useState} from 'react';
import {useNavigate, useParams} from 'react-router-dom';
import * as api from '@/lib/api';
import {IdeBadge, IdeCodeViewer, IdeFileTree, IdeLoader} from '@/ide/IdeKit';
import {ProjectTabs} from '@/ide/IdeProjectTabs';
import {repoDisplayName, repoIdFor} from '@/signal/lib/runUtils';
import {Markdown} from '@/signal/components/Markdown';

const tok = () => localStorage.getItem('access_token') ?? '';

// ───────────────────────── Chat ─────────────────────────
export function IdeProjectChat() {
  const {projectId = ''} = useParams();
  const [msgs, setMsgs] = useState<api.ProjectChatMessageDto[] | null>(null);
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  const [spec, setSpec] = useState('');
  const endRef = useRef<HTMLDivElement>(null);

  const load = () =>
    api
      .getProjectChatHistory(projectId)
      .then(setMsgs)
      .catch(() => setMsgs([]));
  useEffect(() => {
    if (projectId) load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId]);
  useEffect(() => endRef.current?.scrollIntoView({behavior: 'smooth'}), [msgs]);

  async function send() {
    const m = text.trim();
    if (!m || busy) return;
    setBusy(true);
    setText('');
    try {
      await fetch(`/api/projects/${projectId}/chat`, {
        method: 'POST',
        headers: {'Content-Type': 'application/json', Authorization: `Bearer ${tok()}`},
        body: JSON.stringify({message: m}),
      });
      await load();
    } finally {
      setBusy(false);
    }
  }
  async function compile() {
    try {
      const r = await api.compileProjectChatToSpec(projectId);
      setSpec(r.spec || '');
    } catch {
      /* ignore */
    }
  }

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
        <span style={{flex: 1}} />
        <button className='ide-btn ghost' onClick={compile}>
          ⌁ Compile to spec
        </button>
      </div>
      {spec && (
        <div
          className='ide-card ide-mono'
          style={{whiteSpace: 'pre-wrap', fontSize: 12, margin: '10px 0'}}
        >
          {spec}
        </div>
      )}
      <div style={{flex: 1, overflowY: 'auto', minHeight: 0, margin: '12px 0'}}>
        {msgs === null ? (
          <IdeLoader label='Loading chat…' />
        ) : msgs.length === 0 ? (
          <div className='ide-empty'>No messages yet — ask about your codebase.</div>
        ) : (
          msgs.map((m) => (
            <div
              key={m.id}
              className='ide-card'
              style={{
                marginBottom: 8,
                borderColor: m.role === 'USER' ? 'var(--ide-accent)' : undefined,
              }}
            >
              <div style={{fontSize: 11, color: 'var(--ide-fg-muted)', marginBottom: 4}}>
                {m.role === 'USER' ? 'You' : 'Assistant'}
              </div>
              <div className='signal-md' style={{fontSize: 13.5}}>
                <Markdown>{m.content}</Markdown>
              </div>
            </div>
          ))
        )}
        <div ref={endRef} />
      </div>
      <div style={{display: 'flex', gap: 8}}>
        <input
          className='ide-input'
          placeholder='Ask about the codebase…'
          value={text}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && send()}
        />
        <button className='ide-btn' onClick={send} disabled={busy}>
          {busy ? '…' : 'Send'}
        </button>
      </div>
    </div>
  );
}

// ───────────────────────── Code (browse live source) ─────────────────────────
export function IdeProjectCode() {
  const {projectId = ''} = useParams();
  const [repos, setRepos] = useState<Array<Record<string, unknown>>>([]);
  const [repoId, setRepoId] = useState('');
  const [files, setFiles] = useState<string[] | null>(null);
  const [active, setActive] = useState<string | null>(null);
  const [content, setContent] = useState<Record<string, string>>({});

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
  }, [projectId]);

  useEffect(() => {
    if (!repoId) return;
    setFiles(null);
    setActive(null);
    api
      .listRepoFiles(projectId, repoId)
      .then((r) => setFiles(r.files ?? []))
      .catch(() => setFiles([]));
  }, [projectId, repoId]);

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

  return (
    <div
      className='ide-pane'
      style={{
        display: 'flex',
        flexDirection: 'column',
        height: '100%',
        maxWidth: 'none',
        padding: 0,
      }}
    >
      <div style={{padding: '16px 16px 0'}}>
        <ProjectTabs projectId={projectId} />
        <div style={{display: 'flex', alignItems: 'center', gap: 10, marginBottom: 8}}>
          <div className='ide-h1' style={{margin: 0}}>
            Code
          </div>
          {repos.length > 0 && (
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
      </div>
      {repos.length === 0 ? (
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
      )}
    </div>
  );
}

// ───────────────────────── Architecture (doc) ─────────────────────────
export function IdeProjectArchitecture() {
  const {projectId = ''} = useParams();
  const [doc, setDoc] = useState<Record<string, unknown> | null | undefined>(undefined);

  useEffect(() => {
    if (!projectId) return;
    api
      .getArchitectureDoc(projectId)
      .then((d) => setDoc(d as unknown as Record<string, unknown>))
      .catch(() => setDoc(null));
  }, [projectId]);

  const md =
    doc &&
    (((doc as {content?: string}).content ||
      (doc as {markdown?: string}).markdown ||
      (doc as {docMarkdown?: string}).docMarkdown) as string | undefined);

  return (
    <div className='ide-pane'>
      <ProjectTabs projectId={projectId} />
      <div className='ide-h1'>Architecture</div>
      {doc === undefined ? (
        <IdeLoader label='Loading architecture…' />
      ) : !md ? (
        <div className='ide-empty'>No architecture doc yet — generate one from the dashboard.</div>
      ) : (
        <div className='ide-card signal-md' style={{fontSize: 13.5, lineHeight: 1.6}}>
          <Markdown>{md}</Markdown>
        </div>
      )}
    </div>
  );
}

// ───────────────────────── Members ─────────────────────────
export function IdeProjectMembers() {
  const {projectId = ''} = useParams();
  const [members, setMembers] = useState<api.ProjectMemberDto[] | null>(null);

  const load = () =>
    api
      .getProjectMembers(projectId)
      .then(setMembers)
      .catch(() => setMembers([]));
  useEffect(() => {
    if (projectId) load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId]);

  return (
    <div className='ide-pane'>
      <ProjectTabs projectId={projectId} />
      <div className='ide-h1'>Members</div>
      {members === null ? (
        <IdeLoader label='Loading members…' />
      ) : members.length === 0 ? (
        <div className='ide-empty'>No members.</div>
      ) : (
        <div className='ide-card' style={{padding: 0}}>
          {members.map((m, i) => (
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
              </span>
              <span style={{fontSize: 12, color: 'var(--ide-fg-muted)'}}>{m.email}</span>
              <IdeBadge kind='ok'>{m.projectRole}</IdeBadge>
              <button
                className='ide-btn ghost'
                style={{padding: '3px 8px', color: 'var(--ide-red)'}}
                onClick={() =>
                  api
                    .removeProjectMember(projectId, m.userId)
                    .then(load)
                    .catch(() => {})
                }
              >
                Remove
              </button>
            </div>
          ))}
        </div>
      )}
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

// ───────────────────────── Settings ─────────────────────────
export function IdeProjectSettings() {
  const {projectId = ''} = useParams();
  const navigate = useNavigate();
  const [project, setProject] = useState<api.ProjectDto | null>(null);
  const [provider, setProvider] = useState('ANTHROPIC');
  const [msg, setMsg] = useState('');

  useEffect(() => {
    if (!projectId) return;
    api
      .getProject(projectId)
      .then((p) => {
        setProject(p);
        if (p.aiProvider) setProvider(p.aiProvider.toUpperCase());
      })
      .catch(() => {});
  }, [projectId]);

  return (
    <div className='ide-pane' style={{maxWidth: 760}}>
      <ProjectTabs projectId={projectId} />
      <div className='ide-h1'>Settings</div>
      {!project ? (
        <IdeLoader label='Loading settings…' />
      ) : (
        <>
          {msg && (
            <div className='ide-card' style={{marginBottom: 12, color: 'var(--ide-blue)'}}>
              {msg}
            </div>
          )}
          <div className='ide-card'>
            <div className='ide-h2' style={{marginTop: 0}}>
              Default provider
            </div>
            <select
              className='ide-select'
              value={provider}
              onChange={(e) => setProvider(e.target.value)}
            >
              <option value='ANTHROPIC'>Anthropic</option>
              <option value='OPENAI'>OpenAI</option>
              <option value='GEMINI'>Gemini</option>
            </select>
            <button
              className='ide-btn'
              style={{marginTop: 12}}
              onClick={() =>
                api
                  .updateProjectSettings(projectId, {aiProvider: provider})
                  .then(() => setMsg('Saved.'))
                  .catch((e) => setMsg(e instanceof Error ? e.message : 'Save failed.'))
              }
            >
              Save settings
            </button>
          </div>

          <div className='ide-h2'>Danger zone</div>
          <div className='ide-card' style={{display: 'flex', gap: 8}}>
            <button
              className='ide-btn ghost'
              onClick={() =>
                window.confirm('Archive this project?') &&
                api
                  .archiveProject(projectId)
                  .then(() => navigate('/projects'))
                  .catch(() => {})
              }
            >
              Archive
            </button>
            <button
              className='ide-btn ghost'
              style={{color: 'var(--ide-red)'}}
              onClick={() =>
                window.confirm('Delete this project? This cannot be undone.') &&
                api
                  .deleteProject(projectId)
                  .then(() => navigate('/projects'))
                  .catch(() => {})
              }
            >
              Delete
            </button>
          </div>
        </>
      )}
    </div>
  );
}
