import {useEffect, useRef, useState} from 'react';
import {Button} from '@/signal/components/Primitives';
import {Spinner} from '@/signal/components/Loaders';
import {Markdown} from '@/signal/components/Markdown';
import * as api from '@/lib/api';

// Sparkle / "build" mark used on the chat trigger + bubble label.
function SparkleIcon({size = 14, color}: {size?: number; color?: string}) {
  return (
    <svg
      viewBox='0 0 24 24'
      style={{width: size, height: size, color: color ?? 'currentColor'}}
      aria-hidden
    >
      <path
        fill='currentColor'
        d='M12 2l1.5 5.5L19 9l-5.5 1.5L12 16l-1.5-5.5L5 9l5.5-1.5L12 2zm6 12l.9 2.6L21.5 17l-2.6.4L18 20l-.9-2.6L14.5 17l2.6-.4L18 14zm-13 2l.7 2L8 17l-2.3.0L5 19l-.7-2L2 17l2.3-.0L5 16z'
      />
    </svg>
  );
}

/**
 * Per-spec chat panel — lets the user discuss their requirement with the agent
 * BEFORE submitting it as a run. Used in two places:
 *   • /compose (global)               — props.projectId undefined
 *   • /projects/:id  (Compose tab)    — projectId scopes the conversation
 *     to project memory so the agent answers with real codebase context.
 *
 * The "Use this as my spec" action copies the latest refined draft back to
 * the composer textarea (via `onApply`) so the user can tweak + submit.
 *
 * Why this matters: in v3 the same affordance lived in the project chat
 * panel + Copilot, and users relied on it to iterate before spending credits.
 * Putting it directly under the composer means the conversation is right
 * where the next action happens.
 */
export function SpecChat({
  projectId,
  draftSpec,
  onApply,
}: {
  projectId?: string;
  draftSpec: string;
  onApply: (refined: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const [messages, setMessages] = useState<
    Array<{role: 'user' | 'assistant'; content: string; ts: string}>
  >([]);
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');
  const tail = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    tail.current?.scrollIntoView({behavior: 'smooth', block: 'end'});
  }, [messages.length]);

  // Last-assistant message becomes the candidate refined spec.
  const lastAssistant = [...messages].reverse().find((m) => m.role === 'assistant');

  async function send() {
    const text = input.trim();
    if (!text || busy) return;
    setBusy(true);
    setErr('');
    // Prepend the draft spec ONLY on the first user turn so the agent
    // has the starting context. Subsequent turns rely on conversation
    // history. Earlier the draft was prepended every turn, which both
    // (a) burned tokens and (b) only sent the latest message — the
    // agent had no memory of prior turns. Now we send the FULL message
    // history so the chat is genuinely multi-turn.
    const isFirstTurn = messages.length === 0;
    const firstTurnText =
      isFirstTurn && draftSpec.trim()
        ? `My current draft spec is:\n\n${draftSpec.trim()}\n\n${text}`
        : text;
    const next: Array<{role: 'user' | 'assistant'; content: string; ts: string}> = [
      ...messages,
      {role: 'user', content: text, ts: new Date().toISOString()},
    ];
    setMessages(next);
    setInput('');
    try {
      const wireHistory: api.ConversationMessage[] = next.map((m, i) => ({
        role: m.role,
        content: i === 0 && isFirstTurn ? firstTurnText : m.content,
      }));
      const resp = await api.conversationChat(wireHistory);
      const reply =
        (resp as unknown as {reply?: string; message?: string; content?: string}).reply ||
        (resp as unknown as {message?: string}).message ||
        (resp as unknown as {content?: string}).content ||
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

  if (!open) {
    return (
      <Button
        kind='secondary'
        sm
        onClick={() => setOpen(true)}
        style={{display: 'inline-flex', alignItems: 'center', gap: 8, marginTop: 8}}
      >
        <SparkleIcon size={14} color='var(--signal-coral)' />
        Build my spec
      </Button>
    );
  }

  return (
    <div
      style={{
        marginTop: 10,
        background: 'var(--signal-card-bg)',
        border: '1px solid var(--signal-hairline)',
        borderRadius: 14,
        overflow: 'hidden',
      }}
    >
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          padding: '10px 14px',
          borderBottom: '1px solid var(--signal-hairline)',
        }}
      >
        <div
          style={{
            fontSize: 12,
            fontWeight: 700,
            letterSpacing: '0.14em',
            textTransform: 'uppercase',
            color: 'var(--signal-text-mute)',
            display: 'inline-flex',
            alignItems: 'center',
            gap: 6,
          }}
        >
          <SparkleIcon size={12} color='var(--signal-coral)' />
          Build my spec{projectId ? ' · project-scoped' : ''}
        </div>
        <Button kind='ghost' sm onClick={() => setOpen(false)}>
          Close
        </Button>
      </div>

      <div
        style={{
          display: 'flex',
          flexDirection: 'column',
          gap: 8,
          padding: 14,
          maxHeight: 320,
          overflowY: 'auto',
        }}
      >
        {messages.length === 0 && !busy && (
          <p style={{fontSize: 13, color: 'var(--signal-text-dim)', margin: 0, lineHeight: 1.5}}>
            Refine your spec before launching. Ask the agent to clarify scope, suggest test cases,
            or split a big change into smaller specs. Project-scoped chats also have access to your
            codebase memory.
          </p>
        )}
        {messages.map((m, i) => (
          <div
            key={i}
            style={{
              alignSelf: m.role === 'user' ? 'flex-end' : 'flex-start',
              maxWidth: '90%',
              padding: '8px 12px',
              background: m.role === 'user' ? 'var(--signal-ink)' : 'var(--signal-card-elev)',
              color: m.role === 'user' ? 'var(--signal-bg)' : 'var(--signal-ink)',
              borderRadius: 12,
              ...(m.role === 'user' ? {borderBottomRightRadius: 4} : {borderBottomLeftRadius: 4}),
            }}
          >
            <div
              style={{
                fontFamily: "'JetBrains Mono', monospace",
                fontSize: 10,
                opacity: 0.6,
                marginBottom: 3,
              }}
            >
              {m.role === 'user' ? 'YOU' : 'AGENT'} · {new Date(m.ts).toLocaleTimeString()}
            </div>
            {m.role === 'user' ? (
              <div style={{fontSize: 13.5, lineHeight: 1.5, whiteSpace: 'pre-wrap'}}>
                {m.content}
              </div>
            ) : (
              // Agent replies render as Markdown — same workflow ("Use as spec"
              // still copies the raw `content`), just nicer to read.
              <Markdown>{m.content}</Markdown>
            )}
          </div>
        ))}
        {busy && (
          <div
            style={{
              alignSelf: 'flex-start',
              padding: '6px 12px',
              display: 'flex',
              alignItems: 'center',
              gap: 8,
              color: 'var(--signal-text-dim)',
              fontSize: 12,
            }}
          >
            <Spinner size={11} /> Thinking…
          </div>
        )}
        <div ref={tail} />
      </div>

      {err && (
        <div
          style={{
            padding: '8px 14px',
            fontSize: 12,
            color: 'var(--signal-coral-text)',
            background: 'var(--signal-coral-soft)',
          }}
        >
          {err}
        </div>
      )}

      <div
        style={{
          padding: 10,
          borderTop: '1px solid var(--signal-hairline)',
          display: 'flex',
          flexDirection: 'column',
          gap: 8,
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
          placeholder='Ask the agent — e.g. "are there edge cases I should handle?"'
          rows={2}
          disabled={busy}
          style={{
            width: '100%',
            background: 'var(--signal-card-elev)',
            border: '1px solid var(--signal-hairline)',
            borderRadius: 10,
            padding: '8px 12px',
            fontFamily: 'inherit',
            fontSize: 13,
            color: 'var(--signal-ink)',
            resize: 'vertical',
            outline: 'none',
            minHeight: 50,
            maxHeight: 140,
            boxSizing: 'border-box',
          }}
        />
        {/* Buttons in a horizontal row UNDER the textarea — same layout on
            every viewport. Earlier the buttons were stacked in a column
            next to the textarea; on a narrow mobile screen that column was
            taller than the textarea and visually overlapped the chat
            thread above. Below-the-textarea pattern is what every modern
            chat app uses and works at any width. */}
        <div style={{display: 'flex', gap: 6, flexWrap: 'wrap'}}>
          {lastAssistant && (
            <Button
              kind='secondary'
              sm
              onClick={() => onApply(lastAssistant.content)}
              style={{flex: '1 1 0', minWidth: 130}}
            >
              Use as spec
            </Button>
          )}
          <Button
            kind='primary'
            sm
            onClick={send}
            disabled={busy || !input.trim()}
            style={{flex: '1 1 0', minWidth: 90}}
          >
            Send
          </Button>
        </div>
      </div>
    </div>
  );
}
