import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { motion, AnimatePresence } from 'framer-motion';
import { ArrowLeft, ChevronDown, Rocket, Send, Sparkles, X } from 'lucide-react';
import { useRunStore } from '@/stores/useRunStore';
import { useUiStore } from '@/stores/useUiStore';
import { conversationChat, ConversationMessage } from '@/lib/api';
import { cn } from '@/lib/utils';

interface NewRunModalProps {
  onClose: () => void;
}

const MODELS = [
  { value: 'claude-opus-4-6',           label: 'Claude Opus 4.6 (Architect)' },
  { value: 'claude-sonnet-4-6',         label: 'Claude Sonnet 4.6 (Capable)' },
  { value: 'claude-haiku-4-5-20251001', label: 'Claude Haiku 4.5 (Utility)' },
  { value: 'gpt-4o',                    label: 'GPT-4o' },
  { value: 'gemini-2.5-pro',            label: 'Gemini 2.5 Pro' },
];

const MAX_MESSAGES = 10;

type Mode = 'compose' | 'chat';

export default function NewRunModal({ onClose }: NewRunModalProps) {
  // ── compose mode state ────────────────────────────────────────────────────
  const [goal, setGoal] = useState('');
  const [showAdvanced, setShowAdvanced] = useState(false);
  const [skipApproval, setSkipApproval] = useState(false);
  const [model, setModel] = useState('');
  const [launching, setLaunching] = useState(false);

  // ── chat mode state ───────────────────────────────────────────────────────
  const [mode, setMode] = useState<Mode>('compose');
  const [messages, setMessages] = useState<ConversationMessage[]>([]);
  const [draft, setDraft] = useState('');
  const [chatLoading, setChatLoading] = useState(false);
  const messagesEndRef = useRef<HTMLDivElement>(null);

  const { createRun, setActiveRunId } = useRunStore();
  const { addToast } = useUiStore();
  const navigate = useNavigate();

  // Auto-scroll to bottom of chat
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  // ── enter chat mode: seed with the user's current goal ───────────────────
  const enterChat = async () => {
    const seed = goal.trim();
    if (!seed) return;

    const first: ConversationMessage = { role: 'user', content: seed };
    setMessages([first]);
    setMode('chat');
    setChatLoading(true);

    try {
      const { reply } = await conversationChat([first]);
      setMessages([first, { role: 'assistant', content: reply }]);
    } catch (e: any) {
      addToast({ type: 'error', message: `Chat error: ${e.message}` });
      setMode('compose');
    } finally {
      setChatLoading(false);
    }
  };

  // ── send a new user message ───────────────────────────────────────────────
  const sendMessage = async () => {
    const text = draft.trim();
    if (!text || chatLoading || messages.length >= MAX_MESSAGES) return;

    const updated: ConversationMessage[] = [...messages, { role: 'user', content: text }];
    setMessages(updated);
    setDraft('');
    setChatLoading(true);

    try {
      const { reply } = await conversationChat(updated);
      setMessages([...updated, { role: 'assistant', content: reply }]);
    } catch (e: any) {
      addToast({ type: 'error', message: `Chat error: ${e.message}` });
    } finally {
      setChatLoading(false);
    }
  };

  // ── use an AI message as the goal, return to compose ─────────────────────
  const useAsGoal = (text: string) => {
    // Strip the "REQUIREMENT SUMMARY:" prefix if the LLM included it
    const cleaned = text.replace(/^REQUIREMENT SUMMARY:\s*/im, '').trim();
    setGoal(cleaned);
    setMode('compose');
  };

  // ── launch the pipeline run ───────────────────────────────────────────────
  const handleSubmit = async () => {
    if (!goal.trim()) return;
    setLaunching(true);
    try {
      const runId = await createRun({
        userRequest: goal,
        skipApproval,
        ...(model ? { modelName: model } : {}),
      });
      addToast({ type: 'success', message: `Run launched: ${runId.slice(0, 8)}` });
      navigate('/');
      setActiveRunId(runId);
      onClose();
    } catch (e: any) {
      addToast({ type: 'error', message: e.message });
      setLaunching(false);
    }
  };

  const msgCount = messages.length;
  const atLimit  = msgCount >= MAX_MESSAGES;
  // User can only send if there's a slot available for both their message AND the AI reply
  const canSend  = !chatLoading && messages.length < MAX_MESSAGES && draft.trim().length > 0;

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center p-4"
      style={{ backdropFilter: 'blur(4px)', background: 'rgba(0,0,0,0.6)' }}
      onClick={onClose}
    >
      <motion.div
        initial={{ opacity: 0, scale: 0.96 }}
        animate={{ opacity: 1, scale: 1 }}
        exit={{ opacity: 0, scale: 0.96 }}
        transition={{ duration: 0.15 }}
        onClick={(e) => e.stopPropagation()}
        className={cn(
          'w-full rounded-2xl bg-surface-2 border border-white/[0.08] shadow-2xl overflow-hidden flex flex-col',
          mode === 'chat' ? 'max-w-xl' : 'max-w-xl',
        )}
        style={{ maxHeight: mode === 'chat' ? '80vh' : undefined }}
      >
        {/* ── Header ───────────────────────────────────────────────────────── */}
        <div className="flex items-center justify-between px-6 py-4 border-b border-white/[0.06] flex-shrink-0">
          {mode === 'chat' ? (
            <button
              onClick={() => setMode('compose')}
              className="flex items-center gap-1.5 text-sm text-zinc-400 hover:text-zinc-200 transition-colors"
            >
              <ArrowLeft className="h-3.5 w-3.5" />
              Back to goal
            </button>
          ) : (
            <h2 className="text-base font-semibold text-white flex items-center gap-2">
              <Rocket className="h-4 w-4 text-accent-violet" />
              Launch New Run
            </h2>
          )}

          <div className="flex items-center gap-3">
            {mode === 'chat' && (
              <span className={cn(
                'text-xs font-mono tabular-nums px-2 py-0.5 rounded-full',
                atLimit
                  ? 'bg-accent-rose/15 text-accent-rose'
                  : 'bg-white/[0.06] text-zinc-400',
              )}>
                {msgCount}/{MAX_MESSAGES}
              </span>
            )}
            <button onClick={onClose} className="text-zinc-500 hover:text-zinc-300">
              <X className="h-4 w-4" />
            </button>
          </div>
        </div>

        {/* ── Compose mode ─────────────────────────────────────────────────── */}
        {mode === 'compose' && (
          <div className="p-6 space-y-4">
            <textarea
              autoFocus
              value={goal}
              onChange={(e) => setGoal(e.target.value)}
              placeholder="Describe what you want to build..."
              rows={5}
              className={cn(
                'w-full font-mono text-sm bg-surface-1 border border-white/[0.08] rounded-lg',
                'px-4 py-3 text-zinc-200 placeholder:text-zinc-600',
                'focus:outline-none focus:border-accent-violet/40 resize-none',
              )}
            />

            {/* Refine with AI button */}
            <button
              onClick={enterChat}
              disabled={!goal.trim()}
              className={cn(
                'w-full py-2 rounded-lg text-sm font-medium transition-all flex items-center justify-center gap-2',
                'border border-accent-violet/30 text-accent-violet/80',
                'hover:border-accent-violet/60 hover:text-accent-violet hover:bg-accent-violet/5',
                'disabled:opacity-30 disabled:cursor-not-allowed',
              )}
            >
              <Sparkles className="h-3.5 w-3.5" />
              Refine with AI
            </button>

            {/* Advanced toggle */}
            <button
              onClick={() => setShowAdvanced(!showAdvanced)}
              className="flex items-center gap-1.5 text-xs text-zinc-500 hover:text-zinc-300"
            >
              <ChevronDown className={cn('h-3.5 w-3.5 transition-transform', showAdvanced && 'rotate-180')} />
              Advanced options
            </button>

            <AnimatePresence>
              {showAdvanced && (
                <motion.div
                  initial={{ height: 0, opacity: 0 }}
                  animate={{ height: 'auto', opacity: 1 }}
                  exit={{ height: 0, opacity: 0 }}
                  className="overflow-hidden space-y-3"
                >
                  <label className="flex items-center justify-between">
                    <span className="text-sm text-zinc-400">Skip approval gate</span>
                    <button
                      role="switch"
                      aria-checked={skipApproval}
                      onClick={() => setSkipApproval(!skipApproval)}
                      className={cn(
                        'relative h-5 w-9 rounded-full transition-colors',
                        skipApproval ? 'bg-accent-violet' : 'bg-white/[0.10]',
                      )}
                    >
                      <span className={cn(
                        'absolute top-0.5 h-4 w-4 rounded-full bg-white shadow transition-transform',
                        skipApproval ? 'translate-x-4' : 'translate-x-0.5',
                      )} />
                    </button>
                  </label>
                  <div>
                    <label className="block text-sm text-zinc-400 mb-1.5">Model override</label>
                    <select
                      value={model}
                      onChange={(e) => setModel(e.target.value)}
                      className="w-full bg-surface-1 border border-white/[0.08] rounded-lg px-3 py-2 text-sm text-zinc-300 focus:outline-none focus:border-accent-violet/40"
                    >
                      <option value="">Default (Claude Opus)</option>
                      {MODELS.map((m) => (
                        <option key={m.value} value={m.value}>{m.label}</option>
                      ))}
                    </select>
                  </div>
                </motion.div>
              )}
            </AnimatePresence>

            <button
              onClick={handleSubmit}
              disabled={!goal.trim() || launching}
              className={cn(
                'w-full py-2.5 rounded-lg font-medium text-sm text-white transition-all',
                'bg-accent-violet hover:bg-accent-violet/80',
                'disabled:opacity-40 disabled:cursor-not-allowed',
                'flex items-center justify-center gap-2',
              )}
            >
              {launching ? (
                <span className="h-4 w-4 rounded-full border-2 border-white/30 border-t-white animate-spin" />
              ) : (
                <Rocket className="h-4 w-4" />
              )}
              {launching ? 'Launching...' : 'Launch Run'}
            </button>
          </div>
        )}

        {/* ── Chat mode ────────────────────────────────────────────────────── */}
        {mode === 'chat' && (
          <>
            {/* Message list */}
            <div className="flex-1 overflow-y-auto px-5 py-4 space-y-3 min-h-0">
              {messages.map((msg, i) => (
                <ChatBubble
                  key={i}
                  msg={msg}
                  onUseAsGoal={msg.role === 'assistant' ? () => useAsGoal(msg.content) : undefined}
                />
              ))}

              {/* Typing indicator */}
              {chatLoading && (
                <div className="flex gap-2 items-start">
                  <div className="h-6 w-6 rounded-full bg-accent-violet/20 flex items-center justify-center flex-shrink-0 mt-0.5">
                    <Sparkles className="h-3 w-3 text-accent-violet" />
                  </div>
                  <div className="bg-white/[0.05] border border-white/[0.06] rounded-xl rounded-tl-sm px-3 py-2">
                    <span className="flex gap-1 items-center h-4">
                      {[0, 150, 300].map((delay) => (
                        <span
                          key={delay}
                          className="h-1.5 w-1.5 rounded-full bg-zinc-500 animate-bounce"
                          style={{ animationDelay: `${delay}ms` }}
                        />
                      ))}
                    </span>
                  </div>
                </div>
              )}

              <div ref={messagesEndRef} />
            </div>

            {/* Limit reached banner */}
            {atLimit && (
              <div className="mx-5 mb-2 px-3 py-2 rounded-lg bg-accent-amber/10 border border-accent-amber/20">
                <p className="text-xs text-accent-amber">
                  Message limit reached. Click <strong>Use as Goal</strong> on the AI's last reply, then launch your run.
                </p>
              </div>
            )}

            {/* Input row */}
            <div className="border-t border-white/[0.06] px-4 py-3 flex-shrink-0">
              <div className="flex gap-2">
                <input
                  autoFocus
                  type="text"
                  value={draft}
                  onChange={(e) => setDraft(e.target.value)}
                  onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendMessage(); } }}
                  disabled={atLimit || chatLoading}
                  placeholder={atLimit ? 'Max messages reached' : 'Reply…'}
                  className={cn(
                    'flex-1 bg-surface-1 border border-white/[0.08] rounded-lg px-3 py-2',
                    'text-sm text-zinc-200 placeholder:text-zinc-600',
                    'focus:outline-none focus:border-accent-violet/40',
                    'disabled:opacity-40',
                  )}
                />
                <button
                  onClick={sendMessage}
                  disabled={!canSend}
                  className={cn(
                    'px-3 py-2 rounded-lg transition-all flex items-center justify-center',
                    'bg-accent-violet hover:bg-accent-violet/80',
                    'disabled:opacity-30 disabled:cursor-not-allowed',
                  )}
                >
                  <Send className="h-4 w-4 text-white" />
                </button>
              </div>

              {/* Launch from chat */}
              <button
                onClick={handleSubmit}
                disabled={!goal.trim() || launching}
                className={cn(
                  'mt-2 w-full py-2 rounded-lg text-xs font-medium transition-all',
                  'text-zinc-400 hover:text-zinc-200 hover:bg-white/[0.04]',
                  'disabled:opacity-30 disabled:cursor-not-allowed',
                  'flex items-center justify-center gap-1.5',
                )}
              >
                <Rocket className="h-3 w-3" />
                {goal.trim() ? `Launch with current goal` : 'Set a goal first to launch'}
              </button>
            </div>
          </>
        )}
      </motion.div>
    </div>
  );
}

// ── Chat bubble ───────────────────────────────────────────────────────────────

function ChatBubble({
  msg,
  onUseAsGoal,
}: {
  msg: ConversationMessage;
  onUseAsGoal?: () => void;
}) {
  const isUser = msg.role === 'user';

  // Detect a "REQUIREMENT SUMMARY:" section in the AI reply
  const summaryMatch = !isUser
    ? msg.content.match(/REQUIREMENT SUMMARY:\s*([\s\S]+)/i)
    : null;

  return (
    <div className={cn('flex gap-2 items-start', isUser && 'flex-row-reverse')}>
      {/* Avatar */}
      <div className={cn(
        'h-6 w-6 rounded-full flex items-center justify-center flex-shrink-0 mt-0.5',
        isUser ? 'bg-accent-violet/25' : 'bg-white/[0.06]',
      )}>
        {isUser
          ? <span className="text-[9px] font-bold text-accent-violet">You</span>
          : <Sparkles className="h-3 w-3 text-zinc-400" />
        }
      </div>

      {/* Bubble */}
      <div className={cn(
        'max-w-[80%] rounded-xl px-3 py-2 text-sm leading-relaxed',
        isUser
          ? 'bg-accent-violet/15 text-zinc-200 rounded-tr-sm'
          : 'bg-white/[0.05] border border-white/[0.06] text-zinc-300 rounded-tl-sm',
      )}>
        {summaryMatch ? (
          <>
            {/* Text before the summary marker */}
            {msg.content.replace(/REQUIREMENT SUMMARY:[\s\S]*/i, '').trim() && (
              <p className="text-zinc-400 text-xs mb-2 whitespace-pre-wrap">
                {msg.content.replace(/REQUIREMENT SUMMARY:[\s\S]*/i, '').trim()}
              </p>
            )}
            {/* Highlighted summary block */}
            <div className="mt-1 p-2 rounded-lg bg-accent-emerald/10 border border-accent-emerald/20">
              <p className="text-[10px] uppercase tracking-wider text-accent-emerald/70 mb-1 font-semibold">
                Requirement Summary
              </p>
              <p className="text-xs text-zinc-200 whitespace-pre-wrap">{summaryMatch[1].trim()}</p>
            </div>
          </>
        ) : (
          <p className="whitespace-pre-wrap">{msg.content}</p>
        )}

        {/* Use as Goal button on AI messages */}
        {onUseAsGoal && (
          <button
            onClick={onUseAsGoal}
            className={cn(
              'mt-2 text-[11px] px-2 py-0.5 rounded transition-colors',
              summaryMatch
                ? 'bg-accent-emerald/20 text-accent-emerald hover:bg-accent-emerald/30'
                : 'bg-white/[0.06] text-zinc-400 hover:text-zinc-200 hover:bg-white/[0.10]',
            )}
          >
            Use as Goal →
          </button>
        )}
      </div>
    </div>
  );
}
