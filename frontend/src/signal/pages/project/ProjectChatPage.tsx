import {useEffect, useRef, useState} from 'react';
import {useParams} from 'react-router-dom';
import {TopBar} from '@/signal/components/Chrome';
import {Feed, Card, Button} from '@/signal/components/Primitives';
import {ProjectTabs} from './ProjectTabs';
import {useProjectMeta} from '@/signal/hooks/useProjectMeta';
import {Markdown} from '@/signal/components/Markdown';
import {ConfirmModal} from '@/signal/components/Modal';
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
  const [confirmClear, setConfirmClear] = useState(false);
  const [clearBusy, setClearBusy] = useState(false);
  // Backend gates project chat behind `TierConfig.isExecutionViewEnabled`
  // (TEAM+/ENTERPRISE only). FREE / PRO hit a 403 on `chat/info`. Track that
  // here so we render a clear "upgrade to use chat" card instead of a
  // half-broken chat shell.
  const [chatGated, setChatGated] = useState(false);
  const project = useProjectMeta(id);
  const tail = useRef<HTMLDivElement | null>(null);

  // Project memory is appended async by RunLedgerAgent on terminal — it lags
  // the run by a few seconds. Poll memory every 30s so "memory: 0 runs" on a
  // freshly active project converges without a manual refresh. Chat history
  // is loaded once on mount (sending appends locally + remotely).
  useEffect(() => {
    if (!id) return;
    let cancelled = false;
    let memTimer: number | undefined;
    api
      .getProjectChatInfo(id)
      .then((c) => {
        if (cancelled) return;
        const cAny = c as unknown as {messages?: ChatMsg[]};
        if (cAny?.messages) setMessages(cAny.messages);
      })
      .catch((e: unknown) => {
        if (cancelled) return;
        const msg = e instanceof Error ? e.message : '';
        if (/403|forbidden|Team or Enterprise/i.test(msg)) {
          setChatGated(true);
        }
        // Otherwise swallow — chat may still be usable even if /info fails.
      });
    function refreshMemory() {
      api
        .getProjectMemory(id)
        .then((m) => {
          if (!cancelled) setMemory(m);
        })
        .catch(() => {
          /* swallow */
        })
        .finally(() => {
          if (!cancelled) memTimer = window.setTimeout(refreshMemory, 30_000);
        });
    }
    refreshMemory();
    return () => {
      cancelled = true;
      if (memTimer) clearTimeout(memTimer);
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
      // Project-scoped chat: hits POST /api/projects/{id}/chat which routes
      // through ProjectChatService — that injects project memory, codemap
      // summaries, recent runs as context. Earlier we were falling back to
      // the generic `conversationChat` endpoint which has none of that
      // project context, so replies were oblivious to your codebase.
      const r = await fetch(`/api/projects/${id}/chat`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${localStorage.getItem('access_token') ?? ''}`,
        },
        body: JSON.stringify({message: text}),
      });
      if (!r.ok) throw new Error(`Project chat failed (${r.status}).`);
      const data = await r.json();
      const reply =
        (
          data as unknown as {
            reply?: string;
            message?: string;
            content?: string;
            assistantMessage?: string;
          }
        ).reply ||
        (data as unknown as {message?: string}).message ||
        (data as unknown as {content?: string}).content ||
        (data as unknown as {assistantMessage?: string}).assistantMessage ||
        '(no reply)';
      setMessages((prev) => [
        ...prev,
        {role: 'assistant', content: reply, ts: new Date().toISOString()},
      ]);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Chat failed.');
    } finally {
      setBusy(false);
    }
  }

  async function compileToSpec() {
    if (!id) return;
    setBusy(true);
    setErr('');
    try {
      const r = await api.compileProjectChatToSpec(id);
      if (r?.spec) {
        sessionStorage.setItem('signal:pending-spec', r.spec);
        window.location.href = `/projects/${id}`;
      }
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Compile failed.');
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

  async function performClear() {
    setClearBusy(true);
    try {
      await api.clearProjectChat(id);
      setMessages([]);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Clear failed.');
    } finally {
      setClearBusy(false);
      setConfirmClear(false);
    }
  }

  const memDigest =
    (memory as unknown as {summary?: string; digest?: string})?.summary ||
    (memory as unknown as {digest?: string})?.digest;
  const memRunCount =
    (memory as unknown as {runCount?: number; totalRuns?: number})?.runCount ??
    (memory as unknown as {totalRuns?: number})?.totalRuns ??
    0;

  return (
    <>
      <TopBar
        title='Project chat'
        sub={`${project?.name || 'project'} · memory: ${memRunCount} ${memRunCount === 1 ? 'run' : 'runs'}`}
        back
        backTo={`/projects/${id}`}
        actions={
          <>
            <Button kind='primary' sm onClick={compileToSpec} disabled={busy}>
              Compile to spec
            </Button>
            <Button kind='ghost' sm onClick={compactChat}>
              Compact
            </Button>
            <Button kind='ghost' sm onClick={() => setConfirmClear(true)}>
              Clear
            </Button>
          </>
        }
      />
      <Feed padBottom={140}>
        <ProjectTabs active='chat' />

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

        {memDigest && (
          <Card style={{background: 'var(--signal-y-soft)', borderColor: 'transparent'}}>
            <div
              style={{
                fontSize: 11,
                fontWeight: 700,
                color: 'var(--signal-y-text)',
                letterSpacing: '0.14em',
                textTransform: 'uppercase',
                marginBottom: 6,
              }}
            >
              Memory digest · {memRunCount} runs
            </div>
            <div
              style={{
                fontSize: 13.5,
                color: 'var(--signal-y-text)',
                lineHeight: 1.5,
                whiteSpace: 'pre-wrap',
              }}
            >
              {memDigest}
            </div>
          </Card>
        )}

        <div style={{display: 'flex', flexDirection: 'column', gap: 10, marginTop: 16}}>
          {chatGated && (
            <Card style={{background: 'var(--signal-lavender-soft)', borderColor: 'transparent'}}>
              <div
                style={{
                  fontSize: 11,
                  fontWeight: 700,
                  color: '#5443a8',
                  letterSpacing: '0.14em',
                  textTransform: 'uppercase',
                  marginBottom: 6,
                }}
              >
                Project chat is a TEAM+ feature
              </div>
              <div style={{fontSize: 13, color: '#5443a8', lineHeight: 1.5}}>
                The agent uses your project's memory + codemap + linked docs to answer questions —
                gated to TEAM and ENTERPRISE plans.{' '}
                <a href='/plans' style={{color: '#5443a8', textDecoration: 'underline'}}>
                  Compare plans →
                </a>
              </div>
            </Card>
          )}
          {!chatGated && messages.length === 0 && !busy && (
            <Card>
              <p
                style={{
                  fontSize: 13.5,
                  color: 'var(--signal-text-dim)',
                  margin: 0,
                  lineHeight: 1.5,
                }}
              >
                Ask anything about this project — recent runs, architecture, common pitfalls, what's
                been shipped.
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
                ...(m.role === 'user'
                  ? {borderBottomRightRadius: 4}
                  : {borderBottomLeftRadius: 4, border: '1px solid var(--signal-hairline)'}),
              }}
            >
              <div
                style={{
                  fontFamily: "'JetBrains Mono', monospace",
                  fontSize: 10,
                  opacity: 0.6,
                  marginBottom: 4,
                }}
              >
                {m.role === 'user' ? 'YOU' : 'PROJECT'}
                {m.ts && ` · ${new Date(m.ts).toLocaleTimeString()}`}
              </div>
              {m.role === 'user' ? (
                <div style={{fontSize: 14, lineHeight: 1.5, whiteSpace: 'pre-wrap'}}>
                  {m.content}
                </div>
              ) : (
                <Markdown>{m.content}</Markdown>
              )}
            </div>
          ))}
          {busy && (
            <div
              style={{
                alignSelf: 'flex-start',
                padding: '10px 14px',
                color: 'var(--signal-text-dim)',
                fontSize: 13,
              }}
            >
              Thinking…
            </div>
          )}
          <div ref={tail} />
        </div>

        {/* Composer pinned to the bottom of the Feed via sticky positioning.
            Previously this was position:absolute, which broke when the
            page got long enough to scroll — the bar overlapped the message
            list and the user couldn't see what they were typing. Sticky
            keeps it in normal flow + glued to the viewport bottom while
            scrolling within the Feed. */}
        <div
          style={{
            position: 'sticky',
            bottom: 0,
            marginTop: 16,
            padding: '12px 0',
            background: 'var(--signal-bg)',
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
            placeholder={chatGated ? 'Upgrade to TEAM to enable project chat.' : 'Ask the project…'}
            rows={2}
            disabled={busy || chatGated}
            style={{
              flex: 1,
              background: 'var(--signal-card-elev)',
              border: '1px solid var(--signal-hairline)',
              borderRadius: 14,
              padding: '12px 14px',
              fontFamily: 'inherit',
              fontSize: 14,
              color: 'var(--signal-ink)',
              resize: 'vertical',
              outline: 'none',
              minHeight: 60,
              maxHeight: 200,
            }}
          />
          <button
            type='button'
            disabled={busy || chatGated || !input.trim()}
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
            <svg
              viewBox='0 0 24 24'
              style={{width: 16, height: 16}}
              fill='none'
              stroke='currentColor'
              strokeWidth={2.4}
              strokeLinecap='round'
            >
              <line x1='22' y1='2' x2='11' y2='13' />
              <polygon points='22 2 15 22 11 13 2 9 22 2' />
            </svg>
          </button>
        </div>
      </Feed>

      <ConfirmModal
        open={confirmClear}
        title='Clear chat history?'
        body='All messages will be deleted permanently. The project memory snapshot is unaffected — past runs and their digests survive.'
        confirmLabel='Clear'
        danger
        busy={clearBusy}
        onClose={() => setConfirmClear(false)}
        onConfirm={performClear}
      />
    </>
  );
}
