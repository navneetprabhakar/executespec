import { useEffect, useRef, useState } from 'react';
import { motion } from 'framer-motion';
import { MessageSquare, Send, Loader2, Layers, GitBranch, Bug, Rocket, X } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import * as api from '@/lib/api';
import { req } from '@/lib/api';
import type { ProjectRepoLink } from '@/types/project';
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

const QUICK_ACTIONS = [
  {
    icon: Layers,
    label: 'Project summary',
    prompt: 'Give me a concise summary of what this project does, its main repos, and the features built so far.',
  },
  {
    icon: GitBranch,
    label: 'Architecture',
    prompt: 'Explain the architecture — how the repos in this project interact, key interfaces, and data flow.',
  },
  {
    icon: Bug,
    label: 'Debt + risks',
    prompt: 'Based on the project memory and recent runs, what technical debt and risks should I know about?',
  },
];

export default function ProjectChatPanel({ projectId }: { projectId: string }) {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState('');
  const [loading, setLoading] = useState(false);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [launchOpen, setLaunchOpen] = useState(false);
  const bottomRef = useRef<HTMLDivElement | null>(null);
  const inputRef = useRef<HTMLTextAreaElement | null>(null);
  const navigate = useNavigate();

  useEffect(() => {
    setLoading(true);
    req<ChatMessage[]>(`/projects/${projectId}/chat`)
      .then(setMessages)
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  }, [projectId]);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
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
        body: JSON.stringify({ message: text }),
      });
      setMessages((m) => [...m, reply]);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Chat failed');
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

  return (
    <div className="flex flex-col h-[600px] rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)]">
      {/* Scrollable message list */}
      <div className="flex-1 overflow-y-auto px-4 py-3 space-y-3">
        {loading && (
          <div className="flex items-center gap-2 text-xs text-muted">
            <Loader2 className="h-3 w-3 animate-spin" />
            Loading conversation…
          </div>
        )}

        {!loading && !hasConversation && (
          <div className="flex flex-col items-center justify-center h-full text-center space-y-4 py-8">
            <MessageSquare className="h-8 w-8 text-[var(--text-muted)]" />
            <div>
              <p className="text-sm font-medium text-secondary">Project Chat</p>
              <p className="text-xs text-muted mt-1 max-w-sm leading-relaxed">
                Ask about this project's architecture, past runs, decisions, or debt.
                The assistant answers from project memory + the architecture doc — no fabrication.
              </p>
            </div>
            <div className="flex flex-wrap justify-center gap-2 max-w-md">
              {QUICK_ACTIONS.map(({ icon: Icon, label, prompt }) => (
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
                  <Icon className="h-3.5 w-3.5" />
                  {label}
                </button>
              ))}
            </div>
          </div>
        )}

        {messages.map((m, i) => (
          <motion.div
            key={m.id ?? i}
            initial={{ opacity: 0, y: 6 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.2 }}
            className={cn('flex', m.role === 'USER' ? 'justify-end' : 'justify-start')}
          >
            {m.role === 'USER' ? (
              <div className="max-w-[85%] rounded-sm rounded-br-sm px-4 py-3 text-sm leading-relaxed bg-accent-blue/20 text-primary">
                <p className="whitespace-pre-wrap break-words">{m.content}</p>
              </div>
            ) : (
              <div className="max-w-[90%] rounded-sm rounded-bl-sm px-4 py-3 bg-[var(--gray-300)] border border-[var(--border-default)] text-secondary">
                <MarkdownContent content={m.content} compact />
              </div>
            )}
          </motion.div>
        ))}

        {sending && (
          <div className="flex justify-start">
            <div className="bg-[var(--gray-300)] border border-[var(--border-default)] rounded-sm rounded-bl-sm px-4 py-3">
              <div className="flex items-center gap-2">
                <div className="flex items-center gap-1.5">
                  <span className="h-1.5 w-1.5 bg-accent-blue/60 rounded-full animate-bounce [animation-delay:0ms]" />
                  <span className="h-1.5 w-1.5 bg-accent-blue/60 rounded-full animate-bounce [animation-delay:150ms]" />
                  <span className="h-1.5 w-1.5 bg-accent-blue/60 rounded-full animate-bounce [animation-delay:300ms]" />
                </div>
                <span className="text-[10px] text-muted">Thinking…</span>
              </div>
            </div>
          </div>
        )}

        {error && (
          <div className="mx-auto max-w-md p-3 rounded-sm bg-accent-red/10 border border-accent-red/20 text-xs text-accent-red text-center">
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
            navigate(`/runs/${runId}`);
          }}
        />
      )}

      {/* Input */}
      <div
        className="px-4 py-3 border-t border-[var(--border-default)] flex-shrink-0"
        style={{ paddingBottom: 'max(0.75rem, env(safe-area-inset-bottom))' }}
      >
        <div
          className={cn(
            'flex items-end gap-2 bg-[var(--gray-200)] border rounded-sm px-3 py-2 transition-colors',
            input ? 'border-accent-blue/40' : 'border-[var(--border-default)]',
          )}
        >
          <textarea
            ref={inputRef}
            className="flex-1 bg-transparent text-sm text-primary placeholder-[var(--text-muted)] resize-none focus:outline-none max-h-32 min-h-[24px]"
            placeholder="Ask about this project…"
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
            className="inline-flex items-center gap-1 text-[11px] font-medium px-2.5 py-1.5 rounded-sm border border-accent-blue/40 bg-accent-blue/10 text-accent-blue hover:bg-accent-blue/20 disabled:opacity-40 disabled:cursor-not-allowed flex-shrink-0"
            title="Distil this chat into an execution and launch it"
          >
            <Rocket className="h-3.5 w-3.5" />
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
            title="Send (Enter)"
          >
            {sending ? <Loader2 className="h-4 w-4 animate-spin" /> : <Send className="h-4 w-4" />}
          </button>
        </div>
      </div>
    </div>
  );
}

function LaunchFromChatModal({
  projectId, onClose, onLaunched,
}: {
  projectId: string;
  onClose: () => void;
  onLaunched: (runId: string) => void;
}) {
  const [spec, setSpec] = useState('');
  const [compiling, setCompiling] = useState(true);
  const [repos, setRepos] = useState<ProjectRepoLink[]>([]);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    Promise.all([
      api.compileProjectChatToSpec(projectId),
      api.getProjectRepos(projectId),
    ])
      .then(([{ spec }, linkedRepos]) => {
        if (cancelled) return;
        setSpec(spec);
        setRepos(linkedRepos);
        setSelected(new Set(linkedRepos.map((l) => l.repo.id)));
      })
      .catch((e: Error) => { if (!cancelled) setErr(e.message); })
      .finally(() => { if (!cancelled) setCompiling(false); });
    return () => { cancelled = true; };
  }, [projectId]);

  const toggleRepo = (id: string) => {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  };

  const submit = async () => {
    if (!spec.trim()) { setErr('Spec is empty.'); return; }
    if (selected.size === 0) { setErr('Pick at least one repo.'); return; }
    setSubmitting(true);
    setErr(null);
    try {
      const ids = Array.from(selected);
      const repoUrl = repos
        .find((l) => l.repo.id === ids[0])
        ?.repo.githubRepoFullName;
      const body = {
        userRequest: spec.trim(),
        source: 'GITHUB_EXISTING',
        repoMode: 'EXISTING',
        repoUrl: repoUrl ? `https://github.com/${repoUrl}.git` : undefined,
        skipApproval: true,
        executionMode: 'PROTOTYPE',
        projectId,
        selectedRepoIds: ids,
      };
      const res = await req<{ runId?: string; id?: string }>('/runs', {
        method: 'POST',
        body: JSON.stringify(body),
      });
      const rid = res.runId ?? res.id;
      if (!rid) throw new Error('No runId in response');
      onLaunched(rid);
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Launch failed');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="fixed inset-0 z-40 bg-black/60 flex items-center justify-center" onClick={onClose}>
      <div
        className="w-full max-w-xl rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] shadow-xl p-4 space-y-3"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between">
          <h2 className="text-[13px] font-medium text-primary flex items-center gap-1.5">
            <Rocket className="h-3.5 w-3.5 text-accent-blue" />
            Launch execution from chat
          </h2>
          <button onClick={onClose} className="text-muted hover:text-primary">
            <X className="h-3.5 w-3.5" />
          </button>
        </div>

        {compiling && (
          <div className="flex items-center gap-2 text-[11px] text-muted">
            <Loader2 className="h-3 w-3 animate-spin" />
            Compiling chat into a spec…
          </div>
        )}

        {!compiling && (
          <>
            <div>
              <label className="block text-[10px] uppercase tracking-wide text-muted mb-1">
                Execution spec (editable)
              </label>
              <textarea
                value={spec}
                onChange={(e) => setSpec(e.target.value)}
                rows={7}
                className="w-full text-[12px] font-mono rounded-sm border border-[var(--border-default)] bg-[var(--gray-200)] text-primary px-2 py-1.5 focus:outline-none focus:border-accent-blue resize-y"
              />
            </div>

            <div>
              <label className="block text-[10px] uppercase tracking-wide text-muted mb-1">
                Repos ({selected.size} of {repos.length} selected)
              </label>
              <div className="space-y-1 max-h-40 overflow-y-auto">
                {repos.map((l) => (
                  <label
                    key={l.repo.id}
                    className="flex items-center gap-2 text-[11px] font-mono text-secondary px-2 py-1 rounded-sm border border-[var(--border-default)] bg-[var(--gray-200)] cursor-pointer hover:border-accent-blue/40"
                  >
                    <input
                      type="checkbox"
                      checked={selected.has(l.repo.id)}
                      onChange={() => toggleRepo(l.repo.id)}
                      className="accent-accent-blue"
                    />
                    {l.repo.githubRepoFullName}
                  </label>
                ))}
                {repos.length === 0 && (
                  <div className="text-[11px] text-muted">No repos linked to this project.</div>
                )}
              </div>
            </div>
          </>
        )}

        {err && <div className="text-[11px] text-accent-red">{err}</div>}

        <div className="flex justify-end gap-2 pt-1">
          <button
            onClick={onClose}
            className="text-[11px] px-2.5 py-1 rounded-sm border border-[var(--border-default)] text-muted hover:text-primary"
          >
            Cancel
          </button>
          <button
            onClick={submit}
            disabled={submitting || compiling || !spec.trim() || selected.size === 0}
            className="inline-flex items-center gap-1 text-[11px] font-medium px-2.5 py-1 rounded-sm border border-accent-blue/40 bg-accent-blue/10 text-accent-blue hover:bg-accent-blue/20 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {submitting
              ? <><Loader2 className="h-3 w-3 animate-spin" /> Launching…</>
              : <><Rocket className="h-3 w-3" /> Launch</>}
          </button>
        </div>
      </div>
    </div>
  );
}
