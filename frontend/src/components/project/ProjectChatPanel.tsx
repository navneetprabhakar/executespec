import {useEffect, useRef, useState} from 'react';
import {motion} from 'framer-motion';
import {
  MessageSquare,
  Send,
  Loader2,
  Layers,
  GitBranch,
  Bug,
  Rocket,
  X,
  RotateCcw,
  Copy,
  Check,
  Archive,
} from 'lucide-react';
import {useNavigate} from 'react-router-dom';
import * as api from '@/lib/api';
import {
  req,
  clearProjectChat,
  compactProjectChat,
  getProjectChatInfo,
  type ProjectChatInfo,
} from '@/lib/api';
import type {ProjectRepoLink} from '@/types/project';
import {MarkdownContent} from '@/components/shared/MarkdownContent';
import {cn, errMsg} from '@/lib/utils';

interface ChatMessage {
  id: string;
  role: 'USER' | 'ASSISTANT';
  content: string;
  tokensIn?: number | null;
  tokensOut?: number | null;
  createdAt: string;
}

const QUICK_ACTIONS = [
  {
    icon: Layers,
    label: 'Project summary',
    prompt:
      'Give me a concise summary of what this project does, its main repos, and the features built so far.',
  },
  {
    icon: GitBranch,
    label: 'Architecture',
    prompt:
      'Explain the architecture — how the repos in this project interact, key interfaces, and data flow.',
  },
  {
    icon: Bug,
    label: 'Debt + risks',
    prompt:
      'Based on the project memory and recent runs, what technical debt and risks should I know about?',
  },
];

/**
 * Last-resort context window when the chat-info round-trip hasn't returned yet. 128k is a safe
 * lower bound on every frontier model we ship today, so the chip degrades to "128k window"
 * rather than blank during the brief in-flight window.
 */
const FALLBACK_CONTEXT_WINDOW = 128_000;

/** Format a token count as `1.2k` / `12.3k` / `123k`. */
function formatTokens(n: number): string {
  if (n < 1000) return `${n}`;
  if (n < 10_000) return `${(n / 1000).toFixed(1)}k`;
  if (n < 1_000_000) return `${Math.round(n / 1000)}k`;
  return `${(n / 1_000_000).toFixed(1)}M`;
}

export default function ProjectChatPanel({
  projectId,
  aiProvider: _aiProvider,
}: {
  projectId: string;
  /**
   * Kept for back-compat — the panel now fetches the resolved model + window via
   * `getProjectChatInfo(projectId)` so the chip reflects the actual model the chat answers
   * with (gpt-4o = 128k vs gpt-5.4 = 256k vs Sonnet 200k vs the 1M-context Opus variant), not a
   * provider-level guess.
   */
  aiProvider?: string | null;
}) {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState('');
  const [loading, setLoading] = useState(false);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [launchOpen, setLaunchOpen] = useState(false);
  const [resetting, setResetting] = useState(false);
  const [compacting, setCompacting] = useState(false);
  const [copiedId, setCopiedId] = useState<string | null>(null);
  // 2026-05-02 — resolved model + context window driven by the backend so the chip is
  // model-accurate (gpt-4o=128k vs gpt-5.4=256k, Sonnet=200k vs Opus-1M=1M, etc).
  const [chatInfo, setChatInfo] = useState<ProjectChatInfo | null>(null);
  const bottomRef = useRef<HTMLDivElement | null>(null);
  const inputRef = useRef<HTMLTextAreaElement | null>(null);
  const copyResetTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const navigate = useNavigate();

  const handleCopy = async (messageId: string, content: string) => {
    try {
      await navigator.clipboard.writeText(content);
      setCopiedId(messageId);
      if (copyResetTimer.current) clearTimeout(copyResetTimer.current);
      copyResetTimer.current = setTimeout(() => setCopiedId(null), 1500);
    } catch {
      // navigator.clipboard can reject under non-secure contexts — silently
      // ignore; the user just won't see the "Copied" confirmation.
    }
  };

  useEffect(
    () => () => {
      if (copyResetTimer.current) clearTimeout(copyResetTimer.current);
    },
    [],
  );

  // Fetch resolved-model metadata once per mount / projectId switch. Best-effort: any failure
  // leaves chatInfo null and the chip silently falls back to the FALLBACK_CONTEXT_WINDOW guess.
  useEffect(() => {
    let cancelled = false;
    if (!projectId) return;
    getProjectChatInfo(projectId)
      .then((info) => {
        if (!cancelled) setChatInfo(info);
      })
      .catch(() => {
        /* surfaced via chip fallback; no toast */
      });
    return () => {
      cancelled = true;
    };
  }, [projectId]);

  const handleResetHistory = async () => {
    if (resetting) return;
    const confirmed = window.confirm(
      'Reset chat history? This deletes every message in this conversation. ' +
        'Project memory + architecture are kept — only the chat transcript is cleared.',
    );
    if (!confirmed) return;
    setResetting(true);
    setError(null);
    try {
      await clearProjectChat(projectId);
      setMessages([]);
    } catch (e) {
      setError(e instanceof Error ? errMsg(e) : 'Reset failed');
    } finally {
      setResetting(false);
    }
  };

  const handleCompact = async () => {
    if (compacting) return;
    setCompacting(true);
    setError(null);
    try {
      const result = await compactProjectChat(projectId);
      // Refetch instead of reconstructing client-side — the server is the
      // source of truth for the new summary message + message ids.
      const refreshed = await req<ChatMessage[]>(`/projects/${projectId}/chat`);
      setMessages(refreshed);
      // Surface a brief confirmation via the existing error slot (it doubles
      // as a status banner for transient flows; the styling is intentionally
      // neutral when the message starts with "Compacted".
      setError(
        `Compacted ${result.compactedFrom} earlier message${result.compactedFrom === 1 ? '' : 's'} into a summary · ${result.totalAfter} now in conversation`,
      );
      setTimeout(() => {
        setError((curr) => (curr && curr.startsWith('Compacted') ? null : curr));
      }, 4000);
    } catch (e) {
      setError(e instanceof Error ? errMsg(e) : 'Compact failed');
    } finally {
      setCompacting(false);
    }
  };

  useEffect(() => {
    setLoading(true);
    req<ChatMessage[]>(`/projects/${projectId}/chat`)
      .then(setMessages)
      .catch((e: Error) => setError(errMsg(e)))
      .finally(() => setLoading(false));
  }, [projectId]);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({behavior: 'smooth'});
  }, [messages, sending]);

  const handleSend = async (override?: string) => {
    const text = (override ?? input).trim();
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
    if (!override) {
      setInput('');
      if (inputRef.current) inputRef.current.style.height = 'auto';
    }
    try {
      const reply = await req<ChatMessage>(`/projects/${projectId}/chat`, {
        method: 'POST',
        body: JSON.stringify({message: text}),
      });
      setMessages((m) => [...m, reply]);
    } catch (e) {
      setError(e instanceof Error ? errMsg(e) : 'Chat failed');
    } finally {
      setSending(false);
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  };

  const hasConversation = messages.length > 0;

  // 2026-05-02 — surface chat-specific token totals + the model's context
  // window so the user can gauge how close they are to needing a Compact.
  // Tokens are summed across every persisted message (input + output);
  // window comes from the static provider map above. Both render only when
  // the conversation has at least one round-trip with token metadata —
  // before that, "0 / 200k" is noise.
  const totalIn = messages.reduce((acc, m) => acc + (m.tokensIn ?? 0), 0);
  const totalOut = messages.reduce((acc, m) => acc + (m.tokensOut ?? 0), 0);
  const totalTokens = totalIn + totalOut;
  const contextWindow = chatInfo?.contextWindow ?? FALLBACK_CONTEXT_WINDOW;
  const tokenPercent = contextWindow > 0 ? Math.min(100, (totalIn / contextWindow) * 100) : 0;

  return (
    <div className='flex flex-col h-full min-h-0 bg-[var(--gray-100)]'>
      {/* Slim header — only when conversation exists, so the empty state stays clean */}
      {hasConversation && (
        <div className='flex items-center justify-between px-4 py-2 border-b border-[var(--border-default)] flex-shrink-0'>
          <div className='flex items-center gap-1.5 text-[11px] font-medium text-secondary'>
            <MessageSquare className='h-3 w-3 text-accent-blue' />
            Project Chat
            <span className='text-muted'>
              · {messages.length} message{messages.length === 1 ? '' : 's'}
            </span>
            {totalTokens > 0 && (
              <>
                <span
                  className='text-muted'
                  title={`Input tokens: ${totalIn.toLocaleString()} · Output tokens: ${totalOut.toLocaleString()} · Cumulative across this conversation`}
                >
                  · {formatTokens(totalIn)} in / {formatTokens(totalOut)} out
                </span>
                <span
                  className={cn(
                    'text-muted',
                    tokenPercent >= 80 && 'text-accent-amber',
                    tokenPercent >= 95 && 'text-accent-red',
                  )}
                  title={
                    `Model: ${chatInfo?.model ?? 'resolving…'}` +
                    (chatInfo?.provider ? ` (${chatInfo.provider.toLowerCase()})` : '') +
                    `\nContext window: ${formatTokens(contextWindow)} tokens` +
                    `\nCumulative input is ${tokenPercent.toFixed(0)}% of the window — ` +
                    `the chat windows to the last 20 messages so older turns drop off ` +
                    `automatically, but Compact rewrites the trailing tail into a single ` +
                    `summary when answers start drifting.`
                  }
                >
                  · {formatTokens(contextWindow)} window
                </span>
              </>
            )}
          </div>
          <div className='flex items-center gap-1.5'>
            <button
              onClick={handleCompact}
              disabled={compacting || resetting || sending || messages.length < 10}
              className={cn(
                'inline-flex items-center gap-1 text-[11px] px-2 py-1 rounded-sm transition-colors',
                'border border-[var(--border-default)] text-muted hover:text-accent-blue hover:border-accent-blue/40',
                (compacting || resetting || sending || messages.length < 10) &&
                  'opacity-40 cursor-not-allowed',
              )}
              title={
                messages.length < 10
                  ? 'Compact unavailable — need at least 10 messages of history.'
                  : 'Summarise earlier messages into one note while keeping the recent tail. Use this when answers start drifting.'
              }
            >
              {compacting ? (
                <Loader2 className='h-3 w-3 animate-spin' />
              ) : (
                <Archive className='h-3 w-3' />
              )}
              Compact
            </button>
            <button
              onClick={handleResetHistory}
              disabled={resetting || compacting || sending}
              className={cn(
                'inline-flex items-center gap-1 text-[11px] px-2 py-1 rounded-sm transition-colors',
                'border border-[var(--border-default)] text-muted hover:text-accent-red hover:border-accent-red/40',
                (resetting || compacting || sending) && 'opacity-40 cursor-not-allowed',
              )}
              title='Clear chat history (project memory + architecture are preserved)'
            >
              {resetting ? (
                <Loader2 className='h-3 w-3 animate-spin' />
              ) : (
                <RotateCcw className='h-3 w-3' />
              )}
              Reset
            </button>
          </div>
        </div>
      )}
      {/* Scrollable message list */}
      <div className='flex-1 min-h-0 overflow-y-auto px-4 py-3 space-y-3'>
        {loading && (
          <div className='flex items-center gap-2 text-xs text-muted'>
            <Loader2 className='h-3 w-3 animate-spin' />
            Loading conversation…
          </div>
        )}

        {!loading && !hasConversation && (
          <div className='flex flex-col items-center justify-center h-full text-center space-y-4 py-8'>
            <MessageSquare className='h-8 w-8 text-[var(--text-muted)]' />
            <div>
              <p className='text-sm font-medium text-secondary'>Project Chat</p>
              <p className='text-xs text-muted mt-1 max-w-sm leading-relaxed'>
                Ask about this project's architecture, past runs, decisions, or debt. The assistant
                answers from project memory + the architecture doc — no fabrication.
              </p>
            </div>
            <div className='flex flex-wrap justify-center gap-2 max-w-md'>
              {QUICK_ACTIONS.map(({icon: Icon, label, prompt}) => (
                <button
                  key={label}
                  onClick={() => handleSend(prompt)}
                  disabled={sending}
                  className={cn(
                    'flex items-center gap-1.5 px-3 py-2 rounded-sm text-xs transition-all',
                    'bg-[var(--gray-200)] border border-[var(--border-default)] text-secondary',
                    'hover:bg-[var(--border-default)] hover:text-primary hover:border-accent-blue/30',
                    sending && 'opacity-50 cursor-not-allowed',
                  )}
                >
                  <Icon className='h-3.5 w-3.5' />
                  {label}
                </button>
              ))}
            </div>
          </div>
        )}

        {messages.map((m, i) => {
          const messageKey = m.id ?? `idx-${i}`;
          const isCopied = copiedId === messageKey;
          return (
            <motion.div
              key={messageKey}
              initial={{opacity: 0, y: 6}}
              animate={{opacity: 1, y: 0}}
              transition={{duration: 0.2}}
              className={cn(
                'group flex flex-col gap-1',
                m.role === 'USER' ? 'items-end' : 'items-start',
              )}
            >
              {m.role === 'USER' ? (
                <div className='max-w-[85%] rounded-sm rounded-br-sm px-4 py-3 text-sm leading-relaxed bg-accent-blue/20 text-primary'>
                  <p className='whitespace-pre-wrap break-words'>{m.content}</p>
                </div>
              ) : (
                <div className='max-w-[90%] rounded-sm rounded-bl-sm px-4 py-3 bg-[var(--gray-300)] border border-[var(--border-default)] text-secondary'>
                  <MarkdownContent content={m.content} compact />
                </div>
              )}
              <button
                onClick={() => handleCopy(messageKey, m.content)}
                className={cn(
                  'inline-flex items-center gap-1 text-[10.5px] px-1.5 py-0.5 rounded-sm transition-all',
                  'text-muted hover:text-secondary',
                  // Stays visible while showing the "Copied" confirmation;
                  // otherwise reveals on hover/focus-within of the message.
                  isCopied
                    ? 'opacity-100 text-accent-blue'
                    : 'opacity-0 group-hover:opacity-100 focus:opacity-100',
                )}
                title='Copy message'
                aria-label={isCopied ? 'Copied' : 'Copy message'}
              >
                {isCopied ? <Check className='h-3 w-3' /> : <Copy className='h-3 w-3' />}
                {isCopied ? 'Copied' : 'Copy'}
              </button>
            </motion.div>
          );
        })}

        {sending && (
          <div className='flex justify-start'>
            <div className='bg-[var(--gray-300)] border border-[var(--border-default)] rounded-sm rounded-bl-sm px-4 py-3'>
              <div className='flex items-center gap-2'>
                <div className='flex items-center gap-1.5'>
                  <span className='h-1.5 w-1.5 bg-accent-blue/60 rounded-full animate-bounce [animation-delay:0ms]' />
                  <span className='h-1.5 w-1.5 bg-accent-blue/60 rounded-full animate-bounce [animation-delay:150ms]' />
                  <span className='h-1.5 w-1.5 bg-accent-blue/60 rounded-full animate-bounce [animation-delay:300ms]' />
                </div>
                <span className='text-[11px] text-muted'>Thinking…</span>
              </div>
            </div>
          </div>
        )}

        {error && (
          <div className='mx-auto max-w-md p-3 rounded-sm bg-accent-red/10 border border-accent-red/20 text-xs text-accent-red text-center'>
            {error}
          </div>
        )}

        <div ref={bottomRef} />
      </div>

      {launchOpen && (
        <LaunchFromChatModal
          projectId={projectId}
          onClose={() => setLaunchOpen(false)}
          onLaunched={(runId) => {
            setLaunchOpen(false);
            // 2026-05-04 — land on the V3 RunDetailPage for the just-created run instead of
            // bouncing back to the project dashboard. User feedback (P0): "new launch
            // submitted, no loader screen in chat, no redirection, no intimation of any kind"
            // — the dashboard redirect was effectively invisible because the user was already
            // on the dashboard's chat tab; landing on the run detail puts them on the page that
            // visualises the run's live progress (PLANNING → WORKING → REVIEWING → COMPLETE).
            navigate(`/projects/${projectId}/runs/${runId}`, {state: {launchedRunId: runId}});
          }}
        />
      )}

      {/* Input */}
      <div
        className='px-4 py-3 border-t border-[var(--border-default)] flex-shrink-0'
        style={{paddingBottom: 'max(0.75rem, env(safe-area-inset-bottom))'}}
      >
        <div
          className={cn(
            'flex items-end gap-2 bg-[var(--gray-200)] border rounded-sm px-3 py-2 transition-colors',
            input ? 'border-accent-blue/40' : 'border-[var(--border-default)]',
          )}
        >
          <textarea
            ref={inputRef}
            className='flex-1 bg-transparent text-sm text-primary placeholder-[var(--text-muted)] resize-none focus:outline-none max-h-32 min-h-[24px]'
            placeholder='Ask about this project…'
            rows={1}
            value={input}
            onChange={(e) => {
              setInput(e.target.value);
              e.target.style.height = 'auto';
              e.target.style.height = `${Math.min(e.target.scrollHeight, 128)}px`;
            }}
            onKeyDown={handleKeyDown}
            disabled={sending}
          />
          <button
            onClick={() => setLaunchOpen(true)}
            disabled={sending || messages.length === 0}
            className='inline-flex items-center gap-1 text-xs font-medium px-2.5 py-1.5 rounded-sm border border-accent-blue/40 bg-accent-blue/10 text-accent-blue hover:bg-accent-blue/20 disabled:opacity-40 disabled:cursor-not-allowed flex-shrink-0'
            title='Distil this chat into an execution and launch it'
          >
            <Rocket className='h-3.5 w-3.5' />
            Launch
          </button>
          <button
            onClick={() => handleSend()}
            disabled={!input.trim() || sending}
            className={cn(
              'p-2.5 min-h-[44px] min-w-[44px] flex items-center justify-center rounded-sm flex-shrink-0 transition-all',
              input.trim() && !sending
                ? 'bg-accent-blue text-primary hover:bg-accent-blue/90'
                : 'text-muted cursor-not-allowed',
            )}
            title='Send (Enter)'
          >
            {sending ? <Loader2 className='h-4 w-4 animate-spin' /> : <Send className='h-4 w-4' />}
          </button>
        </div>
      </div>
    </div>
  );
}

function LaunchFromChatModal({
  projectId,
  onClose,
  onLaunched,
}: {
  projectId: string;
  onClose: () => void;
  onLaunched: (runId: string) => void;
}) {
  const [spec, setSpec] = useState('');
  const [compiling, setCompiling] = useState(true);
  const [repos, setRepos] = useState<ProjectRepoLink[]>([]);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [branch, setBranch] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    Promise.all([api.compileProjectChatToSpec(projectId), api.getProjectRepos(projectId)])
      .then(([{spec}, linkedRepos]) => {
        if (cancelled) return;
        setSpec(spec);
        setRepos(linkedRepos);
        setSelected(new Set(linkedRepos.map((l) => l.repo.id)));
      })
      .catch((e: Error) => {
        if (!cancelled) setErr(errMsg(e));
      })
      .finally(() => {
        if (!cancelled) setCompiling(false);
      });
    return () => {
      cancelled = true;
    };
  }, [projectId]);

  const toggleRepo = (id: string) => {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const submit = async () => {
    if (!spec.trim()) {
      setErr('Spec is empty.');
      return;
    }
    if (selected.size === 0) {
      setErr('Pick at least one repo.');
      return;
    }
    setSubmitting(true);
    setErr(null);
    try {
      const ids = Array.from(selected);
      const repoUrl = repos.find((l) => l.repo.id === ids[0])?.repo.githubRepoFullName;
      const body: Record<string, unknown> = {
        userRequest: spec.trim(),
        source: 'GITHUB_EXISTING',
        repoMode: 'EXISTING',
        repoUrl: repoUrl ? `https://github.com/${repoUrl}.git` : undefined,
        skipApproval: true,
        executionMode: 'PROTOTYPE',
        projectId,
        selectedRepoIds: ids,
      };
      const trimmedBranch = branch.trim();
      if (trimmedBranch) body.branch = trimmedBranch;
      const res = await req<{runId?: string; id?: string}>('/runs', {
        method: 'POST',
        body: JSON.stringify(body),
      });
      const rid = res.runId ?? res.id;
      if (!rid) throw new Error('No runId in response');
      onLaunched(rid);
    } catch (e) {
      setErr(e instanceof Error ? errMsg(e) : 'Launch failed');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div
      className='fixed inset-0 z-40 bg-black/60 flex items-center justify-center'
      onClick={onClose}
    >
      <div
        className='w-full max-w-xl rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] shadow-xl p-4 space-y-3'
        onClick={(e) => e.stopPropagation()}
      >
        <div className='flex items-center justify-between'>
          <h2 className='text-[13px] font-medium text-primary flex items-center gap-1.5'>
            <Rocket className='h-3.5 w-3.5 text-accent-blue' />
            Launch execution from chat
          </h2>
          <button onClick={onClose} className='text-muted hover:text-primary'>
            <X className='h-3.5 w-3.5' />
          </button>
        </div>

        {compiling && (
          <div className='flex items-center gap-2 text-xs text-muted'>
            <Loader2 className='h-3 w-3 animate-spin' />
            Compiling chat into a spec…
          </div>
        )}

        {/*
         * 2026-05-04 — overlay banner that fires the moment the user clicks Launch. The button
         * already shows a small spinner, but submit can take 5-15s (POST /api/runs with
         * skipApproval=true clones the workspace + classifies + persists before returning), and
         * the user feedback was "no loader screen, no redirection, no intimation of any kind".
         * This banner gives a clear in-flight signal AND pre-announces the redirect so the
         * subsequent navigation doesn't feel like the page was lost.
         */}
        {submitting && (
          <div
            className='flex items-center gap-2 rounded-sm border border-accent-blue/40 bg-accent-blue/5 px-2.5 py-2 text-[11px] text-accent-blue'
            role='status'
            aria-live='polite'
          >
            <Loader2 className='h-3.5 w-3.5 animate-spin shrink-0' />
            <span className='flex-1'>
              Submitting your launch — cloning {selected.size} repo{selected.size === 1 ? '' : 's'}{' '}
              + classifying. You'll land on the run page in a few seconds.
            </span>
          </div>
        )}

        {!compiling && (
          <>
            <div>
              <label className='block text-[11px] uppercase tracking-wide text-muted mb-1'>
                Execution spec (editable)
              </label>
              <textarea
                value={spec}
                onChange={(e) => setSpec(e.target.value)}
                rows={7}
                className='w-full text-sm font-mono rounded-sm border border-[var(--border-default)] bg-[var(--gray-200)] text-primary px-2 py-1.5 focus:outline-none focus:border-accent-blue resize-y'
              />
            </div>

            <div>
              <label className='block text-[11px] uppercase tracking-wide text-muted mb-1'>
                Branch{' '}
                <span className='normal-case tracking-normal text-muted'>
                  (optional — leave blank for each repo's default)
                </span>
              </label>
              <input
                type='text'
                value={branch}
                onChange={(e) => setBranch(e.target.value)}
                placeholder='main'
                title="Re-launch on an existing branch (e.g. the PR branch you want to address review comments on, or a feature branch you're staging changes onto). Applied to every selected repo."
                className='w-full text-sm font-mono rounded-sm border border-[var(--border-default)] bg-[var(--gray-200)] text-primary placeholder:text-muted px-2 py-1.5 focus:outline-none focus:border-accent-blue'
              />
              <p className='text-[10.5px] text-muted mt-1'>
                Use cases — addressing PR review comments (paste the PR branch), or stacking a
                staged launch on an in-progress branch.
              </p>
            </div>

            <div>
              <label className='block text-[11px] uppercase tracking-wide text-muted mb-1'>
                Repos ({selected.size} of {repos.length} selected)
              </label>
              <div className='space-y-1 max-h-40 overflow-y-auto'>
                {repos.map((l) => (
                  <label
                    key={l.repo.id}
                    className='flex items-center gap-2 text-xs font-mono text-secondary px-2 py-1 rounded-sm border border-[var(--border-default)] bg-[var(--gray-200)] cursor-pointer hover:border-accent-blue/40'
                  >
                    <input
                      type='checkbox'
                      checked={selected.has(l.repo.id)}
                      onChange={() => toggleRepo(l.repo.id)}
                      className='accent-accent-blue'
                    />
                    {l.repo.githubRepoFullName}
                  </label>
                ))}
                {repos.length === 0 && (
                  <div className='text-xs text-muted'>No repos linked to this project.</div>
                )}
              </div>
            </div>
          </>
        )}

        {err && <div className='text-xs text-accent-red'>{err}</div>}

        <div className='flex justify-end gap-2 pt-1'>
          <button
            onClick={onClose}
            className='text-xs px-2.5 py-1 rounded-sm border border-[var(--border-default)] text-muted hover:text-primary'
          >
            Cancel
          </button>
          <button
            onClick={submit}
            disabled={submitting || compiling || !spec.trim() || selected.size === 0}
            className='inline-flex items-center gap-1 text-xs font-medium px-2.5 py-1 rounded-sm border border-accent-blue/40 bg-accent-blue/10 text-accent-blue hover:bg-accent-blue/20 disabled:opacity-50 disabled:cursor-not-allowed'
          >
            {submitting ? (
              <>
                <Loader2 className='h-3 w-3 animate-spin' /> Launching…
              </>
            ) : (
              <>
                <Rocket className='h-3 w-3' /> Launch
              </>
            )}
          </button>
        </div>
      </div>
    </div>
  );
}
