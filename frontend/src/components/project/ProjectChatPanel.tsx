import { useEffect, useRef, useState } from 'react';
import { Send } from 'lucide-react';
import { req } from '@/lib/api';
import { MarkdownContent } from '@/components/shared/MarkdownContent';
import { cn } from '@/lib/utils';

interface ChatMessage {
  id: string;
  role: 'USER' | 'ASSISTANT';
  content: string;
  tokensIn?: number | null;
  tokensOut?: number | null;
  createdAt: string;
}

export default function ProjectChatPanel({ projectId }: { projectId: string }) {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [draft, setDraft] = useState('');
  const [loading, setLoading] = useState(false);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const endRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    setLoading(true);
    req<ChatMessage[]>(`/projects/${projectId}/chat`)
      .then(setMessages)
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  }, [projectId]);

  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages, sending]);

  const send = async () => {
    const text = draft.trim();
    if (!text || sending) return;
    setSending(true);
    setError(null);
    const optimistic: ChatMessage = {
      id: `tmp-${Date.now()}`,
      role: 'USER',
      content: text,
      createdAt: new Date().toISOString(),
    };
    setMessages((m) => [...m, optimistic]);
    setDraft('');
    try {
      const reply = await req<ChatMessage>(`/projects/${projectId}/chat`, {
        method: 'POST',
        body: JSON.stringify({ message: text }),
      });
      setMessages((m) => [...m, reply]);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Chat failed');
    } finally {
      setSending(false);
    }
  };

  const onKey = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      send();
    }
  };

  return (
    <div className="flex flex-col h-[600px] rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)]">
      <div className="flex-1 overflow-y-auto p-3 space-y-2.5">
        {loading && <div className="text-[11px] text-muted">Loading…</div>}
        {!loading && messages.length === 0 && (
          <div className="text-[11px] text-muted">
            Ask anything about this project — architecture, past runs, decisions. I'll answer from the project's memory and architecture doc.
          </div>
        )}
        {messages.map((m) => (
          <div
            key={m.id}
            className={cn(
              'rounded-sm px-2.5 py-1.5 max-w-[85%]',
              m.role === 'USER'
                ? 'bg-accent-blue/10 border border-accent-blue/30 text-primary ml-auto'
                : 'bg-[var(--gray-200)] border border-[var(--border-default)] text-secondary',
            )}
          >
            <div className="text-[10px] uppercase tracking-wide text-muted mb-0.5">
              {m.role === 'USER' ? 'You' : 'Assistant'}
            </div>
            {m.role === 'ASSISTANT' ? (
              <div className="text-[12px] prose prose-sm prose-invert max-w-none">
                <MarkdownContent content={m.content} />
              </div>
            ) : (
              <div className="text-[12px] whitespace-pre-wrap">{m.content}</div>
            )}
          </div>
        ))}
        {sending && (
          <div className="text-[11px] text-muted italic">Assistant is thinking…</div>
        )}
        <div ref={endRef} />
      </div>
      {error && (
        <div className="text-[11px] text-accent-red px-3 py-1 border-t border-[var(--border-default)]">
          {error}
        </div>
      )}
      <div className="border-t border-[var(--border-default)] p-2 flex items-end gap-2">
        <textarea
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={onKey}
          placeholder="Ask about this project… (Enter to send, Shift+Enter for newline)"
          rows={2}
          disabled={sending}
          className="flex-1 resize-none text-[12px] rounded-sm border border-[var(--border-default)] bg-[var(--gray-200)] text-primary placeholder:text-muted px-2 py-1 focus:outline-none focus:border-accent-blue disabled:opacity-50"
        />
        <button
          onClick={send}
          disabled={sending || !draft.trim()}
          className="inline-flex items-center gap-1 text-[11px] font-medium rounded-sm border border-accent-blue/40 bg-accent-blue/10 text-accent-blue hover:bg-accent-blue/20 disabled:opacity-50 px-2.5 py-1.5"
        >
          <Send className="h-3 w-3" />
          Send
        </button>
      </div>
    </div>
  );
}
