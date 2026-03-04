import { useEffect, useRef, useState } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { Send, Loader2, Rocket, MessageSquare } from 'lucide-react';
import { getRunChat, sendRunChatMessage, iterateRun } from '@/lib/api';
import type { RunChatMessage, ChatResponse } from '@/lib/api';
import { cn } from '@/lib/utils';
import { useRunStore } from '@/stores/useRunStore';

const MAX_MESSAGES = 10;

interface Props {
  runId: string;
}

/**
 * Post-run conversational AI chat panel.
 *
 * • Shows the conversation history (user + assistant turns).
 * • Allows the user to ask up to MAX_MESSAGES questions about the generated code.
 * • Offers an "Implement as New Run" button to create an iteration run.
 */
export default function RunConversation({ runId }: Props) {
  const [messages, setMessages]         = useState<RunChatMessage[]>([]);
  const [userCount, setUserCount]       = useState(0);
  const [input, setInput]               = useState('');
  const [sending, setSending]           = useState(false);
  const [iterating, setIterating]       = useState(false);
  const [iterateInput, setIterateInput] = useState('');
  const [showIterateBox, setShowIterateBox] = useState(false);
  const [error, setError]               = useState<string | null>(null);
  const [loading, setLoading]           = useState(true);
  const bottomRef = useRef<HTMLDivElement>(null);
  const inputRef  = useRef<HTMLTextAreaElement>(null);
  const setActiveRunId = useRunStore((s) => s.setActiveRunId);

  // Load chat history on mount / when runId changes
  useEffect(() => {
    setLoading(true);
    setMessages([]);
    setUserCount(0);
    setError(null);
    getRunChat(runId)
      .then((history) => {
        setMessages(history);
        const uCount = history.filter((m) => m.role === 'user').length;
        setUserCount(uCount);
      })
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [runId]);

  // Auto-scroll to bottom whenever messages change
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  const limitReached = userCount >= MAX_MESSAGES;
  const hasConversation = messages.length > 0;

  const handleSend = async () => {
    const text = input.trim();
    if (!text || sending || limitReached) return;

    setInput('');
    setSending(true);
    setError(null);

    // Optimistically add the user message
    const optimisticMsg: RunChatMessage = { role: 'user', content: text };
    setMessages((prev) => [...prev, optimisticMsg]);

    try {
      const resp: ChatResponse = await sendRunChatMessage(runId, text);
      const assistantMsg: RunChatMessage = { role: 'assistant', content: resp.reply };
      setMessages((prev) => [...prev.slice(0, -1), optimisticMsg, assistantMsg]);
      setUserCount(resp.messageCount);
    } catch (e: any) {
      // Remove the optimistic message on failure
      setMessages((prev) => prev.slice(0, -1));
      if (e.message?.includes('429')) {
        setError('Conversation limit reached. Click "Implement as New Run" to continue.');
      } else {
        setError(e.message ?? 'Failed to send message. Please try again.');
      }
    } finally {
      setSending(false);
      inputRef.current?.focus();
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  };

  const handleIterate = async () => {
    const req = iterateInput.trim() || buildSuggestedRequest();
    if (!req || iterating) return;

    setIterating(true);
    setError(null);
    try {
      const { runId: newRunId } = await iterateRun(runId, req);
      // Navigate to the new run by selecting it in the sidebar
      setActiveRunId(newRunId);
      setShowIterateBox(false);
      setIterating(false);
    } catch (e: any) {
      setError(e.message ?? 'Failed to create iteration run. Please try again.');
      setIterating(false);
    }
  };

  /** Build a suggested request from the last user message in the conversation. */
  const buildSuggestedRequest = (): string => {
    const lastUserMsg = [...messages].reverse().find((m) => m.role === 'user');
    return lastUserMsg?.content ?? '';
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center h-full">
        <Loader2 className="h-4 w-4 animate-spin text-zinc-500" />
      </div>
    );
  }

  return (
    <div className="flex flex-col h-full">

      {/* Header */}
      <div className="flex items-center justify-between px-4 py-3 border-b border-white/[0.06] flex-shrink-0">
        <div className="flex items-center gap-2">
          <MessageSquare className="h-4 w-4 text-accent-violet" />
          <span className="text-sm font-medium text-zinc-200">Ask about this run</span>
        </div>
        <div className="flex items-center gap-3">
          {/* Message counter */}
          <span className={cn(
            'text-xs font-mono tabular-nums',
            userCount >= MAX_MESSAGES - 2 ? 'text-accent-rose' :
            userCount >= MAX_MESSAGES / 2   ? 'text-accent-amber' :
                                              'text-zinc-600'
          )}>
            {userCount}/{MAX_MESSAGES} messages
          </span>

          {/* Implement as New Run button */}
          <button
            onClick={() => setShowIterateBox((v) => !v)}
            className={cn(
              'flex items-center gap-1.5 px-2.5 py-1.5 rounded text-xs font-medium transition-all',
              'bg-accent-violet/15 text-accent-violet hover:bg-accent-violet/25',
              iterating && 'opacity-50 cursor-not-allowed'
            )}
            disabled={iterating}
          >
            {iterating
              ? <Loader2 className="h-3 w-3 animate-spin" />
              : <Rocket className="h-3 w-3" />}
            Implement as New Run
          </button>
        </div>
      </div>

      {/* Iterate box */}
      <AnimatePresence>
        {showIterateBox && (
          <motion.div
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: 'auto', opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            transition={{ duration: 0.2 }}
            className="overflow-hidden border-b border-white/[0.06] flex-shrink-0"
          >
            <div className="px-4 py-3 space-y-2 bg-accent-violet/[0.03]">
              <p className="text-xs text-zinc-500 leading-relaxed">
                Describe the changes you want to implement. The new run will start with a
                copy of this run's codebase and apply your changes via the full agent pipeline.
              </p>
              <textarea
                className={cn(
                  'w-full bg-white/[0.04] border border-white/[0.08] rounded-lg px-3 py-2',
                  'text-sm text-zinc-200 placeholder-zinc-600 resize-none focus:outline-none',
                  'focus:border-accent-violet/40 focus:bg-white/[0.06] transition-colors'
                )}
                rows={3}
                placeholder={buildSuggestedRequest() || 'Describe what you want to change or add…'}
                value={iterateInput}
                onChange={(e) => setIterateInput(e.target.value)}
              />
              <div className="flex justify-end gap-2">
                <button
                  onClick={() => setShowIterateBox(false)}
                  className="px-3 py-1.5 text-xs text-zinc-500 hover:text-zinc-300 transition-colors"
                >
                  Cancel
                </button>
                <button
                  onClick={handleIterate}
                  disabled={iterating || (!iterateInput.trim() && !buildSuggestedRequest())}
                  className={cn(
                    'flex items-center gap-1.5 px-3 py-1.5 rounded text-xs font-medium transition-all',
                    'bg-accent-violet text-white hover:bg-accent-violet/90',
                    'disabled:opacity-40 disabled:cursor-not-allowed'
                  )}
                >
                  {iterating
                    ? <><Loader2 className="h-3 w-3 animate-spin" /> Creating run…</>
                    : <><Rocket className="h-3 w-3" /> Start New Run</>
                  }
                </button>
              </div>
            </div>
          </motion.div>
        )}
      </AnimatePresence>

      {/* Messages area */}
      <div className="flex-1 overflow-y-auto px-4 py-4 space-y-4">

        {/* Empty state */}
        {!hasConversation && (
          <div className="flex flex-col items-center justify-center h-full text-center space-y-3 py-8">
            <MessageSquare className="h-8 w-8 text-zinc-700" />
            <div>
              <p className="text-sm font-medium text-zinc-400">
                Ask about the generated code
              </p>
              <p className="text-xs text-zinc-600 mt-1 max-w-xs leading-relaxed">
                You can ask about the architecture, request changes, or get explanations.
                Up to {MAX_MESSAGES} messages — then click "Implement as New Run" to apply changes.
              </p>
            </div>
          </div>
        )}

        {/* Message list */}
        {messages.map((msg, i) => (
          <motion.div
            key={i}
            initial={{ opacity: 0, y: 6 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.2 }}
            className={cn(
              'flex',
              msg.role === 'user' ? 'justify-end' : 'justify-start'
            )}
          >
            <div className={cn(
              'max-w-[85%] rounded-2xl px-4 py-3 text-sm leading-relaxed',
              msg.role === 'user'
                ? 'bg-accent-violet/20 text-zinc-100 rounded-br-sm'
                : 'bg-white/[0.05] border border-white/[0.06] text-zinc-300 rounded-bl-sm'
            )}>
              <p className="whitespace-pre-wrap break-words">{msg.content}</p>
            </div>
          </motion.div>
        ))}

        {/* Sending indicator */}
        {sending && (
          <div className="flex justify-start">
            <div className="bg-white/[0.05] border border-white/[0.06] rounded-2xl rounded-bl-sm px-4 py-3">
              <div className="flex items-center gap-1.5">
                <span className="h-1.5 w-1.5 bg-zinc-500 rounded-full animate-bounce [animation-delay:0ms]" />
                <span className="h-1.5 w-1.5 bg-zinc-500 rounded-full animate-bounce [animation-delay:150ms]" />
                <span className="h-1.5 w-1.5 bg-zinc-500 rounded-full animate-bounce [animation-delay:300ms]" />
              </div>
            </div>
          </div>
        )}

        {/* Error */}
        {error && (
          <div className="mx-auto max-w-md p-3 rounded-lg bg-accent-rose/10 border border-accent-rose/20 text-xs text-accent-rose text-center">
            {error}
          </div>
        )}

        <div ref={bottomRef} />
      </div>

      {/* Limit reached banner */}
      {limitReached && (
        <div className="px-4 py-3 border-t border-white/[0.06] bg-accent-rose/[0.04] flex-shrink-0">
          <p className="text-xs text-accent-rose text-center">
            Conversation limit reached. Click{' '}
            <button
              className="underline text-accent-violet hover:opacity-80"
              onClick={() => setShowIterateBox(true)}
            >
              Implement as New Run
            </button>{' '}
            to continue with a new run.
          </p>
        </div>
      )}

      {/* Input area */}
      {!limitReached && (
        <div className="px-4 py-3 border-t border-white/[0.06] flex-shrink-0">
          <div className={cn(
            'flex items-end gap-2 bg-white/[0.04] border rounded-xl px-3 py-2 transition-colors',
            input ? 'border-accent-violet/40' : 'border-white/[0.08]'
          )}>
            <textarea
              ref={inputRef}
              className="flex-1 bg-transparent text-sm text-zinc-200 placeholder-zinc-600 resize-none focus:outline-none max-h-32 min-h-[24px]"
              placeholder="Ask about the code, request changes…"
              rows={1}
              value={input}
              onChange={(e) => {
                setInput(e.target.value);
                // Auto-resize
                e.target.style.height = 'auto';
                e.target.style.height = `${Math.min(e.target.scrollHeight, 128)}px`;
              }}
              onKeyDown={handleKeyDown}
              disabled={sending || limitReached}
            />
            <button
              onClick={handleSend}
              disabled={!input.trim() || sending || limitReached}
              className={cn(
                'p-1.5 rounded-lg flex-shrink-0 transition-all',
                input.trim() && !sending
                  ? 'bg-accent-violet text-white hover:bg-accent-violet/90'
                  : 'text-zinc-600 cursor-not-allowed'
              )}
            >
              {sending
                ? <Loader2 className="h-4 w-4 animate-spin" />
                : <Send className="h-4 w-4" />
              }
            </button>
          </div>
          <p className="text-[10px] text-zinc-700 mt-1 pl-1">
            Press Enter to send · Shift+Enter for new line
          </p>
        </div>
      )}
    </div>
  );
}
