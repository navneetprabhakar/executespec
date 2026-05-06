import {useEffect, useRef, useState} from 'react';
import {createPortal} from 'react-dom';
import {conversationChat, type ConversationMessage} from '@/lib/api';

/**
 * Pre-run "Refine with assistant" chat modal.
 *
 * Wraps `POST /api/conversation` (the requirements-analyst persona —
 * one or two clarifying questions per reply, no code, ends with a
 * `REQUIREMENT SUMMARY:` prefix when ready). Closes the gap surfaced
 * 2026-05-06: the v3 Composer dropped the v2 ConversationAgent surface
 * so users had no way to refine a fuzzy idea before submission.
 *
 * On the assistant's first `REQUIREMENT SUMMARY:` reply we surface a
 * "Use this spec →" CTA that lifts the parsed summary back into the
 * Composer's textarea. The chat thread itself is ephemeral — closes
 * with the modal.
 */
type Msg = ConversationMessage;

const SUMMARY_PREFIX = 'REQUIREMENT SUMMARY:';

interface Props {
  initialText?: string;
  onUseSpec: (spec: string) => void;
  onClose: () => void;
}

export default function ConversationModal({initialText, onUseSpec, onClose}: Props) {
  const seedMessages: Msg[] = [];
  if (initialText && initialText.trim()) {
    seedMessages.push({role: 'user', content: initialText.trim()});
  }
  const [messages, setMessages] = useState<Msg[]>(seedMessages);
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const threadRef = useRef<HTMLDivElement | null>(null);

  // Kick off the assistant's first reply if we seeded with the user's
  // textarea text. Without this the modal opens to a thread with the
  // user message and no response — feels broken.
  useEffect(() => {
    if (seedMessages.length > 0 && messages.length === seedMessages.length) {
      void send(seedMessages);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Auto-scroll thread on each new message.
  useEffect(() => {
    threadRef.current?.scrollTo({top: threadRef.current.scrollHeight, behavior: 'smooth'});
  }, [messages, busy]);

  // Esc closes — dialog convention.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !busy) onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [busy, onClose]);

  async function send(thread: Msg[]) {
    setBusy(true);
    setError(null);
    try {
      const res = await conversationChat(thread);
      setMessages([...thread, {role: 'assistant', content: res.reply}]);
    } catch (e) {
      setError((e as Error)?.message ?? 'Conversation failed');
    } finally {
      setBusy(false);
    }
  }

  async function onSubmit(e?: React.FormEvent) {
    e?.preventDefault();
    const text = input.trim();
    if (!text || busy) return;
    const next: Msg[] = [...messages, {role: 'user', content: text}];
    setMessages(next);
    setInput('');
    await send(next);
  }

  // Detect the most recent REQUIREMENT SUMMARY in the assistant's stream.
  // We look at every assistant message so the user can iterate (the
  // assistant may emit a summary, then the user asks a follow-up which
  // produces a refined summary).
  const summaryFromLast = (() => {
    for (let i = messages.length - 1; i >= 0; i--) {
      const m = messages[i];
      if (m.role !== 'assistant') continue;
      const idx = m.content.indexOf(SUMMARY_PREFIX);
      if (idx >= 0) {
        return m.content.slice(idx + SUMMARY_PREFIX.length).trim();
      }
    }
    return null;
  })();

  // Portal the modal to document.body so the inner <form> isn't nested
  // inside the Composer's <form> — HTML disallows nested forms and
  // Composer would otherwise capture the Send click as a run submission.
  return createPortal(
    <div
      role='dialog'
      aria-modal='true'
      aria-label='Refine spec with assistant'
      onClick={(e) => {
        if (e.target === e.currentTarget && !busy) onClose();
      }}
      style={{
        position: 'fixed',
        inset: 0,
        // Dark theme + dim backdrop = barely-visible separation. Bumped
        // opacity 0.55 → 0.82 and added a backdrop blur so the page
        // content reads as out-of-focus while the modal pops.
        background: 'rgba(5, 7, 10, 0.82)',
        backdropFilter: 'blur(6px)',
        WebkitBackdropFilter: 'blur(6px)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        zIndex: 1000,
        padding: 16,
      }}
    >
      <div
        style={{
          width: '100%',
          maxWidth: 720,
          maxHeight: '85vh',
          // Use an opaque surface stepped one rung above the page bg so
          // the modal box itself pops against the blurred backdrop. Strong
          // shadow + lifted border keep the edge crisp.
          background: 'var(--c-graphite-2, var(--v3-surface))',
          border: '1px solid var(--c-graphite-4, var(--v3-border))',
          borderRadius: 12,
          display: 'flex',
          flexDirection: 'column',
          overflow: 'hidden',
          boxShadow: '0 24px 64px rgba(0, 0, 0, 0.6), 0 0 0 1px rgba(255, 255, 255, 0.04)',
        }}
      >
        {/* Header */}
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            padding: '12px 16px',
            borderBottom: '1px solid var(--v3-border)',
          }}
        >
          <div>
            <div
              style={{
                fontSize: 14,
                fontWeight: 600,
                color: 'var(--v3-text)',
              }}
            >
              Refine with assistant
            </div>
            <div style={{fontSize: 11.5, color: 'var(--v3-text-muted)', marginTop: 2}}>
              Clarify scope, tech, integrations — then ship a tighter spec to the planner.
            </div>
          </div>
          <button
            type='button'
            onClick={onClose}
            disabled={busy}
            aria-label='Close'
            style={{
              background: 'transparent',
              color: 'var(--v3-text-muted)',
              border: '1px solid var(--v3-border)',
              borderRadius: 6,
              padding: '4px 10px',
              fontSize: 12,
              cursor: busy ? 'not-allowed' : 'pointer',
              fontFamily: 'inherit',
            }}
          >
            Close
          </button>
        </div>

        {/* Thread */}
        <div
          ref={threadRef}
          style={{
            flex: 1,
            overflowY: 'auto',
            padding: '16px 18px',
            display: 'flex',
            flexDirection: 'column',
            gap: 12,
            minHeight: 240,
          }}
        >
          {messages.length === 0 && (
            <div
              style={{
                textAlign: 'center',
                color: 'var(--v3-text-muted)',
                fontSize: 13,
                lineHeight: 1.6,
                padding: '32px 16px',
              }}
            >
              Tell the assistant what you want to build. It will ask one or two clarifying
              questions, suggest a visual style if there's a UI, and produce a tight summary you
              can ship to the planner.
            </div>
          )}

          {messages.map((m, i) => (
            <Bubble key={i} role={m.role} content={m.content} />
          ))}

          {busy && (
            <div
              style={{
                fontSize: 12,
                color: 'var(--v3-text-subtle)',
                fontStyle: 'normal',
                padding: '4px 8px',
              }}
            >
              Assistant is thinking…
            </div>
          )}

          {error && (
            <div
              role='alert'
              style={{
                padding: '8px 10px',
                background: 'rgba(239,68,68,0.10)',
                border: '1px solid rgba(239,68,68,0.36)',
                color: '#FCA5A5',
                borderRadius: 6,
                fontSize: 12,
              }}
            >
              {error}
            </div>
          )}
        </div>

        {/* Use this spec banner — appears once a REQUIREMENT SUMMARY: is in flight */}
        {summaryFromLast && (
          <div
            style={{
              padding: '10px 16px',
              borderTop: '1px solid var(--v3-border)',
              background: 'color-mix(in srgb, var(--v3-brand) 8%, transparent)',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              gap: 12,
            }}
          >
            <div style={{fontSize: 12, color: 'var(--v3-text)'}}>
              <span style={{fontWeight: 600}}>Spec ready</span>
              <span style={{color: 'var(--v3-text-muted)', marginLeft: 6}}>
                — review the summary above and ship it.
              </span>
            </div>
            <button
              type='button'
              onClick={() => {
                onUseSpec(summaryFromLast);
                onClose();
              }}
              style={{
                padding: '6px 14px',
                fontSize: 12.5,
                fontWeight: 600,
                background: 'var(--v3-brand)',
                color: 'var(--v3-text-on-brand)',
                border: 'none',
                borderRadius: 6,
                cursor: 'pointer',
                fontFamily: 'inherit',
              }}
            >
              Use this spec →
            </button>
          </div>
        )}

        {/* Input row */}
        <form
          onSubmit={onSubmit}
          style={{
            padding: '12px 16px',
            borderTop: '1px solid var(--v3-border)',
            display: 'flex',
            gap: 8,
          }}
        >
          <input
            type='text'
            value={input}
            onChange={(e) => setInput(e.target.value)}
            placeholder={
              messages.length === 0 ? 'What do you want to build?' : 'Reply or ask a follow-up…'
            }
            disabled={busy}
            autoFocus
            style={{
              flex: 1,
              padding: '8px 12px',
              background: 'var(--v3-bg)',
              color: 'var(--v3-text)',
              border: '1px solid var(--v3-border)',
              borderRadius: 6,
              fontSize: 13,
              fontFamily: 'inherit',
            }}
          />
          <button
            type='submit'
            disabled={!input.trim() || busy}
            style={{
              padding: '8px 16px',
              fontSize: 13,
              fontWeight: 600,
              background: input.trim() && !busy ? 'var(--v3-brand)' : 'var(--v3-surface-elevated)',
              color: input.trim() && !busy ? 'var(--v3-text-on-brand)' : 'var(--v3-text-subtle)',
              border: 'none',
              borderRadius: 6,
              cursor: input.trim() && !busy ? 'pointer' : 'not-allowed',
              fontFamily: 'inherit',
            }}
          >
            Send
          </button>
        </form>
      </div>
    </div>,
    document.body,
  );
}

function Bubble({role, content}: {role: 'user' | 'assistant'; content: string}) {
  const isUser = role === 'user';
  // Highlight REQUIREMENT SUMMARY: section by splitting the content + showing
  // the summary block in a tinted card so the user can see when the
  // assistant has converged on a shippable spec.
  const summaryIdx = !isUser ? content.indexOf(SUMMARY_PREFIX) : -1;
  const hasSummary = summaryIdx >= 0;
  const before = hasSummary ? content.slice(0, summaryIdx).trim() : content;
  const summary = hasSummary
    ? content.slice(summaryIdx + SUMMARY_PREFIX.length).trim()
    : '';

  return (
    <div
      style={{
        display: 'flex',
        justifyContent: isUser ? 'flex-end' : 'flex-start',
      }}
    >
      <div
        style={{
          maxWidth: '85%',
          background: isUser ? 'color-mix(in srgb, var(--v3-brand) 16%, transparent)' : 'var(--v3-bg)',
          border: `1px solid ${isUser ? 'color-mix(in srgb, var(--v3-brand) 40%, transparent)' : 'var(--v3-border)'}`,
          borderRadius: 10,
          padding: '8px 12px',
          fontSize: 13,
          lineHeight: 1.55,
          color: 'var(--v3-text)',
          whiteSpace: 'pre-wrap',
          wordBreak: 'break-word',
        }}
      >
        {before && <div>{before}</div>}
        {hasSummary && (
          <div
            style={{
              marginTop: before ? 10 : 0,
              padding: '8px 10px',
              background: 'color-mix(in srgb, var(--v3-brand) 6%, transparent)',
              border: '1px dashed color-mix(in srgb, var(--v3-brand) 50%, transparent)',
              borderRadius: 6,
              fontSize: 12.5,
            }}
          >
            <div
              style={{
                fontSize: 10,
                fontWeight: 700,
                letterSpacing: 0.5,
                textTransform: 'uppercase',
                color: 'var(--accent-blue-text, var(--v3-brand))',
                marginBottom: 6,
              }}
            >
              Requirement summary
            </div>
            <div>{summary}</div>
          </div>
        )}
      </div>
    </div>
  );
}
