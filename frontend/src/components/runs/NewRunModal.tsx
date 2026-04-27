import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { motion, AnimatePresence } from 'framer-motion';
import { AlertCircle, ArrowLeft, ChevronDown, Github, Loader2, Rocket, Send, Sparkles, X, Zap } from 'lucide-react';
import { useRunStore } from '@/stores/useRunStore';
import { useUiStore, type NewRunConfig } from '@/stores/useUiStore';
import { useAuthStore } from '@/stores/useAuthStore';
import { conversationChat, ConversationMessage, getProjectRepos } from '@/lib/api';
import type { ProjectRepoLink } from '@/types/project';
import { cn } from '@/lib/utils';
import ProjectSelector from '@/components/auth/ProjectSelector';
import { MarkdownContent } from '@/components/shared/MarkdownContent';
import { UpgradeNudge } from '@/components/billing/UpgradeNudge';
import ProviderModelSelector, { getPersistedSelection } from '@/components/runs/ProviderModelSelector';
import { AddGitHubPatDrawer, AddApiKeysDrawer } from '@/components/runs/InlineCredentialDrawers';
import { useViewMode } from '@/hooks/useViewMode';
import { useIsMobile } from '@/hooks/useIsMobile';
import Button from '@/components/shared/Button';
import Checkbox from '@/components/shared/Checkbox';
import Radio from '@/components/shared/Radio';
import EstimateChip from '@/components/runs/EstimateChip';

interface NewRunModalProps {
  onClose: () => void;
  initialConfig?: NewRunConfig;
}

const MAX_MESSAGES = 50;

type Mode = 'compose' | 'chat';

export default function NewRunModal({ onClose, initialConfig }: NewRunModalProps) {
  // ── compose mode state ────────────────────────────────────────────────────
  const [goal, setGoal] = useState(initialConfig?.goal ?? '');
  const [executionMode, setExecutionMode] = useState<'PROTOTYPE' | 'PRODUCTION'>(initialConfig?.executionMode ?? 'PROTOTYPE');
  const [showGitHub, setShowGitHub] = useState(initialConfig?.repoMode && initialConfig.repoMode !== 'NONE' ? true : false);
  const persisted = getPersistedSelection();
  const [provider, setProvider] = useState(initialConfig?.provider ?? persisted.provider);
  const [model, setModel] = useState(initialConfig?.model ?? persisted.model);
  const [selectedProjectId, setSelectedProjectId] = useState(initialConfig?.projectId ?? '');
  const [launching, setLaunching] = useState(false);
  const [showUpgradeNudge, setShowUpgradeNudge] = useState(false);
  const [upgradeInfo, setUpgradeInfo] = useState({ metric: 'RUNS', current: 0, max: 0 });

  // ── github repo options ───────────────────────────────────────────────────
  type RepoChoice = 'NONE' | 'NEW' | 'EXISTING';
  const [repoChoice, setRepoChoice] = useState<RepoChoice>(initialConfig?.repoMode ?? 'NONE');
  const [repoUrl, setRepoUrl] = useState(initialConfig?.repoUrl ?? '');

  // ── pilot v3.0: project-linked multi-repo selection ──────────────────────
  const [projectRepos, setProjectRepos] = useState<ProjectRepoLink[]>([]);
  const [selectedRepoIds, setSelectedRepoIds] = useState<string[]>([]);
  const [figmaUrl, setFigmaUrl] = useState('');
  const [tddMode, setTddMode] = useState(false);

  // ── chat mode state ───────────────────────────────────────────────────────
  const [mode, setMode] = useState<Mode>('compose');
  const [messages, setMessages] = useState<ConversationMessage[]>([]);
  const [draft, setDraft] = useState('');
  const [chatLoading, setChatLoading] = useState(false);
  const [compilingGoalIndex, setCompilingGoalIndex] = useState<number | null>(null);
  const messagesEndRef = useRef<HTMLDivElement>(null);

  const { createRun, setActiveRunId } = useRunStore();
  const { addToast } = useUiStore();
  const { user } = useAuthStore();
  const { isVibe, vibeRunDefaults } = useViewMode();
  const isMobile = useIsMobile();
  const navigate = useNavigate();

  // M-008 — inline credential drawers so the user doesn't get bounced
  // to /settings (and lose every typed field) just to add a PAT or key.
  const [showPatDrawer, setShowPatDrawer] = useState(false);
  const [showKeysDrawer, setShowKeysDrawer] = useState(false);

  // Reset state if initialConfig changes (though usually the modal is unmounted)
  useEffect(() => {
    if (initialConfig) {
      setGoal(initialConfig.goal);
      if (initialConfig.executionMode) setExecutionMode(initialConfig.executionMode);
      if (initialConfig.provider) setProvider(initialConfig.provider);
      if (initialConfig.model) setModel(initialConfig.model);
      if (initialConfig.projectId) setSelectedProjectId(initialConfig.projectId);
      if (initialConfig.repoMode) {
        setRepoChoice(initialConfig.repoMode);
        setShowGitHub(initialConfig.repoMode !== 'NONE');
      }
      if (initialConfig.repoUrl) setRepoUrl(initialConfig.repoUrl);
    }
  }, [initialConfig]);

  // Auto-scroll to bottom of chat
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  // Pilot v3.0 — pull linked repos for the selected project
  useEffect(() => {
    if (!selectedProjectId) {
      setProjectRepos([]);
      setSelectedRepoIds([]);
      return;
    }
    getProjectRepos(selectedProjectId)
      .then((links) => setProjectRepos(links))
      .catch(() => setProjectRepos([]));
    setSelectedRepoIds([]);
  }, [selectedProjectId]);

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

  /** Extract only the requirement summary, stripping conversational prefix/suffix. */
  const extractSummary = (text: string): string => {
    // Match everything after "REQUIREMENT SUMMARY:" up to the end or a trailing
    // conversational line (e.g. "Let me know...", "Feel free...", "Would you like...")
    const match = text.match(/REQUIREMENT SUMMARY:\s*([\s\S]+)/i);
    if (!match) return text.trim();
    let body = match[1].trim();
    // Strip trailing conversational fluff — lines starting with common AI phrases
    body = body.replace(/\n\s*(?:Let me know|Feel free|Would you like|If you|I hope|Is there anything|Happy to|Please let|Don't hesitate|---).*/is, '').trim();
    return body;
  };

  const useAsGoal = async (text: string, messageIndex: number) => {
    const hasSummary = /REQUIREMENT SUMMARY:/i.test(text);

    if (hasSummary) {
      // Instant extraction — message already contains a well-formed summary
      setGoal(extractSummary(text));
      setMode('compose');
      return;
    }

    // Mid-chat compilation — call AI to compile goals from conversation so far
    setCompilingGoalIndex(messageIndex);
    try {
      const context = messages.slice(0, messageIndex + 1);
      const compilePrompt: ConversationMessage = {
        role: 'user',
        content: 'Compile a clear, concise, actionable requirement summary from the conversation above. Output ONLY the technical requirements — no preamble, no commentary, no trailing remarks. Start directly with the first requirement.',
      };
      const { reply } = await conversationChat([...context, compilePrompt]);
      setGoal(extractSummary(reply));
      setMode('compose');
    } catch (e: any) {
      addToast({ type: 'error', message: `Failed to compile goals: ${e.message}` });
    } finally {
      setCompilingGoalIndex(null);
    }
  };

  // ── launch the pipeline run ───────────────────────────────────────────────
  const handleSubmit = async () => {
    if (!goal.trim()) return;
    if (repoChoice === 'EXISTING' && !repoUrl.trim()) return;
    setLaunching(true);
    try {
      const repoMode = repoChoice === 'NONE' ? null : repoChoice;  // 'NEW' | 'EXISTING' | null
      const isPrototype = executionMode === 'PROTOTYPE';
      const runId = await createRun({
        userRequest: goal,
        executionMode: isVibe ? 'PROTOTYPE' : executionMode,
        skipApproval: isVibe ? vibeRunDefaults.skipApproval : isPrototype,
        autoMerge: isVibe ? vibeRunDefaults.autoMerge : isPrototype,
        ...(model ? { modelName: model } : {}),
        ...(provider ? { aiProvider: provider } : {}),
        ...(selectedProjectId ? { projectId: selectedProjectId } : {}),
        ...(repoMode ? { repoMode } : {}),
        ...(repoChoice === 'EXISTING' && repoUrl.trim() ? { repoUrl: repoUrl.trim() } : {}),
        ...(selectedRepoIds.length > 0 ? { selectedRepoIds } : {}),
        ...(figmaUrl.trim() ? { figmaUrl: figmaUrl.trim() } : {}),
        ...(tddMode ? { tddMode: true } : {}),
      });
      addToast({ type: 'success', message: `Run launched: ${runId.slice(0, 8)}` });
      navigate('/');
      setActiveRunId(runId);
      onClose();
    } catch (e: any) {
      const msg = e.message || '';
      if (msg.startsWith('402')) {
        // Parse billing limit error: "402: RUNS limit reached (5/5). Upgrade..."
        const match = msg.match(/(\w+) limit reached \((\d+)\/(\d+)\)/);
        if (match) {
          setUpgradeInfo({ metric: match[1], current: parseInt(match[2]), max: parseInt(match[3]) });
        }
        setShowUpgradeNudge(true);
      } else {
        addToast({ type: 'error', message: msg });
      }
      setLaunching(false);
    }
  };

  const msgCount = messages.length;
  const atLimit  = msgCount >= MAX_MESSAGES;
  // User can only send if there's a slot available for both their message AND the AI reply
  const canSend  = !chatLoading && messages.length < MAX_MESSAGES && draft.trim().length > 0;

  // Whether to show the project selector (only for org members)
  const isOrgMember = !!user?.orgId;

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center p-0 lg:p-4"
      style={{ backdropFilter: 'blur(4px)', background: 'rgba(0,0,0,0.6)' }}
      onClick={onClose}
    >
      <motion.div
        initial={{ opacity: 0, scale: isMobile ? 1 : 0.96 }}
        animate={{ opacity: 1, scale: 1 }}
        exit={{ opacity: 0, scale: isMobile ? 1 : 0.96 }}
        transition={{ duration: 0.15 }}
        onClick={(e) => e.stopPropagation()}
        className={cn(
          'w-full bg-[var(--gray-200)] border border-[var(--border-default)] overflow-hidden flex flex-col',
          isMobile ? 'fixed inset-0 rounded-none' : 'rounded-sm max-w-xl',
        )}
        style={isMobile ? undefined : { maxHeight: mode === 'chat' ? 'min(95dvh, 80vh)' : '95dvh' }}
      >
        {/* ── Header ───────────────────────────────────────────────────────── */}
        <div
          className="relative flex items-center justify-between px-6 py-5 border-b border-[var(--border-default)] flex-shrink-0 overflow-hidden"
          style={{ background: 'linear-gradient(135deg, color-mix(in srgb, var(--accent-blue) 14%, transparent), color-mix(in srgb, var(--accent-harness) 8%, transparent) 60%, transparent)' }}
        >
          {mode === 'chat' ? (
            <button
              onClick={() => setMode('compose')}
              className="flex items-center gap-1.5 text-sm text-secondary hover:text-primary transition-colors"
            >
              <ArrowLeft className="h-3.5 w-3.5" />
              Back to goal
            </button>
          ) : (
            <div className="min-w-0 flex-1">
              {/* M-001 — copy-only Mission rename. */}
              <div className="font-mono text-[10.5px] font-medium uppercase tracking-[0.08em] text-accent-blue mb-1">New mission</div>
              <h2 className="text-lg sm:text-xl font-semibold text-primary tracking-[-0.015em] leading-tight">
                Write a spec. <span style={{ fontFamily: "'Instrument Serif', Georgia, serif", fontStyle: 'italic', fontWeight: 400 }}>Ship</span> it.
              </h2>
            </div>
          )}

          <div className="flex items-center gap-3">
            {mode === 'chat' && (
              <span className={cn(
                'text-xs font-mono tabular-nums px-2 py-0.5 rounded-sm',
                atLimit
                  ? 'bg-accent-red/15 text-accent-red'
                  : 'bg-[var(--gray-200)] text-secondary',
              )}>
                {msgCount}/{MAX_MESSAGES}
              </span>
            )}
            <button onClick={onClose} className="p-2 -m-1 text-muted hover:text-secondary">
              <X className="h-4 w-4" />
            </button>
          </div>
        </div>

        {/* ── Compose mode ─────────────────────────────────────────────────── */}
        {mode === 'compose' && (
          <div className="flex-1 flex flex-col min-h-0 overflow-hidden">
          <div className="p-6 space-y-4 overflow-y-auto flex-1 min-h-0">
            {/* v2.5.12 — supported-stack hint moved above the textarea so users
                see the constraint before they start typing. Was below, easy to
                miss after submit. */}
            <p className="text-xs text-muted">
              Supported: Java, TypeScript, React, Python, Go, Rust, HTML/CSS.
              {' '}Best results with concrete specs (endpoints, validation,
              expected output).
            </p>
            <textarea
              autoFocus
              value={goal}
              onChange={(e) => setGoal(e.target.value)}
              placeholder="e.g., Add a /api/health endpoint that returns {status:'ok'} and a server uptime in seconds. Include a unit test."
              rows={isMobile ? 6 : 5}
              className={cn(
                'w-full font-mono text-sm bg-[var(--gray-100)] border border-[var(--border-default)] rounded-sm',
                'px-4 py-3 text-primary placeholder:text-muted',
                'focus:outline-none focus:border-accent-blue/40 resize-none',
                isMobile && 'min-h-[120px]',
              )}
            />

            {/* Project selector — visible for org members only */}
            {isOrgMember && (
              <ProjectSelector
                value={selectedProjectId}
                onChange={setSelectedProjectId}
              />
            )}

            {/* Launch-time heuristic estimate — shown only when a project is
                selected (estimate endpoint is project-scoped). Mirrors the
                chip in ExecutionSetup so New Run + Launch surfaces agree. */}
            {selectedProjectId && <EstimateChip projectId={selectedProjectId} requirement={goal} />}

            {/* Pilot v3.0 — multi-repo selection from project catalogue */}
            {selectedProjectId && projectRepos.length > 0 && (
              <div className="space-y-2">
                <label className="text-xs text-muted">Project repositories</label>
                <div className="space-y-1 max-h-40 overflow-y-auto border border-[var(--border-default)] rounded-sm p-2">
                  {projectRepos.map((link) => {
                    const checked = selectedRepoIds.includes(link.repoId);
                    const id = `repo-${link.repoId}`;
                    return (
                      <Checkbox
                        key={link.repoId}
                        id={id}
                        size="sm"
                        checked={checked}
                        onChange={(e) => {
                          setSelectedRepoIds((prev) =>
                            e.target.checked
                              ? [...prev, link.repoId]
                              : prev.filter((id) => id !== link.repoId),
                          );
                        }}
                        className="py-0.5"
                        label={
                          <span className="flex items-center gap-1.5">
                            <span className="font-mono text-xs">{link.repo.githubRepoFullName}</span>
                            <span className="text-[10px] text-muted">({link.repo.scope})</span>
                          </span>
                        }
                      />
                    );
                  })}
                </div>
                {selectedRepoIds.length > 0 && (
                  <p className="text-[11px] text-muted">
                    {selectedRepoIds.length} repo{selectedRepoIds.length > 1 ? 's' : ''} will be cloned into{' '}
                    <code>workspace/&lt;runId&gt;/repos/</code>.
                  </p>
                )}
              </div>
            )}

            {/* Pilot R7 — Figma URL (optional) */}
            <div className="space-y-1">
              <label className="text-xs text-muted">Figma URL (optional)</label>
              <input
                type="url"
                value={figmaUrl}
                onChange={(e) => setFigmaUrl(e.target.value)}
                placeholder="https://www.figma.com/file/..."
                className="w-full px-3 py-1.5 text-sm border border-[var(--border-default)] rounded-sm bg-transparent focus:outline-none focus:border-accent-blue/50"
              />
              <p className="text-[11px] text-muted">
                When provided, drives the design-compliance score on the validation report.
              </p>
            </div>

            {/* v2.5.2 (Phase 4.3) — TDD Mode toggle */}
            <Checkbox
              id="tdd-mode"
              size="sm"
              checked={tddMode}
              onChange={(e) => setTddMode(e.target.checked)}
              label={
                <span className="text-xs text-muted">
                  <span className="text-secondary">TDD mode</span> — generate failing tests
                  first, then implementation. Slower but higher accuracy on logic-heavy specs.
                </span>
              }
            />


            {/* Build My Spec button */}
            <button
              onClick={enterChat}
              disabled={!goal.trim()}
              className={cn(
                'w-full py-2 rounded-sm text-sm font-medium transition-all flex items-center justify-center gap-2',
                'border border-accent-blue/30 text-accent-blue/80',
                'hover:border-accent-blue/60 hover:text-accent-blue hover:bg-accent-blue/5',
                'disabled:opacity-30 disabled:cursor-not-allowed',
              )}
            >
              <Sparkles className="h-3.5 w-3.5" />
              Build My Spec
            </button>

            {/* ── Pro-only options (hidden in Vibe mode) ────────────────────── */}
            {!isVibe && (
              <>
                {/* Prototype Mode toggle — v2.5.13 colour fix.
                    Was orange (warning) for the default-on state, which read as
                    "danger" rather than "fast path". Switched to accent-blue so
                    the on-state matches the rest of the UI's affirmative cues. */}
                <div
                  role="switch"
                  aria-checked={executionMode === 'PROTOTYPE'}
                  tabIndex={0}
                  onClick={() => setExecutionMode(executionMode === 'PROTOTYPE' ? 'PRODUCTION' : 'PROTOTYPE')}
                  onKeyDown={(e) => { if (e.key === ' ' || e.key === 'Enter') { e.preventDefault(); setExecutionMode(executionMode === 'PROTOTYPE' ? 'PRODUCTION' : 'PROTOTYPE'); } }}
                  className="flex items-start gap-2 justify-between cursor-pointer select-none group"
                >
                  <div className="flex items-start gap-2 min-w-0 flex-1">
                    <Zap className={cn('h-3.5 w-3.5 mt-0.5 flex-shrink-0 transition-colors', executionMode === 'PROTOTYPE' ? 'text-accent-blue' : 'text-muted')} />
                    <span className="text-sm text-secondary group-hover:text-secondary transition-colors">
                      Prototype Mode
                      <span className="block sm:inline text-xs text-muted sm:ml-1.5">
                        {executionMode === 'PROTOTYPE'
                          ? '(Auto-approved, fast models, auto-preview)'
                          : '(Plan review, full validation, code review)'}
                      </span>
                    </span>
                  </div>
                  <span
                    className={cn(
                      'relative h-5 w-9 rounded-full transition-colors flex-shrink-0 mt-0.5',
                      executionMode === 'PROTOTYPE' ? 'bg-accent-blue' : 'bg-[var(--border-default)]',
                    )}
                  >
                    <span className={cn(
                      'absolute top-0.5 h-4 w-4 rounded-full bg-white shadow transition-transform',
                      executionMode === 'PROTOTYPE' ? 'translate-x-4' : 'translate-x-0.5',
                    )} />
                  </span>
                </div>

                {/* Provider & model selection */}
                <ProviderModelSelector
                  provider={provider}
                  model={model}
                  onProviderChange={setProvider}
                  onModelChange={setModel}
                />

                {/* GitHub toggle */}
                <button
                  onClick={() => setShowGitHub(!showGitHub)}
                  className="flex items-center gap-1.5 text-xs text-muted hover:text-secondary"
                >
                  <ChevronDown className={cn('h-3.5 w-3.5 transition-transform', showGitHub && 'rotate-180')} />
                  GitHub repository
                  {repoChoice !== 'NONE' && (
                    <span className="text-accent-blue">•</span>
                  )}
                </button>

                <AnimatePresence>
                  {showGitHub && (
                    <motion.div
                      initial={{ height: 0, opacity: 0 }}
                      animate={{ height: 'auto', opacity: 1 }}
                      exit={{ height: 0, opacity: 0 }}
                      className="overflow-hidden space-y-3"
                    >
                      {/* No-PAT warning + inline drawer (M-008). */}
                      {!user?.hasGithubPat && (
                        <div className="flex items-center gap-2 flex-wrap text-xs text-accent-amber/80 bg-accent-amber/[0.07] border border-accent-amber/20 rounded-sm px-3 py-2">
                          <AlertCircle className="h-3.5 w-3.5 flex-shrink-0" />
                          <span>No GitHub PAT configured.</span>
                          <button
                            type="button"
                            onClick={() => setShowPatDrawer(true)}
                            className="ml-auto rounded-sm bg-accent-amber/20 text-accent-amber px-2 py-0.5 font-semibold hover:bg-accent-amber/30 transition-colors"
                          >
                            Add it now
                          </button>
                        </div>
                      )}

                      {/* Radio options */}
                      <div className="space-y-2">
                        {(
                          [
                            { value: 'NONE',     label: 'None',                   desc: "Don't push to GitHub" },
                            { value: 'NEW',      label: 'Create new repo',        desc: 'Auto-create a private GitHub repo after merge' },
                            { value: 'EXISTING', label: 'Start from existing repo', desc: 'Clone, modify and push back to the same repo' },
                          ] as { value: RepoChoice; label: string; desc: string }[]
                        ).map((opt) => (
                          <div
                            key={opt.value}
                            className={cn(
                              'rounded-sm border px-3 py-2.5 transition-colors',
                              repoChoice === opt.value
                                ? 'border-accent-blue/40 bg-accent-blue/[0.06]'
                                : 'border-[var(--border-default)]',
                            )}
                          >
                            <Radio
                              id={`repoChoice-${opt.value}`}
                              name="repoChoice"
                              value={opt.value}
                              checked={repoChoice === opt.value}
                              onChange={() => setRepoChoice(opt.value as RepoChoice)}
                              label={opt.label}
                              description={opt.desc}
                            />
                          </div>
                        ))}
                      </div>

                      {/* Repo URL input — only when EXISTING */}
                      <AnimatePresence>
                        {repoChoice === 'EXISTING' && (
                          <motion.div
                            initial={{ height: 0, opacity: 0 }}
                            animate={{ height: 'auto', opacity: 1 }}
                            exit={{ height: 0, opacity: 0 }}
                            className="overflow-hidden"
                          >
                            <div className="flex items-center gap-2 bg-[var(--gray-100)] border border-[var(--border-default)] rounded-sm px-3 py-2 focus-within:border-accent-blue/40">
                              <Github className="h-3.5 w-3.5 text-muted flex-shrink-0" />
                              <input
                                type="url"
                                value={repoUrl}
                                onChange={(e) => setRepoUrl(e.target.value)}
                                placeholder="https://github.com/owner/repo"
                                className="flex-1 bg-transparent text-sm text-primary placeholder:text-muted focus:outline-none"
                              />
                            </div>
                          </motion.div>
                        )}
                      </AnimatePresence>
                    </motion.div>
                  )}
                </AnimatePresence>
              </>
            )}

            {/* BYOK warning for MAX tier without API keys + inline drawer (M-008). */}
            {user?.tier === 'MAX' && !user?.hasOpenAiKey && !user?.hasAnthropicKey && !user?.hasGeminiKey && (
              <div className="flex items-start gap-2 p-3 rounded-sm bg-accent-amber/10 border border-accent-amber/30 text-sm text-secondary">
                <AlertCircle className="h-4 w-4 text-accent-amber flex-shrink-0 mt-0.5" />
                <span className="flex-1">
                  Max plan requires your own API keys.
                </span>
                <button
                  type="button"
                  onClick={() => setShowKeysDrawer(true)}
                  className="rounded-sm bg-accent-amber/20 text-accent-amber px-2 py-0.5 text-xs font-semibold hover:bg-accent-amber/30 transition-colors flex-shrink-0"
                >
                  Add keys
                </button>
              </div>
            )}
          </div>
          {/* v2.5.7 UX-fix: sticky footer so the Launch button stays visible
              even when the GitHub repository section or other expandable blocks
              push content below the viewport. Previously the button scrolled
              off-screen and users couldn't find it. */}
          <div className="flex-shrink-0 border-t border-[var(--border-default)] bg-[var(--gray-200)] px-6 py-3">
            <Button
              onClick={handleSubmit}
              variant="primary"
              size="lg"
              fullWidth
              loading={launching}
              disabled={!goal.trim() || launching || (repoChoice === 'EXISTING' && !repoUrl.trim()) || (user?.tier === 'MAX' && !user?.hasOpenAiKey && !user?.hasAnthropicKey && !user?.hasGeminiKey)}
            >
              {!launching && <Rocket className="h-4 w-4" />}
              {launching ? 'Launching...' : 'Launch Run'}
            </Button>
          </div>
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
                  onUseAsGoal={msg.role === 'assistant' ? () => useAsGoal(msg.content, i) : undefined}
                  compiling={compilingGoalIndex === i}
                />
              ))}

              {/* Typing indicator */}
              {chatLoading && (
                <div className="flex gap-2 items-start">
                  <div className="h-6 w-6 rounded-full bg-accent-blue/20 flex items-center justify-center flex-shrink-0 mt-0.5">
                    <Sparkles className="h-3 w-3 text-accent-blue" />
                  </div>
                  <div className="bg-[var(--gray-300)] border border-[var(--border-default)] rounded-sm rounded-tl-sm px-3 py-2">
                    <span className="flex gap-1 items-center h-4">
                      {[0, 150, 300].map((delay) => (
                        <span
                          key={delay}
                          className="h-1.5 w-1.5 rounded-full bg-[var(--gray-400)] animate-bounce"
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
              <div className="mx-5 mb-2 px-3 py-2 rounded-sm bg-accent-amber/10 border border-accent-amber/20">
                <p className="text-xs text-accent-amber">
                  Message limit reached. Click <strong>Use as Goal</strong> on the AI's last reply, then launch your run.
                </p>
              </div>
            )}

            {/* Input row */}
            <div className="border-t border-[var(--border-default)] px-4 py-3 flex-shrink-0">
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
                    'flex-1 bg-[var(--gray-100)] border border-[var(--border-default)] rounded-sm px-3 py-2',
                    'text-sm text-primary placeholder:text-muted',
                    'focus:outline-none focus:border-accent-blue/40',
                    'disabled:opacity-40',
                  )}
                />
                <button
                  onClick={sendMessage}
                  disabled={!canSend}
                  className={cn(
                    'px-3 py-2 rounded-sm transition-all flex items-center justify-center',
                    'bg-accent-blue hover:bg-accent-blue/80',
                    'disabled:opacity-30 disabled:cursor-not-allowed',
                  )}
                >
                  <Send className="h-4 w-4 text-primary" />
                </button>
              </div>

              {/* Launch from chat */}
              <button
                onClick={handleSubmit}
                disabled={!goal.trim() || launching}
                className={cn(
                  'mt-2 w-full py-2 rounded-sm text-xs font-medium transition-all',
                  'text-secondary hover:text-primary hover:bg-[var(--gray-200)]',
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

      {/* Upgrade Nudge on 402 */}
      <UpgradeNudge
        isOpen={showUpgradeNudge}
        onClose={() => setShowUpgradeNudge(false)}
        currentTier={user?.tier || 'FREE'}
        {...upgradeInfo}
      />

      {/* M-008 — inline credential drawers (kept above the modal so the
          parent state stays alive). */}
      <AddGitHubPatDrawer open={showPatDrawer} onClose={() => setShowPatDrawer(false)} />
      <AddApiKeysDrawer open={showKeysDrawer} onClose={() => setShowKeysDrawer(false)} />
    </div>
  );
}

// ── Chat bubble ───────────────────────────────────────────────────────────────

function ChatBubble({
  msg,
  onUseAsGoal,
  compiling = false,
}: {
  msg: ConversationMessage;
  onUseAsGoal?: () => void;
  compiling?: boolean;
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
        isUser ? 'bg-accent-blue/25' : 'bg-[var(--gray-200)]',
      )}>
        {isUser
          ? <span className="text-[9px] font-bold text-accent-blue">You</span>
          : <Sparkles className="h-3 w-3 text-secondary" />
        }
      </div>

      {/* Bubble */}
      <div className={cn(
        'max-w-[80%] rounded-sm px-3 py-2 text-sm leading-relaxed',
        isUser
          ? 'bg-accent-blue/15 text-primary rounded-tr-sm'
          : 'bg-[var(--gray-300)] border border-[var(--border-default)] text-secondary rounded-tl-sm',
      )}>
        {isUser ? (
          /* User messages: plain pre-wrap (no markdown) */
          <p className="whitespace-pre-wrap text-sm leading-relaxed">{msg.content}</p>
        ) : summaryMatch ? (
          <>
            {/* Text before the REQUIREMENT SUMMARY marker — rendered as markdown */}
            {msg.content.replace(/REQUIREMENT SUMMARY:[\s\S]*/i, '').trim() && (
              <MarkdownContent
                content={msg.content.replace(/REQUIREMENT SUMMARY:[\s\S]*/i, '').trim()}
                compact
                className="mb-2"
              />
            )}
            {/* Highlighted summary block */}
            <div className="mt-1 p-2.5 rounded-sm bg-accent-green/10 border border-accent-green/20">
              <p className="text-[10px] uppercase tracking-wider text-accent-green/70 mb-1.5 font-semibold">
                Requirement Summary
              </p>
              <MarkdownContent
                content={summaryMatch[1].trim()}
                compact
              />
            </div>
          </>
        ) : (
          /* Regular AI message — full markdown rendering */
          <MarkdownContent content={msg.content} compact />
        )}

        {/* Use as Goal button on AI messages */}
        {onUseAsGoal && (
          <button
            onClick={onUseAsGoal}
            disabled={compiling}
            className={cn(
              'mt-2 text-[11px] px-2 py-0.5 rounded transition-colors flex items-center gap-1',
              compiling
                ? 'bg-accent-blue/20 text-accent-blue cursor-wait'
                : summaryMatch
                  ? 'bg-accent-green/20 text-accent-green hover:bg-accent-green/30'
                  : 'bg-[var(--gray-200)] text-secondary hover:text-primary hover:bg-[var(--border-default)]',
              'disabled:opacity-70',
            )}
          >
            {compiling ? (
              <>
                <Loader2 className="h-3 w-3 animate-spin" />
                Compiling goals…
              </>
            ) : summaryMatch ? (
              'Use as Goal →'
            ) : (
              'Use as Goal →'
            )}
          </button>
        )}
      </div>
    </div>
  );
}
