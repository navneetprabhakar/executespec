import {useEffect, useRef, useState} from 'react';
import {useParams} from 'react-router-dom';
import {TopBar} from '@/signal/components/Chrome';
import {Feed, Card, Button} from '@/signal/components/Primitives';
import {ProjectTabs} from './ProjectTabs';
import {useProjectMeta} from '@/signal/hooks/useProjectMeta';
import * as api from '@/lib/api';

type ChatMsg = {role: 'user' | 'assistant' | 'system'; content: string; ts?: string};

export default function ProjectChatPage() {
  const {projectId} = useParams();
  const id = projectId || '';
  const [memory, setMemory] = useState<api.ProjectMemoryResponse | null>(null);
  const [messages, setMessages] = useState<ChatMsg[]>([]);
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');
  const project = useProjectMeta(id);
  const tail = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (!id) return;
    let cancelled = false;
    Promise.allSettled([
      api.getProjectMemory(id),
      api.getProjectChatInfo(id),
    ]).then(([m, c]) => {
      if (cancelled) return;
      if (m.status === 'fulfilled') setMemory(m.value);
      const cAny = c.status === 'fulfilled' ? (c.value as unknown as {messages?: ChatMsg[]}) : null;
      if (cAny?.messages) setMessages(cAny.messages);
    });
    return () => {
      cancelled = true;
    };
  }, [id]);

  useEffect(() => {
    tail.current?.scrollIntoView({behavior: 'smooth', block: 'end'});
  }, [messages.length]);

  async function send() {
    const text = input.trim();
    if (!text || busy) return;
    setMessages((prev) => [...prev, {role: 'user', content: text, ts: new Date().toISOString()}]);
    setInput('');
    setBusy(true);
    setErr('');
    try {
      // Use the project chat compiler endpoint via conversationChat as a
      // generic chat surface — project-scoped chat lives in the dedicated
      // ProjectChatService which compileProjectChatToSpec calls underneath.
      // For now, surface the response via conversationChat directly.
      const resp = await api.conversationChat([{role: 'user', content: text}]);
      const out = (resp as unknown as {reply?: string; message?: string; content?: string}).reply ||
        (resp as unknown as {message?: string}).message ||
        (resp as unknown as {content?: string}).content ||
        '(no reply)';
      setMessages((prev) => [...prev, {role: 'assistant', content: out, ts: new Date().toISOString()}]);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Chat failed.');
    } finally {
      setBusy(false);
    }
  }

  async function compactChat() {
    try {
      await api.compactProjectChat(id);
      setMessages([]);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Compact failed.');
    }
  }

  async function clearChat() {
    if (!window.confirm('Clear chat history for this project?')) return;
    try {
      await api.clearProjectChat(id);
      setMessages([]);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Clear failed.');
    }
  }

  const memDigest = (memory as unknown as {summary?: string; digest?: string})?.summary ||
    (memory as unknown as {digest?: string})?.digest;
  const memRunCount = (memory as unknown as {runCount?: number; totalRuns?: number})?.runCount ??
    (memory as unknown as {totalRuns?: number})?.totalRuns ?? 0;

  return (
    <>
      <TopBar
        title='Project chat'
        sub={`${project?.name || 'project'} · memory: ${memRunCount} runs`}
        back
        backTo={`/projects/${id}`}
        actions={
          <>
            <Button kind='ghost' sm onClick={compactChat}>Compact</Button>
            <Button kind='ghost' sm onClick={clearChat}>Clear</Button>
          </>
        }
      />
      <Feed padBottom={140}>
        <ProjectTabs active='chat' />

        {err && <div style={{background: 'var(--signal-coral-soft)', color: 'var(--signal-coral-text)', border: '1px solid var(--signal-coral)', borderRadius: 10, padding: '10px 14px', marginBottom: 12, fontSize: 13}}>{err}</div>}

        {memDigest && (
          <Card style={{background: 'var(--signal-y-soft)', borderColor: 'transparent'}}>
            <div style={{fontSize: 11, fontWeight: 700, color: 'var(--signal-y-text)', letterSpacing: '0.14em', textTransform: 'uppercase', marginBottom: 6}}>
              Memory digest · {memRunCount} runs
            </div>
            <div style={{fontSize: 13.5, color: 'var(--signal-y-text)', lineHeight: 1.5, whiteSpace: 'pre-wrap'}}>
              {memDigest}
            </div>
          </Card>
        )}

        <div style={{display: 'flex', flexDirection: 'column', gap: 10, marginTop: 16}}>
          {messages.length === 0 && !busy && (
            <Card>
              <p style={{fontSize: 13.5, color: 'var(--signal-text-dim)', margin: 0, lineHeight: 1.5}}>
                Ask anything about this project — recent runs, architecture, common pitfalls, what's been shipped.
              </p>
            </Card>
          )}
          {messages.map((m, i) => (
            <div
              key={i}
              style={{
                alignSelf: m.role === 'user' ? 'flex-end' : 'flex-start',
                maxWidth: '85%',
                padding: '10px 14px',
                background: m.role === 'user' ? 'var(--signal-ink)' : 'var(--signal-card-bg)',
                color: m.role === 'user' ? 'var(--signal-bg)' : 'var(--signal-ink)',
                borderRadius: 14,
                ...(m.role === 'user' ? {borderBottomRightRadius: 4} : {borderBottomLeftRadius: 4, border: '1px solid var(--signal-hairline)'}),
              }}
            >
              <div style={{fontFamily: "'JetBrains Mono', monospace", fontSize: 10, opacity: 0.6, marginBottom: 4}}>
                {m.role === 'user' ? 'YOU' : 'PROJECT'}{m.ts && ` · ${new Date(m.ts).toLocaleTimeString()}`}
              </div>
              <div style={{fontSize: 14, lineHeight: 1.5, whiteSpace: 'pre-wrap'}}>{m.content}</div>
            </div>
          ))}
          {busy && (
            <div style={{alignSelf: 'flex-start', padding: '10px 14px', color: 'var(--signal-text-dim)', fontSize: 13}}>
              Thinking…
            </div>
          )}
          <div ref={tail} />
        </div>
      </Feed>

      <div
        style={{
          position: 'absolute',
          bottom: 0,
          left: 0,
          right: 0,
          padding: '12px 16px',
          background: 'var(--signal-bg-translucent)',
          backdropFilter: 'blur(20px)',
          borderTop: '1px solid var(--signal-hairline)',
          display: 'flex',
          gap: 8,
          alignItems: 'flex-end',
          zIndex: 25,
        }}
      >
        <textarea
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault();
              void send();
            }
          }}
          placeholder='Ask the project…'
          rows={1}
          disabled={busy}
          style={{
            flex: 1,
            background: 'var(--signal-card-elev)',
            border: '1px solid var(--signal-hairline)',
            borderRadius: 22,
            padding: '11px 16px',
            fontFamily: 'inherit',
            fontSize: 14,
            color: 'var(--signal-ink)',
            resize: 'none',
            outline: 'none',
            minHeight: 44,
            maxHeight: 120,
          }}
        />
        <button
          type='button'
          disabled={busy || !input.trim()}
          onClick={send}
          style={{
            width: 44,
            height: 44,
            borderRadius: '50%',
            background: 'var(--signal-ink)',
            color: 'var(--signal-bg)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            flexShrink: 0,
            border: 0,
            cursor: busy ? 'wait' : 'pointer',
            opacity: !input.trim() ? 0.5 : 1,
          }}
          aria-label='Send'
        >
          <svg viewBox='0 0 24 24' style={{width: 16, height: 16}} fill='none' stroke='currentColor' strokeWidth={2.4} strokeLinecap='round'>
            <line x1='22' y1='2' x2='11' y2='13' />
            <polygon points='22 2 15 22 11 13 2 9 22 2' />
          </svg>
        </button>
      </div>
    </>
  );
}
