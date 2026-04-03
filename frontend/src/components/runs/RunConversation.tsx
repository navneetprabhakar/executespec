import { useEffect, useRef, useState } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { Send, Loader2, Rocket, MessageSquare, Bug, Lightbulb, Code2, Wrench, FileText, Maximize2, Minimize2, AlertTriangle, Shield } from 'lucide-react';
import { getRunChat, sendRunChatMessage, applyToRun, getValidationReport } from '@/lib/api';
import type { RunChatMessage, ChatResponse, ValidationReportData, ReportIssue } from '@/lib/api';
import { cn } from '@/lib/utils';
import { useRunStore } from '@/stores/useRunStore';
import { useUiStore } from '@/stores/useUiStore';
import { useTierPermissions } from '@/hooks/useTierPermissions';
import { MarkdownContent } from '@/components/shared/MarkdownContent';

const MAX_MESSAGES = 50;

interface Props {
  runId: string;
}

/** Quick-action prompt suggestions shown in empty state. */
const QUICK_ACTIONS = [
  { icon: Bug, label: 'Debug preview', prompt: 'Why is the preview failing? Check for port mismatches, missing dependencies, or build errors.' },
  { icon: Lightbulb, label: 'Suggest improvements', prompt: 'Analyze the generated code and suggest the top 3 improvements for code quality, security, and performance.' },
  { icon: Code2, label: 'Explain architecture', prompt: 'Explain the architecture of this project — how the frontend and backend interact, the data flow, and key design decisions.' },
];

/** Build a prompt from unresolved validation issues for the copilot. */
function buildIssuesPrompt(issues: ReportIssue[]): string {
  if (issues.length === 0) return '';
  let prompt = 'The validation report found the following unresolved issues that the auto-fix agent could not resolve. Please analyze each issue and provide the exact code fixes needed:\n\n';
  issues.forEach((issue, i) => {
    prompt += `${i + 1}. **[${issue.severity.replace(/_/g, ' ')}]** ${issue.description}`;
    if (issue.filePath) prompt += ` (in \`${issue.filePath}\`)`;
    if (issue.fix) prompt += `\n   Suggested fix: ${issue.fix}`;
    prompt += '\n\n';
  });
  prompt += 'Please provide the corrected code for each issue.';
  return prompt;
}

/**
 * Post-run conversational AI chat panel.
 *
 * - Shows the conversation history with markdown rendering for assistant replies.
 * - Allows the user to ask up to MAX_MESSAGES questions about the generated code.
 * - Offers quick-action chips for common debugging/improvement tasks.
 * - Offers an "Iterate as New Run" button to create an iteration run.
 */
export default function RunConversation({ runId }: Props) {
  const permissions = useTierPermissions();
  const [messages, setMessages]         = useState<RunChatMessage[]>([]);
  const [userCount, setUserCount]       = useState(0);
  const [input, setInput]               = useState('');
  const [sending, setSending]           = useState(false);
  const [iterating, setIterating]       = useState(false);
  const [iterateInput, setIterateInput] = useState('');
  const [showIterateBox, setShowIterateBox] = useState(false);
  const [applying, setApplying]         = useState(false);
  const [showApplyBox, setShowApplyBox] = useState(false);
  const [applyInput, setApplyInput]     = useState('');
  const [error, setError]               = useState<string | null>(null);
  const [loading, setLoading]           = useState(true);
  const [compiledSpec, setCompiledSpec] = useState<string | null>(null);
  const [showCompiledSpec, setShowCompiledSpec] = useState(false);
  const [validationReport, setValidationReport] = useState<ValidationReportData | null>(null);
  const [unresolvedIssues, setUnresolvedIssues] = useState<ReportIssue[]>([]);
  const [applyingFixes, setApplyingFixes] = useState(false);
  const bottomRef = useRef<HTMLDivElement>(null);
  const inputRef  = useRef<HTMLTextAreaElement>(null);
  const { setActiveRunId, fetchRuns } = useRunStore();
  const activeRun = useRunStore((s) => s.activeRun);
  const { addToast, setPendingNewRunGoal } = useUiStore();
  const copilotMaximized = useUiStore((s) => s.copilotMaximized);
  const setCopilotMaximized = useUiStore((s) => s.setCopilotMaximized);

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

  // Fetch validation report when run is terminal — provides context for "Fix Issues" actions
  useEffect(() => {
    if (!activeRun) return;
    const isTerminal = ['COMPLETE', 'PARTIAL', 'FAILED'].includes(activeRun.status);
    if (!isTerminal) return;
    getValidationReport(activeRun.runId).then((report) => {
      if (!report) return;
      setValidationReport(report);
      // Filter to unresolved issues: those in NEEDS_ATTENTION reports
      if (report.pipelineResult === 'NEEDS_ATTENTION' || report.pipelineResult === 'FAILED') {
        setUnresolvedIssues(report.issues.filter((issue) => !!issue.fix));
      }
    });
  }, [activeRun?.runId, activeRun?.status]);

  const limitReached = userCount >= MAX_MESSAGES;
  const hasConversation = messages.length > 0;

  const handleSend = async (text?: string) => {
    const msg = (text ?? input).trim();
    if (!msg || sending || limitReached) return;

    setInput('');
    setSending(true);
    setError(null);

    // Reset textarea height
    if (inputRef.current) {
      inputRef.current.style.height = 'auto';
    }

    // Optimistically add the user message
    const optimisticMsg: RunChatMessage = { role: 'user', content: msg };
    setMessages((prev) => [...prev, optimisticMsg]);

    try {
      const resp: ChatResponse = await sendRunChatMessage(runId, msg);
      const assistantMsg: RunChatMessage = { role: 'assistant', content: resp.reply };
      setMessages((prev) => [...prev.slice(0, -1), optimisticMsg, assistantMsg]);
      setUserCount(resp.messageCount);
    } catch (e: any) {
      // Remove the optimistic message on failure
      setMessages((prev) => prev.slice(0, -1));
      if (e.message?.includes('429')) {
        setError('Conversation limit reached. Click "Iterate as New Run" to continue.');
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

  const handleIterate = () => {
    const req = iterateInput.trim() || buildSuggestedRequest();
    if (!req) return;

    // Open NewRunModal pre-filled with the goal instead of launching directly
    setPendingNewRunGoal(req);
    setShowIterateBox(false);
  };

  const handleApply = async (eOrText?: any) => {
    const suggestionOverride = typeof eOrText === 'string' ? eOrText : undefined;
    const suggestion = (suggestionOverride ?? applyInput).trim() || buildSuggestedRequest();
    if (!suggestion || applying) return;

    setApplying(true);
    setError(null);
    try {
      const result = await applyToRun(runId, suggestion, 'COPILOT');
      setShowApplyBox(false);
      setApplyInput('');
      addToast({ type: 'success', message: `Applied ${result.taskCount} task${result.taskCount !== 1 ? 's' : ''} to run` });
      // Refresh the run to show new tasks
      useRunStore.getState().fetchRun(runId);
    } catch (e: any) {
      const msg = e.message ?? '';
      if (msg.includes('422')) {
        // Too many tasks — extract compiled goal from error response
        try {
          const body = JSON.parse(msg.split(': ').slice(1).join(': '));
          if (body?.compiledGoal) {
            setShowApplyBox(false);
            setShowIterateBox(true);
            setIterateInput(body.compiledGoal);
            addToast({ type: 'info', message: 'Suggestion too large for apply. Use "Iterate as New Run" to start fresh.' });
          } else {
            setError('Suggestion requires too many tasks. Please create a fresh run instead.');
          }
        } catch {
          setError('Suggestion requires too many tasks. Please create a fresh run instead.');
        }
      } else if (msg.includes('429')) {
        setError('Apply limit reached. Create a fresh run instead.');
      } else {
        setError(msg || 'Failed to apply suggestion. Please try again.');
      }
    } finally {
      setApplying(false);
    }
  };

  /** Build a suggested request from the last assistant message's improvement suggestions. */
  const buildSuggestedRequest = (): string => {
    // Check last assistant message for improvement suggestions
    const lastAssistant = [...messages].reverse().find((m) => m.role === 'assistant');
    if (lastAssistant?.content) {
      // Extract improvement goals if present
      const improvementMatch = lastAssistant.content.match(/\*\*Improvement:\*\*\s*(.+)/);
      if (improvementMatch) return improvementMatch[1].trim();
    }
    // Fallback: use the last user message
    const lastUserMsg = [...messages].reverse().find((m) => m.role === 'user');
    return lastUserMsg?.content ?? '';
  };

  /** Compile all discussed improvements into a consolidated spec message. */
  const compileSpec = () => {
    const assistantMsgs = messages.filter((m) => m.role === 'assistant');
    if (assistantMsgs.length === 0) return;

    const userMsgs = messages.filter((m) => m.role === 'user');
    const original = activeRun?.userRequest ?? '';

    let spec = `**Original Goal:** ${original}\n\n**Improvements discussed:**\n`;
    userMsgs.forEach((m, i) => {
      spec += `${i + 1}. ${m.content.slice(0, 200)}${m.content.length > 200 ? '...' : ''}\n`;
    });

    // Extract key suggestions from assistant responses
    spec += `\n**AI Recommendations:**\n`;
    assistantMsgs.forEach((m) => {
      const lines = m.content.split('\n').filter((l) =>
        /^\s*[-*\d]/.test(l) || /\*\*/.test(l)
      );
      if (lines.length > 0) {
        spec += lines.slice(0, 5).join('\n') + '\n';
      }
    });

    setCompiledSpec(spec.trim());
    setShowCompiledSpec(true);
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center h-full">
        <Loader2 className="h-4 w-4 animate-spin text-muted" />
      </div>
    );
  }

  return (
    <div className={cn(
      'flex flex-col h-full',
      copilotMaximized && 'fixed inset-0 z-50 bg-[var(--geist-bg)]'
    )}>

      {/* Header */}
      <div className="flex flex-col gap-2 px-4 py-3 border-b border-[var(--border-default)] flex-shrink-0">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <MessageSquare className="h-4 w-4 text-accent-blue" />
            <span className="text-sm font-medium text-primary">Run Copilot</span>
            <span className="text-[10px] text-muted font-mono">sonnet</span>
            <button
              onClick={() => setCopilotMaximized(!copilotMaximized)}
              className="ml-1 p-1.5 min-h-[36px] min-w-[36px] flex items-center justify-center rounded text-muted hover:text-secondary hover:bg-[var(--gray-200)] transition-colors"
              title={copilotMaximized ? 'Minimize' : 'Maximize'}
            >
              {copilotMaximized ? <Minimize2 className="h-3.5 w-3.5" /> : <Maximize2 className="h-3.5 w-3.5" />}
            </button>
          </div>
          {/* Message counter */}
          <span className={cn(
            'text-xs font-mono tabular-nums',
            userCount >= MAX_MESSAGES - 2 ? 'text-accent-red' :
            userCount >= MAX_MESSAGES / 2   ? 'text-accent-amber' :
                                              'text-muted'
          )}>
            {userCount}/{MAX_MESSAGES}
          </span>
        </div>

        {/* Action buttons — wrap on mobile */}
        <div className="flex items-center gap-2 flex-wrap">
          {/* Apply to Run button — only when run is COMPLETE/PARTIAL and has conversation */}
          {hasConversation && activeRun && ['COMPLETE', 'PARTIAL'].includes(activeRun.status) && (
            <button
              onClick={() => { setShowApplyBox((v) => !v); setShowIterateBox(false); }}
              className={cn(
                'flex items-center gap-1.5 px-3 py-2 min-h-[44px] rounded text-xs font-medium transition-all',
                'bg-emerald-500/15 text-emerald-400 hover:bg-emerald-500/25',
                (applying || iterating) && 'opacity-50 cursor-not-allowed'
              )}
              disabled={applying || iterating}
            >
              {applying
                ? <Loader2 className="h-3 w-3 animate-spin" />
                : <Wrench className="h-3 w-3" />}
              Apply to Run
              {permissions && permissions.maxApplySuggestions > 0 && (
                <span className="text-[10px] font-mono text-muted ml-1">
                  {activeRun.applySuggestionCount ?? 0}/{permissions.maxApplySuggestions}
                </span>
              )}
            </button>
          )}

          {/* Fix Remaining Issues — only when validation report has unresolved issues */}
          {unresolvedIssues.length > 0 && activeRun && ['COMPLETE', 'PARTIAL'].includes(activeRun.status) && (
            <button
              onClick={async () => {
                if (applyingFixes) return;
                const prompt = buildIssuesPrompt(unresolvedIssues);
                if (!prompt) return;
                setApplyingFixes(true);
                try {
                  const result = await applyToRun(runId, prompt, 'COPILOT');
                  addToast({ type: 'success', message: `Applied ${result.taskCount} fix${result.taskCount !== 1 ? 'es' : ''} for unresolved issues` });
                  useRunStore.getState().fetchRun(runId);
                } catch (e: any) {
                  const msg = e.message ?? '';
                  if (msg.includes('422')) {
                    // Too large — fall back to iterate as new run
                    setPendingNewRunGoal(buildIssuesPrompt(unresolvedIssues));
                    addToast({ type: 'info', message: 'Too many fixes for apply. Redirecting to new run.' });
                  } else {
                    addToast({ type: 'error', message: msg || 'Failed to apply fixes.' });
                  }
                } finally {
                  setApplyingFixes(false);
                }
              }}
              disabled={applyingFixes}
              className={cn(
                'flex items-center gap-1.5 px-3 py-2 min-h-[44px] rounded text-xs font-medium transition-all',
                'bg-red-500/15 text-accent-red hover:bg-red-500/25',
                applyingFixes && 'opacity-50 cursor-not-allowed'
              )}
            >
              {applyingFixes
                ? <Loader2 className="h-3 w-3 animate-spin" />
                : <Shield className="h-3 w-3" />}
              Fix {unresolvedIssues.length} Issue{unresolvedIssues.length !== 1 ? 's' : ''}
            </button>
          )}

          {/* Compile as Spec — consolidate discussed improvements */}
          {hasConversation && messages.length >= 2 && (
            <button
              onClick={compileSpec}
              className="flex items-center gap-1.5 px-3 py-2 min-h-[44px] rounded text-xs font-medium transition-all bg-amber-500/15 text-accent-amber hover:bg-amber-500/25"
            >
              <FileText className="h-3 w-3" />
              Compile as Spec
            </button>
          )}

          {/* Iterate as New Run button */}
          <button
            onClick={() => { setShowIterateBox((v) => !v); setShowApplyBox(false); }}
            className={cn(
              'flex items-center gap-1.5 px-3 py-2 min-h-[44px] rounded text-xs font-medium transition-all',
              'bg-accent-blue/15 text-accent-blue hover:bg-accent-blue/25',
              iterating && 'opacity-50 cursor-not-allowed'
            )}
            disabled={iterating}
          >
            {iterating
              ? <Loader2 className="h-3 w-3 animate-spin" />
              : <Rocket className="h-3 w-3" />}
            Iterate as New Run
          </button>
        </div>
      </div>

      {/* Apply to Run box */}
      <AnimatePresence>
        {showApplyBox && (
          <motion.div
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: 'auto', opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            transition={{ duration: 0.2 }}
            className="overflow-hidden border-b border-[var(--border-default)] flex-shrink-0"
          >
            <div className="px-4 py-3 space-y-2 bg-emerald-500/[0.03]">
              <p className="text-xs text-muted leading-relaxed">
                Apply improvements directly to this run. The system will create a mini-plan
                ({'\u2264'}6 tasks), append them to the existing task graph, and execute them.
              </p>
              <textarea
                className={cn(
                  'w-full bg-[var(--gray-200)] border border-[var(--border-default)] rounded-sm px-3 py-2',
                  'text-sm text-primary placeholder-[var(--text-muted)] resize-none focus:outline-none',
                  'focus:border-emerald-500/40 focus:bg-[var(--gray-200)] transition-colors'
                )}
                rows={3}
                placeholder={buildSuggestedRequest() || 'Describe the improvement to apply\u2026'}
                value={applyInput}
                onChange={(e) => setApplyInput(e.target.value)}
              />
              <div className="flex justify-end gap-2">
                <button
                  onClick={() => setShowApplyBox(false)}
                  className="px-3 py-1.5 text-xs text-muted hover:text-secondary transition-colors"
                >
                  Cancel
                </button>
                <button
                  onClick={handleApply}
                  disabled={applying || (!applyInput.trim() && !buildSuggestedRequest())}
                  className={cn(
                    'flex items-center gap-1.5 px-3 py-1.5 rounded text-xs font-medium transition-all',
                    'bg-emerald-600 text-primary hover:bg-emerald-500',
                    'disabled:opacity-40 disabled:cursor-not-allowed'
                  )}
                >
                  {applying
                    ? <><Loader2 className="h-3 w-3 animate-spin" /> Applying&hellip;</>
                    : <><Wrench className="h-3 w-3" /> Apply Changes</>
                  }
                </button>
              </div>
            </div>
          </motion.div>
        )}
      </AnimatePresence>

      {/* Iterate box */}
      <AnimatePresence>
        {showIterateBox && (
          <motion.div
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: 'auto', opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            transition={{ duration: 0.2 }}
            className="overflow-hidden border-b border-[var(--border-default)] flex-shrink-0"
          >
            <div className="px-4 py-3 space-y-2 bg-accent-blue/[0.03]">
              <p className="text-xs text-muted leading-relaxed">
                Describe the changes you want to implement. The new run will start with a
                copy of this run's codebase and apply your changes via the full agent pipeline.
              </p>
              <textarea
                className={cn(
                  'w-full bg-[var(--gray-200)] border border-[var(--border-default)] rounded-sm px-3 py-2',
                  'text-sm text-primary placeholder-[var(--text-muted)] resize-none focus:outline-none',
                  'focus:border-accent-blue/40 focus:bg-[var(--gray-200)] transition-colors'
                )}
                rows={3}
                placeholder={buildSuggestedRequest() || 'Describe what you want to change or add\u2026'}
                value={iterateInput}
                onChange={(e) => setIterateInput(e.target.value)}
              />
              <div className="flex justify-end gap-2">
                <button
                  onClick={() => setShowIterateBox(false)}
                  className="px-3 py-1.5 text-xs text-muted hover:text-secondary transition-colors"
                >
                  Cancel
                </button>
                <button
                  onClick={handleIterate}
                  disabled={iterating || (!iterateInput.trim() && !buildSuggestedRequest())}
                  className={cn(
                    'flex items-center gap-1.5 px-3 py-1.5 rounded text-xs font-medium transition-all',
                    'bg-accent-blue text-primary hover:bg-accent-blue/90',
                    'disabled:opacity-40 disabled:cursor-not-allowed'
                  )}
                >
                  {iterating
                    ? <><Loader2 className="h-3 w-3 animate-spin" /> Creating run&hellip;</>
                    : <><Rocket className="h-3 w-3" /> Iterate as New Run</>
                  }
                </button>
              </div>
            </div>
          </motion.div>
        )}
      </AnimatePresence>

      {/* Compiled Spec panel — buttons outside scroll region for mobile accessibility */}
      <AnimatePresence>
        {showCompiledSpec && compiledSpec && (
          <motion.div
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: 'auto', opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            transition={{ duration: 0.2 }}
            className="overflow-hidden border-b border-[var(--border-default)] flex-shrink-0"
          >
            <div className="px-4 py-3 bg-amber-500/[0.03]">
              <div className="flex items-center justify-between mb-2">
                <span className="text-xs font-medium text-accent-amber">Compiled Spec</span>
                <button
                  onClick={() => setShowCompiledSpec(false)}
                  className="text-xs text-muted hover:text-secondary min-h-[44px] min-w-[44px] flex items-center justify-center"
                >Close</button>
              </div>
              {/* Scrollable markdown content — buttons stay outside */}
              <div className="text-xs text-secondary whitespace-pre-wrap bg-[var(--gray-200)] rounded-sm p-3 max-h-48 overflow-y-auto mb-3">
                <MarkdownContent content={compiledSpec} />
              </div>
              {/* Action buttons — always visible, large touch targets */}
              <div className="flex justify-end gap-2 flex-wrap">
                <button
                  onClick={() => { handleApply(compiledSpec); setShowCompiledSpec(false); }}
                  className="flex items-center gap-1.5 px-4 py-2.5 min-h-[44px] rounded text-xs font-medium bg-emerald-600 text-primary hover:bg-emerald-500 transition-colors"
                >
                  {applying
                    ? <><Loader2 className="h-3.5 w-3.5 animate-spin" /> Applying...</>
                    : <><Wrench className="h-3.5 w-3.5" /> Apply to Run</>
                  }
                </button>
                <button
                  onClick={() => { setPendingNewRunGoal(compiledSpec); setShowCompiledSpec(false); }}
                  className="flex items-center gap-1.5 px-4 py-2.5 min-h-[44px] rounded text-xs font-medium bg-accent-blue text-primary hover:bg-accent-blue/90 transition-colors"
                >
                  <Rocket className="h-3.5 w-3.5" /> Iterate as New Run
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
          <div className="flex flex-col items-center justify-center h-full text-center space-y-4 py-8">
            <MessageSquare className="h-8 w-8 text-[var(--text-muted)]" />
            <div>
              <p className="text-sm font-medium text-secondary">
                Run Copilot
              </p>
              <p className="text-xs text-muted mt-1 max-w-sm leading-relaxed">
                Debug preview issues, get improvement suggestions, or ask about the architecture.
                This AI has full access to your run's source code, plan, review, and Docker status.
              </p>
            </div>

            {/* Quick action chips */}
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
                    sending && 'opacity-50 cursor-not-allowed'
                  )}
                >
                  <Icon className="h-3.5 w-3.5" />
                  {label}
                </button>
              ))}
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
            {msg.role === 'user' ? (
              /* User message — plain text bubble */
              <div className="max-w-[85%] rounded-sm px-4 py-3 text-sm leading-relaxed bg-accent-blue/20 text-primary rounded-br-sm">
                <p className="whitespace-pre-wrap break-words">{msg.content}</p>
              </div>
            ) : (
              /* Assistant message — rendered markdown */
              <div className="max-w-[90%] rounded-sm px-4 py-3 bg-[var(--gray-300)] border border-[var(--border-default)] text-secondary rounded-bl-sm">
                <MarkdownContent content={msg.content} compact />
              </div>
            )}
          </motion.div>
        ))}

        {/* Sending indicator */}
        {sending && (
          <div className="flex justify-start">
            <div className="bg-[var(--gray-300)] border border-[var(--border-default)] rounded-sm rounded-bl-sm px-4 py-3">
              <div className="flex items-center gap-2">
                <div className="flex items-center gap-1.5">
                  <span className="h-1.5 w-1.5 bg-accent-blue/60 rounded-full animate-bounce [animation-delay:0ms]" />
                  <span className="h-1.5 w-1.5 bg-accent-blue/60 rounded-full animate-bounce [animation-delay:150ms]" />
                  <span className="h-1.5 w-1.5 bg-accent-blue/60 rounded-full animate-bounce [animation-delay:300ms]" />
                </div>
                <span className="text-[10px] text-muted">Analyzing code&hellip;</span>
              </div>
            </div>
          </div>
        )}

        {/* Error */}
        {error && (
          <div className="mx-auto max-w-md p-3 rounded-sm bg-accent-red/10 border border-accent-red/20 text-xs text-accent-red text-center">
            {error}
          </div>
        )}

        <div ref={bottomRef} />
      </div>

      {/* Limit reached banner */}
      {limitReached && (
        <div className="px-4 py-3 border-t border-[var(--border-default)] bg-accent-red/[0.04] flex-shrink-0">
          <p className="text-xs text-accent-red text-center">
            Conversation limit reached. Click{' '}
            <button
              className="underline text-accent-blue hover:opacity-80"
              onClick={() => setShowIterateBox(true)}
            >
              Iterate as New Run
            </button>{' '}
            to continue with a new run.
          </p>
        </div>
      )}

      {/* Input area — with safe-area bottom padding for iOS */}
      {!limitReached && (
        <div className="px-4 py-3 border-t border-[var(--border-default)] flex-shrink-0" style={{ paddingBottom: 'max(0.75rem, env(safe-area-inset-bottom))' }}>
          {/* Unresolved issues quick-action — shows above input when issues exist */}
          {unresolvedIssues.length > 0 && !hasConversation && (
            <button
              onClick={() => handleSend(buildIssuesPrompt(unresolvedIssues))}
              disabled={sending}
              className={cn(
                'flex items-center gap-1.5 w-full px-3 py-2.5 mb-2 rounded-sm text-xs transition-all',
                'bg-red-500/10 border border-red-500/20 text-accent-red',
                'hover:bg-red-500/15 hover:border-red-500/30',
                sending && 'opacity-50 cursor-not-allowed'
              )}
            >
              <AlertTriangle className="h-3.5 w-3.5" />
              Ask about {unresolvedIssues.length} unresolved validation issue{unresolvedIssues.length !== 1 ? 's' : ''}
            </button>
          )}
          <div className={cn(
            'flex items-end gap-2 bg-[var(--gray-200)] border rounded-sm px-3 py-2 transition-colors',
            input ? 'border-accent-blue/40' : 'border-[var(--border-default)]'
          )}>
            <textarea
              ref={inputRef}
              className="flex-1 bg-transparent text-sm text-primary placeholder-[var(--text-muted)] resize-none focus:outline-none max-h-32 min-h-[24px]"
              placeholder="Debug issues, ask about code, request improvements\u2026"
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
              onClick={() => handleSend()}
              disabled={!input.trim() || sending || limitReached}
              className={cn(
                'p-2.5 min-h-[44px] min-w-[44px] flex items-center justify-center rounded-sm flex-shrink-0 transition-all',
                input.trim() && !sending
                  ? 'bg-accent-blue text-primary hover:bg-accent-blue/90'
                  : 'text-muted cursor-not-allowed'
              )}
            >
              {sending
                ? <Loader2 className="h-4 w-4 animate-spin" />
                : <Send className="h-4 w-4" />
              }
            </button>
          </div>
          <p className="text-[10px] text-[var(--text-muted)] mt-1 pl-1">
            Press Enter to send · Shift+Enter for new line
          </p>
        </div>
      )}
    </div>
  );
}
