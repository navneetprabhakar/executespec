import {useEffect, useRef, useState} from 'react';
import {useNavigate, useParams} from 'react-router-dom';
import {PrCommentInbox} from '@/signal/components/PrCommentInbox';
import {humanStatusReason, providerLabel} from '@/signal/lib/runUtils';
import {RunTimeline} from '@/ide/RunTimeline';
import Crumbs from '@/ide/Crumbs';
import {useDocumentTitle} from '@/hooks/useDocumentTitle';
import {Markdown} from '@/signal/components/Markdown';
import {ConfirmModal} from '@/signal/components/Modal';
import * as api from '@/lib/api';
import {normalizeUiProvider, uiSlashHelp, uiSlashSuggestions, uiSlashUsage} from '@/lib/uiSlash';
import {agentTurnToRunChatMessages} from '@/ide/agentChatHistory';
import {selectScopedAgentSession, webAgentSessionRequest} from '@/ide/agentSession';
import {streamWebAgentChatReply} from '@/ide/agentChatStream';
import {
  IdeBadge,
  IdeCodeViewer,
  IdeFileTree,
  IdeLoader,
  FileIcon,
  ResizeHandle,
  type DiffLine,
} from '@/ide/IdeKit';
import {
  confidenceColor,
  evidenceKind,
  fmtDur,
  isNoisyLogEvent,
  lineDiff,
  logIcon,
  logMessage,
  num,
  SEV,
  TABS,
  TAB_LABEL,
  type Tab,
  toLines,
  usd,
  verdictFor,
} from '@/ide/runResultUtils';

function Kv({k, v}: {k: string; v: React.ReactNode}) {
  return (
    <div className='ide-kv'>
      <span>{k}</span>
      <span>{v}</span>
    </div>
  );
}

function taskGoalLine(t: api.TaskSummary): string | null {
  const goal = t.goalDoneness;
  const continuation = t.continuationDecision;
  if (!goal && !continuation) return null;
  const source = continuationSourceLabel(continuation?.source || continuation?.policy?.source);
  const goalPart =
    goal?.state && goal?.recommendedDecision
      ? `Goal ${goal.state} -> ${goal.recommendedDecision}`
      : goal?.state
        ? `Goal ${goal.state}`
        : null;
  const nextPart = continuation?.decision
    ? `${source ? `${source} · ` : ''}Next ${continuation.decision}`
    : null;
  const reason = continuation?.reasons?.[0] || goal?.reason || null;
  return [goalPart, nextPart, reason].filter(Boolean).join(' · ');
}

function continuationSourceLabel(source?: string | null): string | null {
  switch ((source || '').toUpperCase()) {
    case 'POST_REVIEW':
      return 'Reviewer/build trigger';
    case 'POST_VALIDATION_REPORT':
      return 'Final validation trigger';
    case 'TASK_BOUNDARY':
      return 'Task trigger';
    default:
      return source ? source.replace(/_/g, ' ').toLowerCase() : null;
  }
}

function continuationApprovalCandidate(t: api.TaskSummary) {
  const continuation = t.continuationDecision;
  const candidate = continuation?.selectedCandidate;
  const explicitConfirmation = continuation?.policy?.requireConfirmationBeforeExpensiveContinuation;
  const benchmarkConfidenceBlocked =
    continuation?.policy?.requireBenchmarkPriorForAutomaticContinuation &&
    candidate?.benchmarkQualityPriorAvailable === false;
  if (
    continuation?.decision !== 'STOP_BLOCKED' ||
    (!explicitConfirmation && !benchmarkConfidenceBlocked) ||
    !candidate?.provider ||
    !candidate?.model
  ) {
    return null;
  }
  return candidate;
}

function continuationApprovalReason(t: api.TaskSummary): string | null {
  const continuation = t.continuationDecision;
  if (continuation?.decision !== 'STOP_BLOCKED') return null;
  const source = continuationSourceLabel(continuation.source || continuation.policy?.source);
  const prefix = source ? `${source}: ` : '';
  switch ((continuation.approvalRequiredReason || '').toUpperCase()) {
    case 'BENCHMARK_PRIOR_REQUIRED':
      return `${prefix}Approval needed: no usable benchmark prior for this model on this task category.`;
    case 'EXPENSIVE_CONTINUATION_CONFIRMATION_REQUIRED':
      return `${prefix}Approval needed: this run requires confirmation before model/provider upgrade spend.`;
    case 'ROUTE_PINNED':
      return `${prefix}Blocked: this run is pinned to its selected provider/model, so automatic upgrade cannot change route.`;
    case 'APPROVAL_REQUIRED':
      return `${prefix}Approval needed before this model/provider upgrade can run.`;
    default:
      break;
  }
  const candidate = continuation.selectedCandidate;
  if (
    continuation.policy?.requireBenchmarkPriorForAutomaticContinuation &&
    candidate?.benchmarkQualityPriorAvailable === false
  ) {
    return `${prefix}Approval needed: no usable benchmark prior for this model on this task category.`;
  }
  if (continuation.policy?.requireConfirmationBeforeExpensiveContinuation) {
    return `${prefix}Approval needed: this run requires confirmation before model/provider upgrade spend.`;
  }
  if (continuation.policy?.routePinned) {
    return `${prefix}Blocked: this run is pinned to its selected provider/model, so automatic upgrade cannot change route.`;
  }
  return null;
}

function latestContinuationAction(t: api.TaskSummary): string | null {
  const actions = t.continuationActions || [];
  const action = actions.length > 0 ? actions[actions.length - 1] : null;
  if (!action?.decision || !action?.provider || !action?.model) return null;
  const rawStatus = (action.status || '').toUpperCase();
  const status =
    rawStatus === 'SKIPPED' ? 'Skipped' : rawStatus === 'APPROVED' ? 'Approved' : 'Used';
  const source = continuationSourceLabel(action.source);
  const sourcePart = source ? `${source}: ` : '';
  const limit =
    action.limitName && action.maxLimit != null
      ? ` · ${action.limitName} ${action.usedLimit ?? 0}/${action.maxLimit}`
      : '';
  return `${sourcePart}${status} ${action.decision} -> ${action.provider}/${action.model}${limit}`;
}

function goalStateKind(state?: string | null): 'ok' | 'warn' | 'bad' {
  const s = (state || '').toUpperCase();
  if (s === 'DONE') return 'ok';
  if (s === 'FAILED' || s === 'BLOCKED' || s === 'WASTE_RISK') return 'bad';
  return 'warn';
}

function preciseUsd(n?: number | null): string {
  if (n == null) return '—';
  if (Math.abs(n) < 1) return `$${n.toFixed(4)}`;
  return usd(n);
}

function positive(n?: number | null): number | null {
  return typeof n === 'number' && Number.isFinite(n) && n > 0 ? n : null;
}

function taskBudgetFacts(t: api.TaskSummary): {k: string; v: string}[] {
  const goal = t.goalDoneness;
  if (!goal) return [];
  const policy = t.continuationDecision?.policy || {};
  const facts: {k: string; v: string}[] = [];
  const cost = positive(goal.costUsd);
  const costLimit = positive(policy.maxTotalCostUsd);
  if (cost != null || costLimit != null) {
    facts.push({
      k: 'Cost',
      v: costLimit != null ? `${preciseUsd(cost)} / ${preciseUsd(costLimit)}` : preciseUsd(cost),
    });
  }
  const tokens = positive(goal.totalTokens);
  const tokenLimit = positive(policy.maxTotalTokens);
  if (tokens != null || tokenLimit != null) {
    facts.push({
      k: 'Tokens',
      v: tokenLimit != null ? `${num(tokens)} / ${num(tokenLimit)}` : num(tokens),
    });
  }
  const wall = positive(goal.wallClockMs);
  const wallLimit = positive(policy.maxTaskWallClockMs);
  if (wall != null || wallLimit != null) {
    facts.push({
      k: 'Time',
      v: wallLimit != null ? `${fmtDur(wall)} / ${fmtDur(wallLimit)}` : fmtDur(wall),
    });
  }
  if (goal.budgetCeilingReason) {
    facts.push({k: 'Ceiling', v: goal.budgetCeilingReason});
  }
  return facts;
}

type ContinuationPolicy = NonNullable<
  NonNullable<api.TaskSummary['continuationDecision']>['policy']
>;
type RankingCandidate = NonNullable<ContinuationPolicy['topEligibleCandidates']>[number];

function rankingLabel(policy?: string | null): string {
  return policy === 'quality_desc_then_cost_asc_then_latency_asc'
    ? 'Quality > cost > latency'
    : policy || 'Model ranking';
}

function rankingPolicyDetails(policy: ContinuationPolicy): string[] {
  const details: string[] = [];
  const source = continuationSourceLabel(policy.source);
  if (source) {
    details.push(source);
  }
  if (
    typeof policy.rankingQualityTieBand === 'number' &&
    Number.isFinite(policy.rankingQualityTieBand)
  ) {
    details.push(`near-quality band ${policy.rankingQualityTieBand.toFixed(1)} pts`);
  }
  if (policy.requireBenchmarkPriorForAutomaticContinuation === true) {
    details.push('unbenchmarked upgrades need approval');
  } else if (policy.requireBenchmarkPriorForAutomaticContinuation === false) {
    details.push('unbenchmarked upgrades can auto-run');
  }
  if (policy.routePinned) {
    details.push('route pinned');
  }
  if (policy.lowComplexityWorkerStartTierEffective) {
    const effective = policy.lowComplexityWorkerStartTierEffective.toLowerCase();
    const configured = policy.lowComplexityWorkerStartTierConfigured?.toLowerCase();
    details.push(
      configured && configured !== effective
        ? `LOW starts on ${effective} (${configured} ignored)`
        : `LOW starts on ${effective}`,
    );
  }
  return details;
}

function candidateLabel(c: RankingCandidate): string {
  const provider = providerLabel(c.provider) || c.provider || 'Provider';
  return `${provider}/${c.model || 'model'}`;
}

function candidateMetrics(c: RankingCandidate): string {
  const q = c.qualityScore == null ? 'Q —' : `Q ${Math.round(c.qualityScore)}`;
  const cost = c.costPerMillionTokens == null ? '$—/M' : `$${c.costPerMillionTokens.toFixed(2)}/M`;
  const latency = c.latencyP50Ms == null ? 'p50 —' : `p50 ${fmtDur(c.latencyP50Ms)}`;
  const benchmark = c.benchmarkQualityPriorAvailable ? 'bench' : 'no bench';
  return `${q} · ${cost} · ${latency} · ${benchmark}`;
}

export default function IdeRunResult() {
  const params = useParams();
  const navigate = useNavigate();
  const runId = params.runId || params.runShort || '';
  const [run, setRun] = useState<api.RunDetail | null>(null);
  const [report, setReport] = useState<api.ValidationReportData | null>(null);
  const [tests, setTests] = useState<api.TestReportData | null>(null);
  const [testPlan, setTestPlan] = useState<api.TestPlanData | null>(null);
  const [files, setFiles] = useState<string[]>([]);
  const [openFiles, setOpenFiles] = useState<string[]>([]);
  const [activeFile, setActiveFile] = useState<string | null>(null);
  const [cache, setCache] = useState<Record<string, {content: string; diff: DiffLine[] | null}>>(
    {},
  );
  const [filesWidth, setFilesWidth] = useState(240);
  const [tab, setTab] = useState<Tab>('Overview');
  const [tabMenuOpen, setTabMenuOpen] = useState(false);
  const [msg, setMsg] = useState('');
  const [busy, setBusy] = useState('');
  const [loading, setLoading] = useState(true);
  // Single confirm-modal slot — multiple destructive actions on this page
  // (delete run / deploy / stop preview / mark-test-invalid) drive it via a
  // discriminated payload that carries the action to run on confirm.
  const [confirm, setConfirm] = useState<{
    title: string;
    body: string;
    confirmLabel: string;
    danger?: boolean;
    onConfirm: () => void;
  } | null>(null);
  // RES-1 — run-management overflow menu (Fork / Archive / Delete).
  const [moreOpen, setMoreOpen] = useState(false);
  // Timeline + Chat — IDE parity with Signal ResultPage.
  const [timeline, setTimeline] = useState<api.TimelineData | null>(null);
  const [chat, setChat] = useState<api.RunChatMessage[] | null>(null);
  const [runAgentSessionId, setRunAgentSessionId] = useState<string | null>(null);
  const [chatInput, setChatInput] = useState('');
  const [chatBusy, setChatBusy] = useState(false);
  const chatEndRef = useRef<HTMLDivElement | null>(null);
  // Preview lifecycle — status / logs / iframe embed once a preview is live.
  const [preview, setPreview] = useState<api.LocalPreviewStatus | null>(null);
  const [previewLogs, setPreviewLogs] = useState<string[] | null>(null);
  const [previewStarted, setPreviewStarted] = useState(false);
  // RL-2026-05-046 — Logs tab: the run's live-log events (persisted SSE replay, 24h).
  const [eventLog, setEventLog] = useState<api.RunLogEvent[] | null>(null);

  useEffect(() => {
    if (!runId) return;
    api
      .getRun(runId)
      .then(setRun)
      .catch(() => {})
      .finally(() => setLoading(false));
    api
      .getValidationReport(runId)
      .then(setReport)
      .catch(() => setReport(null));
    api
      .getFiles(runId)
      .then((f) => {
        setFiles(f);
        if (f.length) openFile(f[0]);
      })
      .catch(() => setFiles([]));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [runId]);

  // BUG-FIX (2026-05-29, founder reports #1 + #3) — the result page is the TERMINAL
  // run surface; it never offered an Approve action, so a run opened here at the plan
  // gate (e.g. from a stale link / notification) showed Pause but no way to approve.
  // And an actively-running run opened here showed the empty terminal chrome instead
  // of the live console ("two different views for the same run"). Fix both by routing
  // a non-terminal run to its correct surface as soon as we know its status:
  //   AWAITING_APPROVAL → /plan/:id  (IdePlan owns the tested approve/cancel/revise gate)
  //   actively running  → /live/:id  (IdeLive owns the SSE console; it auto-returns here on terminal)
  // PAUSED + every terminal status stay here (the result page owns resume + the full detail).
  useEffect(() => {
    if (!runId || !run) return;
    const s = (run.status || '').toUpperCase();
    if (s === 'AWAITING_APPROVAL') {
      navigate(`/plan/${runId}`, {replace: true});
    } else if (
      [
        'CLASSIFYING',
        'PLANNING',
        'EXECUTING',
        'RUNNING',
        'IN_PROGRESS',
        'PROVIDER_SWITCHING',
      ].includes(s)
    ) {
      navigate(`/live/${runId}`, {replace: true});
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [run?.status, runId]);

  useEffect(() => {
    if (!runId) return;
    if (tab === 'Tests' && !tests)
      api
        .getTestReport(runId)
        .then(setTests)
        .catch(() => setTests(null));
    if (tab === 'Tests' && !testPlan)
      api
        .getTestPlan(runId)
        .then(setTestPlan)
        .catch(() => setTestPlan(null));
    if (tab === 'Timeline' && !timeline)
      api
        .getTimeline(runId)
        .then(setTimeline)
        .catch(() => setTimeline({tasks: []}));
    if (tab === 'Logs' && !eventLog)
      api
        .getRunEventLog(runId)
        .then(setEventLog)
        .catch(() => setEventLog([]));
    if (tab === 'Chat' && !chat) void loadRunChat();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tab]);

  // Keep the chat thread pinned to the newest message — but only when the user
  // is already near the bottom (RES-8: don't yank the view while they're
  // reading history).
  useEffect(() => {
    if (tab !== 'Chat') return;
    const box = chatEndRef.current?.parentElement;
    if (!box) return;
    const nearBottom = box.scrollHeight - box.scrollTop - box.clientHeight < 160;
    if (nearBottom) box.scrollTop = box.scrollHeight;
  }, [tab, chat, chatBusy]);

  // Preview status poll — once a preview has been started, poll status + logs
  // while the lifecycle is non-terminal (IDLE → BUILDING → … → RUNNING/FAILED).
  useEffect(() => {
    if (!runId || !previewStarted) return;
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | null = null;
    const TERMINAL = ['RUNNING', 'FAILED', 'STOPPED', 'TIMED_OUT', 'IDLE'];
    const tick = async () => {
      try {
        const s = await api.getPreviewStatus(runId);
        if (cancelled) return;
        setPreview(s);
        api
          .getPreviewLogs(runId, 200)
          .then((l) => !cancelled && setPreviewLogs(l.lines || []))
          .catch(() => {});
        if (!TERMINAL.includes(s.status)) timer = setTimeout(tick, 3000);
      } catch {
        if (!cancelled) timer = setTimeout(tick, 5000);
      }
    };
    void tick();
    return () => {
      cancelled = true;
      if (timer) clearTimeout(timer);
    };
  }, [runId, previewStarted]);

  async function openFile(path: string) {
    setActiveFile(path);
    setOpenFiles((o) => (o.includes(path) ? o : [...o, path]));
    if (cache[path]) return;
    try {
      const d = await api.getFileDiff(runId, path);
      // ADDED → every head line is an addition. MODIFIED → real LCS diff over
      // base (old) + head (new). UNCHANGED/other → no diff toggle.
      let diff: DiffLine[] | null = null;
      if (d.status === 'ADDED') {
        diff = toLines(d.head || '').map((t) => ({sign: '+' as const, text: t}));
      } else if (d.status === 'MODIFIED' && (d.base || d.head)) {
        diff = lineDiff(d.base || '', d.head || '');
      }
      setCache((c) => ({...c, [path]: {content: d.head ?? '', diff}}));
    } catch {
      try {
        const content = await api.getFile(runId, path);
        setCache((c) => ({...c, [path]: {content, diff: null}}));
      } catch {
        setCache((c) => ({...c, [path]: {content: '// could not load', diff: null}}));
      }
    }
  }
  function closeFile(path: string, e?: React.MouseEvent) {
    e?.stopPropagation();
    setOpenFiles((o) => {
      const next = o.filter((f) => f !== path);
      if (activeFile === path) setActiveFile(next[next.length - 1] ?? null);
      return next;
    });
  }

  async function action(name: string, fn: () => Promise<unknown>, ok: (r: unknown) => string) {
    setBusy(name);
    setMsg('');
    try {
      const r = await fn();
      setMsg(ok(r));
    } catch (e: unknown) {
      setMsg(e instanceof Error ? e.message : 'Failed.');
    } finally {
      setBusy('');
    }
  }
  function tryProvider(p: string) {
    if (!run) return;
    action(
      'reprovider',
      () =>
        api.createRun({
          userRequest: run.userRequest || '',
          aiProvider: p,
          executionMode: 'PRODUCTION',
          projectId: run.projectId || undefined,
        }),
      (r) => {
        const id = (r as {runId?: string})?.runId;
        if (id) navigate(`/live/${id}`);
        return 'Re-run started.';
      },
    );
  }

  // Run management — fork / archive / unarchive / soft-delete (dark endpoints).
  // 'delete' is confirmed by the caller via the ConfirmModal before reaching here.
  async function manageRun(act: 'fork' | 'archive' | 'unarchive' | 'delete') {
    if (!runId) return;
    setBusy(act);
    setMsg('');
    try {
      if (act === 'fork') {
        const f = await api.forkRun(runId);
        navigate(`/plan/${f.runId}`);
        return;
      }
      if (act === 'delete') {
        await api.softDeleteRun(runId);
        navigate('/runs');
        return;
      }
      await (act === 'archive' ? api.archiveRun(runId) : api.unarchiveRun(runId));
      setRun(await api.getRun(runId));
      setMsg(act === 'archive' ? 'Run archived.' : 'Run unarchived.');
    } catch (e: unknown) {
      setMsg(e instanceof Error ? e.message : 'Action failed.');
    } finally {
      setBusy('');
    }
  }

  async function loadRunChat() {
    if (!runId) return;
    try {
      const [legacy, sessions] = await Promise.all([
        api.getRunChat(runId).catch(() => []),
        api.listAgentSessions(100).catch(() => []),
      ]);
      const session = selectScopedAgentSession(sessions, {projectId: run?.projectId, runId});
      if (session) {
        setRunAgentSessionId(session.id);
        const turns = await api.listAgentTurns(session.id, {limit: 50}).catch(() => []);
        setChat([...legacy, ...turns.flatMap(agentTurnToRunChatMessages)]);
        return;
      }
      setChat(legacy);
    } catch {
      setChat([]);
    }
  }

  async function ensureRunAgentSession(): Promise<api.AgentSessionResponse> {
    if (runAgentSessionId) {
      try {
        return await api.getAgentSession(runAgentSessionId);
      } catch {
        setRunAgentSessionId(null);
      }
    }
    const sessions = await api.listAgentSessions(100);
    const existing = selectScopedAgentSession(sessions, {projectId: run?.projectId, runId});
    if (existing) {
      setRunAgentSessionId(existing.id);
      return existing;
    }
    const created = await api.createAgentSession(
      webAgentSessionRequest({projectId: run?.projectId, runId}),
    );
    setRunAgentSessionId(created.id);
    return created;
  }

  // Run-scoped chat: append the user message optimistically, then stream the reply through
  // the durable agent-session protocol. Legacy /runs/{id}/chat remains read-only history.
  async function sendChat() {
    if (!runId || !chatInput.trim() || chatBusy) return;
    const text = chatInput.trim();
    setChatInput('');
    setChatBusy(true);
    try {
      if (await handleRunSlash(text)) return;
      setChat((prev) => [...(prev || []), {role: 'user', content: text}]);
      const session = await ensureRunAgentSession();
      const reply = await streamWebAgentChatReply(session.id, text, {
        label: 'run agent',
        href: `/runs/${runId}/agent`,
      });
      setChat((prev) => [
        ...(prev || []),
        {
          role: 'assistant',
          content: reply,
        },
      ]);
    } catch (e: unknown) {
      setMsg(e instanceof Error ? e.message : 'Chat failed.');
    } finally {
      setChatBusy(false);
    }
  }

  const runSlashSuggestions = uiSlashSuggestions(chatInput, 'run');

  function appendRunChatNote(content: string) {
    setChat((prev) => [...(prev || []), {role: 'assistant', content}]);
  }

  async function handleRunSlash(raw: string): Promise<boolean> {
    if (!raw.startsWith('/')) return false;
    const [cmd, arg] = raw.split(/\s+/, 2);
    if (cmd === '/help') {
      appendRunChatNote(uiSlashHelp('run'));
      return true;
    }
    if (cmd === '/team' || cmd === '/invite') {
      navigate('/org-members');
      return true;
    }
    if (cmd === '/clear') {
      const created = await api.createAgentSession(
        webAgentSessionRequest({projectId: run?.projectId, runId}),
      );
      setRunAgentSessionId(created.id);
      setChat([]);
      return true;
    }
    if (cmd === '/projects') {
      const projects = await api.getProjects();
      appendRunChatNote(
        projects.length
          ? projects
              .slice(0, 8)
              .map((p) => `${p.name} · ${p.id}`)
              .join('\n')
          : 'No projects found.',
      );
      return true;
    }
    if (cmd === '/runs') {
      const runs = await api.getRuns({scope: 'mine', size: 8});
      appendRunChatNote(
        runs.content.length
          ? runs.content
              .map((r) => `${r.status} · ${r.runId.slice(0, 8)} · ${r.userRequest || 'Untitled'}`)
              .join('\n')
          : 'No recent work found.',
      );
      return true;
    }
    if (cmd === '/status') {
      appendRunChatNote(
        `Run ${runId.slice(0, 8)} · ${run?.status ?? 'loading'} · score ${run?.verifiedScore ?? 'n/a'}`,
      );
      return true;
    }
    if (cmd === '/provider') {
      const provider = normalizeUiProvider(arg);
      if (!provider) {
        appendRunChatNote('Usage: /provider anthropic|openai|gemini|deepseek');
        return true;
      }
      const saved = await api.updateProviderPreference(provider);
      appendRunChatNote(`Provider preference saved: ${saved.provider}`);
      return true;
    }
    return false;
  }

  // Iterate as new run — forks the run's codebase + spec into a fresh run via the
  // backend /iterate endpoint, then routes to the new run's gate (PRODUCTION) or
  // live view (PROTOTYPE).
  async function iterateAsNewRun() {
    if (!runId || chatBusy) return;
    const instruction = chatInput.trim();
    if (!instruction) {
      setMsg('Type the change you want before starting a new iteration.');
      return;
    }
    setChatBusy(true);
    setMsg('');
    try {
      const {runId: newId} = await api.iterateRun(runId, instruction);
      if (newId) {
        setChatInput('');
        const prod = (run?.executionMode || '').toUpperCase() === 'PRODUCTION';
        navigate(prod ? `/plan/${newId}` : `/live/${newId}`);
      }
    } catch (e: unknown) {
      setMsg(e instanceof Error ? e.message : 'Iterate failed.');
    } finally {
      setChatBusy(false);
    }
  }

  const statusUpper = (run?.status || '').toUpperCase();
  const terminal = ['COMPLETE', 'COMPLETED', 'FAILED', 'CANCELLED', 'PARTIAL'].includes(
    statusUpper,
  );
  const failedTerminal = ['FAILED', 'ERROR', 'CANCELLED', 'PARTIAL'].includes(statusUpper);
  const failedTasks =
    run?.tasks?.filter((t) =>
      ['FAILED', 'PARTIAL', 'BLOCKED'].includes((t.status || '').toUpperCase()),
    ) ?? [];
  // Power-ops gating: pause is only meaningful while running; resume only when paused.
  const canPause = [
    'RUNNING',
    'EXECUTING',
    'IN_PROGRESS',
    'PLANNING',
    'AWAITING_APPROVAL',
  ].includes(statusUpper);
  const canResume = statusUpper === 'PAUSED';
  const score = report?.verifiedScore ?? null;
  const v = verdictFor(score, run?.status);
  const measuredTestsTotal = tests?.totalTests ?? report?.testsTotal ?? null;
  const measuredTestsPassed = tests?.passed ?? report?.testsPassed ?? null;
  const testsMeasured = measuredTestsTotal != null && measuredTestsTotal > 0;
  const scoredWithoutMeasuredTests = score != null && !testsMeasured;
  const prUrl = run?.scmPrUrl || run?.githubPrUrl || null;
  const active = activeFile ? cache[activeFile] : null;
  const issues = report?.issues ?? [];
  const failureText =
    run?.failureReason ||
    (run?.statusReason ? humanStatusReason(run.statusReason) : '') ||
    (failedTasks.length > 0
      ? `${failedTasks.length} step${failedTasks.length === 1 ? '' : 's'} need attention.`
      : issues.length > 0
        ? `${issues.length} validation issue${issues.length === 1 ? '' : 's'} found.`
        : 'Open the logs and evidence to identify the failing stage.');
  // C2 — tab title carries the run name once loaded.
  useDocumentTitle(run?.runTitle || (run ? `R-${runId.slice(0, 6)}` : null));

  if (loading) {
    return (
      <div style={{position: 'absolute', inset: 0}}>
        <IdeLoader label='Loading run…' />
      </div>
    );
  }

  return (
    <div style={{position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column'}}>
      {/* tab strip */}
      <div className='ide-result-mobile-nav'>
        <button
          type='button'
          className='ide-project-mobile-trigger'
          aria-haspopup='menu'
          aria-expanded={tabMenuOpen}
          onClick={() => setTabMenuOpen((v) => !v)}
        >
          <span>
            {TAB_LABEL[tab]}
            {tab === 'Code' && files.length ? ` · ${files.length}` : ''}
            {tab === 'Tasks' && run?.tasks?.length ? ` · ${run.tasks.length}` : ''}
            {tab === 'Validation' && issues.length ? ` · ${issues.length}` : ''}
          </span>
          <span aria-hidden='true'>⌄</span>
        </button>
        {tabMenuOpen && (
          <div className='ide-project-mobile-menu' role='menu'>
            {TABS.map((t) => {
              const badge =
                t === 'Code'
                  ? files.length
                  : t === 'Tasks'
                    ? run?.tasks?.length
                    : t === 'Validation'
                      ? issues.length
                      : null;
              return (
                <button
                  key={t}
                  type='button'
                  className={`ide-project-mobile-item${tab === t ? ' active' : ''}`}
                  role='menuitem'
                  onClick={() => {
                    setTab(t);
                    setTabMenuOpen(false);
                  }}
                >
                  {TAB_LABEL[t]}
                  {badge ? ` · ${badge}` : ''}
                </button>
              );
            })}
          </div>
        )}
      </div>
      <div className='ide-rtabs ide-result-tabs'>
        {TABS.map((t) => {
          const badge =
            t === 'Code'
              ? files.length
              : t === 'Tasks'
                ? run?.tasks?.length
                : t === 'Validation'
                  ? issues.length
                  : null;
          return (
            <button
              key={t}
              className={`ide-rtab${tab === t ? ' active' : ''}`}
              onClick={() => setTab(t)}
            >
              {TAB_LABEL[t]}
              {badge ? <span className='ide-rtab-badge'>{badge}</span> : null}
            </button>
          );
        })}
      </div>

      <div style={{flex: 1, minHeight: 0, position: 'relative', overflow: 'hidden'}}>
        {/* ── Overview ── */}
        {tab === 'Overview' && (
          <div className='ide-pane' style={{position: 'absolute', inset: 0, overflowY: 'auto'}}>
            <Crumbs
              items={[
                {label: 'Work', to: '/runs'},
                {label: run?.runTitle || `R-${runId.slice(0, 6)}`},
              ]}
            />
            <h1
              className='ide-h1 ide-clamp2'
              title={run?.runTitle || run?.userRequest || undefined}
            >
              {run ? run.runTitle || (run.userRequest || '').slice(0, 70) || 'Work' : 'Loading…'}
            </h1>
            <div style={{display: 'flex', gap: 8, alignItems: 'center', margin: '8px 0 18px'}}>
              <IdeBadge kind={v.kind}>{v.label}</IdeBadge>
              {score != null && (
                <span style={{fontSize: 20, fontWeight: 700, color: 'var(--ide-fg-bright)'}}>
                  {score}
                  <span style={{fontSize: 12, color: 'var(--ide-fg-muted)'}}> / 100</span>
                </span>
              )}
              <span style={{flex: 1}} />
              {run?.statusReason && humanStatusReason(run.statusReason) && (
                <span style={{color: 'var(--ide-fg-muted)', fontSize: 12}} title={run.statusReason}>
                  {humanStatusReason(run.statusReason)}
                </span>
              )}
            </div>

            {msg && (
              <div className='ide-card' style={{marginBottom: 12, color: 'var(--ide-blue)'}}>
                {msg}
              </div>
            )}

            {failedTerminal && (
              <div className='ide-recovery-card'>
                <div className='ide-recovery-main'>
                  <div className='ide-recovery-kicker'>Recovery path</div>
                  <div className='ide-recovery-title'>
                    {statusUpper === 'PARTIAL'
                      ? 'This run completed with unresolved work.'
                      : 'This run did not reach a clean result.'}
                  </div>
                  <div className='ide-recovery-copy'>{failureText}</div>
                  <div className='ide-recovery-meta'>
                    <span>
                      {failedTasks.length} blocked step{failedTasks.length === 1 ? '' : 's'}
                    </span>
                    <span>
                      {issues.length} evidence issue{issues.length === 1 ? '' : 's'}
                    </span>
                    <span>
                      {files.length} file{files.length === 1 ? '' : 's'}
                    </span>
                  </div>
                </div>
                <div className='ide-recovery-actions' aria-label='Recovery actions'>
                  <button className='ide-btn' onClick={() => setTab('Logs')}>
                    Open logs
                  </button>
                  <button className='ide-btn ghost' onClick={() => setTab('Tasks')}>
                    Failed steps
                  </button>
                  <button className='ide-btn ghost' onClick={() => setTab('Validation')}>
                    Evidence
                  </button>
                  <button className='ide-btn ghost' onClick={() => setTab('Code')}>
                    Files
                  </button>
                  <button className='ide-btn ghost' onClick={() => setTab('Chat')}>
                    Ask about it
                  </button>
                </div>
              </div>
            )}

            <div style={{display: 'flex', gap: 12, flexWrap: 'wrap'}}>
              {/* RES-3 — when nothing was measured (patch/bench runs), one honest
                  line beats a wall of em-dashes that reads as "broken". */}
              {!report && !tests && score == null ? (
                <div
                  className='ide-card'
                  style={{flex: 1, minWidth: 240, color: 'var(--ide-fg-muted)', fontSize: 12}}
                >
                  No verified score for this run — patch and benchmark runs aren't scored by the
                  validation pipeline.
                </div>
              ) : (
                <div className='ide-card' style={{flex: 1, minWidth: 240}}>
                  <div className='ide-h2' style={{marginTop: 0}}>
                    Signals
                  </div>
                  <Kv
                    k='Build'
                    v={
                      report
                        ? report.buildBreakers > 0
                          ? `${report.buildBreakers} breaker(s)`
                          : 'green'
                        : '—'
                    }
                  />
                  <Kv
                    k='Tests'
                    v={
                      testsMeasured
                        ? `${measuredTestsPassed ?? 0}/${measuredTestsTotal}`
                        : score != null
                          ? 'not measured'
                          : '—'
                    }
                  />
                  <Kv
                    k='Reviewer'
                    v={
                      report
                        ? report.reviewFailures > 0
                          ? `${report.reviewFailures} unresolved`
                          : 'clean'
                        : '—'
                    }
                  />
                  <Kv k='Issues' v={issues.length} />
                  <Kv k='Files' v={report?.filesGenerated ?? files.length} />
                  <Kv k='Pipeline' v={report?.pipelineResult ?? '—'} />
                  {scoredWithoutMeasuredTests && (
                    <div
                      className='ide-card'
                      style={{
                        marginTop: 12,
                        padding: 12,
                        borderColor: 'color-mix(in srgb, var(--ide-yellow) 55%, var(--ide-border))',
                        background: 'color-mix(in srgb, var(--ide-yellow) 8%, var(--ide-bg-card))',
                        color: 'var(--ide-fg-muted)',
                        fontSize: 12.5,
                        lineHeight: 1.45,
                      }}
                    >
                      Tests were not measured for this score. Treat the headline as build/reviewer
                      evidence, not a fully test-backed result.
                    </div>
                  )}
                </div>
              )}
              <div className='ide-card' style={{flex: 1, minWidth: 240}}>
                <div className='ide-h2' style={{marginTop: 0}}>
                  Execution details
                </div>
                <Kv k='Status' v={run?.status ?? '—'} />
                <Kv k='Provider' v={(run?.aiProvider || '—').toLowerCase()} />
                <Kv
                  k='Worker'
                  v={
                    <span className='ide-mono' style={{fontSize: 11}}>
                      {run?.workerModel || '—'}
                    </span>
                  }
                />
                <Kv k='Cost' v={usd(run?.actualCostUsd)} />
                <Kv k='Credits' v={run?.creditsCharged ?? '—'} />
              </div>
            </div>

            {run?.userRequest && (
              <>
                <div className='ide-h2'>Original request</div>
                <div
                  className='ide-card ide-mono'
                  style={{fontSize: 12, lineHeight: 1.6, whiteSpace: 'pre-wrap'}}
                >
                  {run.userRequest}
                </div>
              </>
            )}

            <div className='ide-h2'>Next action</div>
            {/* RES-1 — one context-aware primary CTA; everything else is a ghost
                or lives in the ⋯ overflow. Bench/patch runs with no workspace
                files get "Iterate in chat" instead of a blue Push-to-GitHub. */}
            <div style={{display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center'}}>
              {canResume && (
                <button
                  className='ide-btn'
                  disabled={busy === 'resume'}
                  onClick={() =>
                    action(
                      'resume',
                      () => api.resumeRun(runId),
                      () => 'Run resumed.',
                    )
                  }
                >
                  {busy === 'resume' ? 'Resuming…' : '▶ Resume'}
                </button>
              )}
              {!canResume &&
                (prUrl ? (
                  <a className='ide-btn' href={prUrl} target='_blank' rel='noreferrer'>
                    ↗ Open PR
                  </a>
                ) : files.length > 0 ? (
                  <button
                    className='ide-btn'
                    disabled={busy === 'push'}
                    onClick={() =>
                      action(
                        'push',
                        () => api.pushToScm(runId),
                        () => 'Pushed — PR opening.',
                      )
                    }
                  >
                    {busy === 'push' ? 'Pushing…' : '⤴ Push to GitHub'}
                  </button>
                ) : (
                  <button className='ide-btn' onClick={() => setTab('Chat')}>
                    ↻ Iterate in chat
                  </button>
                ))}
              {canPause && (
                <button
                  className='ide-btn ghost'
                  disabled={busy === 'pause'}
                  onClick={() =>
                    action(
                      'pause',
                      () => api.pauseRun(runId),
                      () => 'Run paused.',
                    )
                  }
                >
                  {busy === 'pause' ? 'Pausing…' : '⏸ Pause'}
                </button>
              )}
              <button
                className='ide-btn ghost'
                disabled={busy === 'preview'}
                onClick={() =>
                  action(
                    'preview',
                    async () => {
                      const s = await api.startPreview(runId);
                      setPreview(s);
                      setPreviewStarted(true);
                      return s;
                    },
                    (r) => `Preview: ${(r as {url?: string})?.url || 'starting'}`,
                  )
                }
              >
                ▶ Preview
              </button>
              <button
                className='ide-btn ghost'
                disabled={busy === 'deploy'}
                onClick={() =>
                  setConfirm({
                    title: 'Deploy to Cloud Run',
                    body: 'Deploy this run to a permanent Cloud Run service?',
                    confirmLabel: 'Deploy',
                    onConfirm: () =>
                      action(
                        'deploy',
                        () => api.createDeployment({runId}),
                        (r) =>
                          `Deployed: ${(r as {serviceUrl?: string})?.serviceUrl || 'provisioning'}`,
                      ),
                  })
                }
              >
                {busy === 'deploy' ? 'Deploying…' : '☁ Deploy'}
              </button>
              <button
                className='ide-btn ghost'
                disabled={busy === 'pdf'}
                onClick={() =>
                  action(
                    'pdf',
                    () => api.downloadValidationReportPdf(runId),
                    () => 'Report downloaded.',
                  )
                }
              >
                ⬇ Report PDF
              </button>
              <button
                className='ide-btn ghost'
                disabled={busy === 'code' || files.length === 0}
                title={
                  files.length === 0
                    ? 'No generated code is available for this run.'
                    : 'Download the generated code as a clean repository zip.'
                }
                onClick={() =>
                  action(
                    'code',
                    () => api.downloadRunCode(runId, 'repo'),
                    () => 'Code downloaded.',
                  )
                }
              >
                ⬇ Code ZIP
              </button>
              <button
                className='ide-btn ghost'
                disabled={busy === 'share'}
                onClick={() =>
                  action(
                    'share',
                    () => api.shareValidationReport(runId),
                    (r) => `Shared: ${(r as {shareUrl?: string})?.shareUrl || 'link created'}`,
                  )
                }
              >
                ⌁ Share
              </button>
              {['anthropic', 'openai', 'gemini']
                .filter((p) => p !== (run?.aiProvider || '').toLowerCase())
                .map((p) => (
                  <button
                    key={p}
                    className='ide-btn ghost'
                    onClick={() =>
                      // C8 — re-running on another provider dispatches a NEW paid run.
                      setConfirm({
                        title: `Re-run with ${providerLabel(p)}`,
                        body: `This dispatches a new run of the same spec on ${providerLabel(p)}. It consumes credits like any run. Continue?`,
                        confirmLabel: 'Dispatch run',
                        onConfirm: () => tryProvider(p.toUpperCase()),
                      })
                    }
                  >
                    ↻ Try {providerLabel(p)}
                  </button>
                ))}
              {/* Run management lives in an overflow so the row stays scannable. */}
              <div style={{position: 'relative'}}>
                <button
                  className='ide-btn ghost'
                  aria-haspopup='menu'
                  aria-expanded={moreOpen}
                  onClick={() => setMoreOpen((o) => !o)}
                >
                  ⋯ More
                </button>
                {moreOpen && (
                  <div
                    role='menu'
                    className='ide-card'
                    style={{
                      position: 'absolute',
                      top: 'calc(100% + 4px)',
                      left: 0,
                      zIndex: 60,
                      minWidth: 160,
                      padding: 6,
                      display: 'flex',
                      flexDirection: 'column',
                      gap: 2,
                      boxShadow: '0 6px 16px var(--ide-shadow)',
                    }}
                  >
                    <button
                      className='ide-btn ghost'
                      style={{justifyContent: 'flex-start'}}
                      disabled={busy === 'fork'}
                      onClick={() => {
                        setMoreOpen(false);
                        manageRun('fork');
                      }}
                    >
                      ⑂ Fork
                    </button>
                    <button
                      className='ide-btn ghost'
                      style={{justifyContent: 'flex-start'}}
                      disabled={busy === 'archive' || busy === 'unarchive'}
                      onClick={() => {
                        setMoreOpen(false);
                        manageRun(run?.archivedAt ? 'unarchive' : 'archive');
                      }}
                    >
                      {run?.archivedAt ? '⊡ Unarchive' : '⊟ Archive'}
                    </button>
                    {terminal && (
                      <button
                        className='ide-btn ghost'
                        style={{justifyContent: 'flex-start', color: 'var(--ide-red)'}}
                        disabled={busy === 'delete'}
                        onClick={() => {
                          setMoreOpen(false);
                          setConfirm({
                            title: 'Delete run',
                            body: 'Delete this run? It is soft-deleted with a 30-day undo window.',
                            confirmLabel: 'Delete',
                            danger: true,
                            onConfirm: () => manageRun('delete'),
                          });
                        }}
                      >
                        ⊘ Delete
                      </button>
                    )}
                  </div>
                )}
              </div>
            </div>

            {/* Preview lifecycle — status + iframe embed + logs + rebuild/stop. */}
            {(previewStarted || preview) &&
              (() => {
                const ps = (preview?.status || 'STARTING').toUpperCase();
                const previewUrl = preview?.url || '';
                const live = ps === 'RUNNING' && !!previewUrl;
                const failed = ps === 'FAILED' || ps === 'TIMED_OUT';
                const settled = live || failed || ps === 'STOPPED';
                return (
                  <>
                    <div
                      className='ide-h2'
                      style={{display: 'flex', alignItems: 'center', gap: 10}}
                    >
                      Preview
                      <IdeBadge kind={live ? 'ok' : failed ? 'bad' : 'warn'}>{ps}</IdeBadge>
                      {preview?.ttlRemainingSeconds ? (
                        <span style={{fontSize: 11, color: 'var(--ide-fg-muted)'}}>
                          TTL {Math.max(0, Math.round(preview.ttlRemainingSeconds))}s
                        </span>
                      ) : null}
                      <span style={{flex: 1}} />
                      <button
                        className='ide-btn ghost'
                        style={{padding: '4px 10px'}}
                        disabled={busy === 'preview-rebuild'}
                        onClick={() =>
                          action(
                            'preview-rebuild',
                            async () => {
                              const s = await api.rebuildPreview(runId);
                              setPreview(s);
                              setPreviewStarted(true);
                              return s;
                            },
                            () => 'Rebuilding preview…',
                          )
                        }
                      >
                        {busy === 'preview-rebuild' ? 'Rebuilding…' : '↻ Rebuild'}
                      </button>
                      <button
                        className='ide-btn ghost'
                        style={{padding: '4px 10px', color: 'var(--ide-red)'}}
                        disabled={busy === 'preview-stop'}
                        onClick={() =>
                          setConfirm({
                            title: 'Stop preview',
                            body: 'Stop this preview?',
                            confirmLabel: 'Stop',
                            danger: true,
                            onConfirm: () =>
                              action(
                                'preview-stop',
                                async () => {
                                  await api.stopPreview(runId);
                                  setPreviewStarted(false);
                                  setPreview((p) => (p ? {...p, status: 'STOPPED', url: null} : p));
                                  return null;
                                },
                                () => 'Preview stopped.',
                              ),
                          })
                        }
                      >
                        {busy === 'preview-stop' ? 'Stopping…' : '■ Stop'}
                      </button>
                    </div>

                    {preview?.errorMessage && failed && (
                      <div
                        className='ide-card'
                        style={{color: 'var(--ide-red)', fontSize: 12, marginBottom: 10}}
                      >
                        {preview.errorMessage}
                      </div>
                    )}

                    {live ? (
                      <div className='ide-card' style={{padding: 0, overflow: 'hidden'}}>
                        <div
                          style={{
                            display: 'flex',
                            alignItems: 'center',
                            gap: 8,
                            padding: '6px 12px',
                            borderBottom: '1px solid var(--ide-border)',
                          }}
                        >
                          <span
                            className='ide-mono'
                            style={{
                              fontSize: 11,
                              color: 'var(--ide-fg-muted)',
                              flex: 1,
                              minWidth: 0,
                              whiteSpace: 'nowrap',
                              overflow: 'hidden',
                              textOverflow: 'ellipsis',
                            }}
                          >
                            {previewUrl}
                          </span>
                          <a
                            className='ide-btn ghost'
                            style={{padding: '3px 9px'}}
                            href={previewUrl || '#'}
                            target='_blank'
                            rel='noreferrer'
                          >
                            ↗ Open
                          </a>
                        </div>
                        <iframe
                          title='Run preview'
                          src={previewUrl}
                          sandbox='allow-scripts allow-same-origin allow-forms allow-popups'
                          style={{
                            width: '100%',
                            height: 420,
                            border: 'none',
                            // Deliberate: webpage canvas default, theme-independent.
                            background: '#fff',
                          }}
                        />
                      </div>
                    ) : (
                      !settled && (
                        <div
                          className='ide-card'
                          style={{fontSize: 12, color: 'var(--ide-fg-muted)'}}
                        >
                          Preview is {ps.toLowerCase()}… this can take up to a minute.
                        </div>
                      )
                    )}

                    {previewLogs && previewLogs.length > 0 && (
                      <>
                        <div className='ide-h2' style={{fontSize: 12}}>
                          Preview logs
                        </div>
                        <pre
                          className='ide-card ide-mono'
                          style={{
                            fontSize: 11,
                            lineHeight: 1.5,
                            maxHeight: 220,
                            overflow: 'auto',
                            whiteSpace: 'pre-wrap',
                            wordBreak: 'break-all',
                            margin: 0,
                          }}
                        >
                          {previewLogs.join('\n')}
                        </pre>
                      </>
                    )}
                  </>
                );
              })()}
          </div>
        )}

        {/* ── Code ── */}
        {tab === 'Code' && (
          <div className='ide-split'>
            <div className='ide-split-files' style={{width: filesWidth}}>
              <div
                className='ide-side-head'
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                  gap: 8,
                }}
              >
                <span>Explorer {files.length ? `· ${files.length}` : ''}</span>
                {files.length > 0 && (
                  <button
                    className='ide-btn ghost'
                    style={{padding: '2px 8px', fontSize: 11}}
                    disabled={busy === 'code'}
                    title='Download the generated code as a zip'
                    onClick={() =>
                      action(
                        'code',
                        () => api.downloadRunCode(runId, 'repo'),
                        () => 'Code downloaded.',
                      )
                    }
                  >
                    {busy === 'code' ? '…' : '⬇ Zip'}
                  </button>
                )}
              </div>
              {files.length ? (
                <IdeFileTree files={files} activePath={activeFile} onOpen={openFile} />
              ) : (
                <div className='ide-empty' style={{padding: 14}}>
                  No files
                </div>
              )}
            </div>
            <ResizeHandle
              onDelta={(dx) => setFilesWidth((w) => Math.max(160, Math.min(520, w + dx)))}
            />
            <div className='ide-split-main'>
              {files.length > 0 && (
                <div className='ide-mobile-file-picker'>
                  <select
                    className='ide-select'
                    value={activeFile || ''}
                    onChange={(e) => {
                      if (e.target.value) void openFile(e.target.value);
                    }}
                  >
                    <option value=''>Select a file</option>
                    {files.map((f) => (
                      <option key={f} value={f}>
                        {f}
                      </option>
                    ))}
                  </select>
                </div>
              )}
              {openFiles.length > 0 && (
                <div className='ide-tabs'>
                  {openFiles.map((f) => (
                    <div
                      key={f}
                      className={`ide-tab${activeFile === f ? ' active' : ''}`}
                      onClick={() => setActiveFile(f)}
                      title={f}
                    >
                      <FileIcon name={f.split('/').pop() || f} />
                      {f.split('/').pop()}
                      <span className='ide-tab-close' onClick={(e) => closeFile(f, e)}>
                        ×
                      </span>
                    </div>
                  ))}
                </div>
              )}
              {active ? (
                <IdeCodeViewer
                  filename={activeFile || ''}
                  content={active.content}
                  diff={active.diff}
                />
              ) : (
                <div className='ide-empty'>
                  {files.length ? 'Select a file' : 'No generated files'}
                </div>
              )}
            </div>
          </div>
        )}

        {/* ── Tasks ── */}
        {tab === 'Tasks' && (
          <div className='ide-pane' style={{position: 'absolute', inset: 0, overflowY: 'auto'}}>
            <div className='ide-h1'>Steps</div>
            {run?.runGoalDoneness && (
              <div className='ide-card' style={{marginBottom: 12}}>
                <div style={{display: 'flex', alignItems: 'center', gap: 8}}>
                  <IdeBadge kind={goalStateKind(run.runGoalDoneness.state)}>
                    {run.runGoalDoneness.state || 'UNKNOWN'}
                  </IdeBadge>
                  {run.runGoalDoneness.recommendedDecision && (
                    <span className='ide-mono' style={{fontSize: 12}}>
                      {run.runGoalDoneness.recommendedDecision}
                    </span>
                  )}
                </div>
                {run.runGoalDoneness.reason && (
                  <div style={{fontSize: 13, marginTop: 8}}>{run.runGoalDoneness.reason}</div>
                )}
                {run.runGoalDoneness.recommendedNextStep && (
                  <div style={{fontSize: 12, marginTop: 8, color: 'var(--ide-fg-muted)'}}>
                    Next: {run.runGoalDoneness.recommendedNextStep}
                  </div>
                )}
              </div>
            )}
            {!run?.tasks?.length ? (
              <div className='ide-empty'>No steps.</div>
            ) : (
              <div className='ide-card' style={{padding: 0}}>
                {run.tasks.map((t, i) => {
                  const goalLine = taskGoalLine(t);
                  const continuationActionLine = latestContinuationAction(t);
                  const budgetFacts = taskBudgetFacts(t);
                  const incompleteItems = t.goalDoneness?.incompleteItems || [];
                  const risks = t.goalDoneness?.risks || [];
                  const approvalCandidate = continuationApprovalCandidate(t);
                  const approvalReason = continuationApprovalReason(t);
                  const policy = t.continuationDecision?.policy;
                  const rankingCandidates = policy?.topEligibleCandidates?.slice(0, 3) || [];
                  const rankingDetails = policy ? rankingPolicyDetails(policy) : [];
                  const rejectedRankingCount =
                    policy?.rejectedBelowQualityThresholdCount ??
                    policy?.topRejectedCandidates?.length ??
                    0;
                  return (
                    <div
                      key={t.taskId}
                      style={{
                        padding: '12px 16px',
                        borderBottom:
                          i < run.tasks.length - 1 ? '1px solid var(--ide-border)' : 'none',
                      }}
                    >
                      <div style={{display: 'flex', alignItems: 'center', gap: 10}}>
                        <IdeBadge
                          kind={
                            t.status === 'COMPLETE' || t.status === 'SUCCESS'
                              ? 'ok'
                              : t.status === 'FAILED'
                                ? 'bad'
                                : 'warn'
                          }
                        >
                          {t.status}
                        </IdeBadge>
                        <span style={{flex: 1, fontWeight: 500}}>{t.title}</span>
                        <span style={{fontSize: 11, color: 'var(--ide-fg-muted)'}}>
                          {fmtDur(t.durationMs)}
                        </span>
                        {(t.status === 'FAILED' ||
                          t.status === 'PARTIAL' ||
                          t.status === 'BLOCKED') && (
                          <button
                            className='ide-btn ghost'
                            style={{padding: '3px 8px'}}
                            disabled={busy === t.taskId}
                            onClick={() =>
                              action(
                                t.taskId,
                                () => api.retryTask(runId, t.taskId),
                                () => 'Retry started.',
                              )
                            }
                          >
                            Retry
                          </button>
                        )}
                        {approvalCandidate && (
                          <button
                            className='ide-btn primary'
                            style={{padding: '3px 8px'}}
                            disabled={busy === `continuation-${t.taskId}`}
                            title={`Approve continuation with ${approvalCandidate.provider}/${approvalCandidate.model}`}
                            onClick={() =>
                              action(
                                `continuation-${t.taskId}`,
                                () => api.approveContinuation(runId, t.taskId),
                                (r) =>
                                  typeof r === 'object' && r && 'message' in r
                                    ? String(r.message)
                                    : 'Continuation approved.',
                              )
                            }
                          >
                            Approve upgrade
                          </button>
                        )}
                      </div>
                      {t.failureReason && (
                        <div style={{fontSize: 12, color: 'var(--ide-red)', marginTop: 4}}>
                          {t.failureReason}
                        </div>
                      )}
                      <div style={{fontSize: 11, color: 'var(--ide-fg-muted)', marginTop: 4}}>
                        {(t.provider || '').toLowerCase()} · {t.model || t.assignedModel} ·{' '}
                        {num(t.inputTokens + t.outputTokens)} tok
                      </div>
                      {goalLine && (
                        <div style={{fontSize: 11, color: 'var(--ide-fg-muted)', marginTop: 4}}>
                          {goalLine}
                        </div>
                      )}
                      {approvalReason && (
                        <div style={{fontSize: 11, color: 'var(--ide-amber)', marginTop: 4}}>
                          {approvalReason}
                        </div>
                      )}
                      {continuationActionLine && (
                        <div style={{fontSize: 11, color: 'var(--ide-fg-muted)', marginTop: 4}}>
                          {continuationActionLine}
                        </div>
                      )}
                      {t.goalDoneness?.recommendedNextStep && (
                        <div style={{fontSize: 11, color: 'var(--ide-fg-muted)', marginTop: 4}}>
                          Next: {t.goalDoneness.recommendedNextStep}
                        </div>
                      )}
                      {policy?.rankingPolicy && rankingCandidates.length > 0 && (
                        <div
                          style={{
                            border: '1px solid var(--ide-border)',
                            borderRadius: 6,
                            padding: '7px 8px',
                            marginTop: 8,
                            fontSize: 11,
                            color: 'var(--ide-fg-muted)',
                          }}
                        >
                          <div
                            style={{
                              display: 'flex',
                              gap: 8,
                              alignItems: 'center',
                              flexWrap: 'wrap',
                              marginBottom: 5,
                            }}
                          >
                            <span style={{color: 'var(--ide-fg-bright)'}}>Model ranking</span>
                            <span className='ide-mono'>{rankingLabel(policy.rankingPolicy)}</span>
                            {rankingDetails.map((detail) => (
                              <span key={detail}>{detail}</span>
                            ))}
                            {rejectedRankingCount > 0 && (
                              <span>{rejectedRankingCount} below quality bar</span>
                            )}
                          </div>
                          <div style={{display: 'grid', gap: 4}}>
                            {rankingCandidates.map((candidate, idx) => (
                              <div
                                key={`${candidate.provider || 'provider'}:${candidate.model || idx}`}
                                style={{
                                  display: 'grid',
                                  gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))',
                                  gap: 8,
                                  alignItems: 'center',
                                }}
                              >
                                <span className='ide-mono' style={{overflowWrap: 'anywhere'}}>
                                  {idx + 1}. {candidateLabel(candidate)}
                                </span>
                                <span>{candidateMetrics(candidate)}</span>
                              </div>
                            ))}
                          </div>
                        </div>
                      )}
                      {budgetFacts.length > 0 && (
                        <div
                          style={{
                            display: 'grid',
                            gridTemplateColumns: 'repeat(auto-fit, minmax(140px, 1fr))',
                            gap: 6,
                            marginTop: 8,
                          }}
                        >
                          {budgetFacts.map((fact) => (
                            <div
                              key={fact.k}
                              style={{
                                border: '1px solid var(--ide-border)',
                                borderRadius: 6,
                                padding: '6px 8px',
                                minWidth: 0,
                              }}
                            >
                              <div
                                style={{
                                  fontSize: 10,
                                  color: 'var(--ide-fg-muted)',
                                  textTransform: 'uppercase',
                                }}
                              >
                                {fact.k}
                              </div>
                              <div
                                className='ide-mono'
                                style={{
                                  fontSize: 11,
                                  color: 'var(--ide-fg)',
                                  overflowWrap: 'anywhere',
                                }}
                              >
                                {fact.v}
                              </div>
                            </div>
                          ))}
                        </div>
                      )}
                      {(incompleteItems.length > 0 || risks.length > 0) && (
                        <div
                          style={{
                            display: 'grid',
                            gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))',
                            gap: 8,
                            marginTop: 8,
                            fontSize: 11,
                            color: 'var(--ide-fg-muted)',
                          }}
                        >
                          {incompleteItems.length > 0 && (
                            <div>
                              <div style={{color: 'var(--ide-fg-bright)', marginBottom: 3}}>
                                Remaining
                              </div>
                              {incompleteItems.slice(0, 3).map((item) => (
                                <div key={item}>- {item}</div>
                              ))}
                            </div>
                          )}
                          {risks.length > 0 && (
                            <div>
                              <div style={{color: 'var(--ide-fg-bright)', marginBottom: 3}}>
                                Risks
                              </div>
                              {risks.slice(0, 3).map((risk) => (
                                <div key={risk}>- {risk}</div>
                              ))}
                            </div>
                          )}
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
            )}
          </div>
        )}

        {/* ── Logs ── */}
        {tab === 'Logs' &&
          (() => {
            // Drop the RUN_DETAIL dump events before rendering (founder report #4) so the
            // Logs tab shows readable lines, not the raw run-detail JSON. Mirrors the
            // live console's skip in hooks/useSse.ts.
            const visibleLog = eventLog?.filter((ev) => !isNoisyLogEvent(ev)) ?? null;
            return (
              <div className='ide-pane' style={{position: 'absolute', inset: 0, overflowY: 'auto'}}>
                <div className='ide-h1' style={{display: 'flex', alignItems: 'center', gap: 8}}>
                  Activity log{visibleLog?.length ? ` · ${visibleLog.length}` : ''}{' '}
                  <span
                    className='ide-mono'
                    style={{fontSize: 11, color: 'var(--ide-fg-muted)', fontWeight: 400}}
                  >
                    · replay, retained 24h
                  </span>
                  <span style={{flex: 1}} />
                  {/* RES-6 — the replay is a snapshot; let the user re-pull it. */}
                  <button
                    className='ide-btn ghost'
                    style={{padding: '3px 10px', fontSize: 12}}
                    onClick={() =>
                      api
                        .getRunEventLog(runId)
                        .then(setEventLog)
                        .catch(() => {})
                    }
                  >
                    ↻ Refresh
                  </button>
                </div>
                {visibleLog == null ? (
                  <IdeLoader label='Loading event log…' />
                ) : visibleLog.length === 0 ? (
                  <div className='ide-empty'>
                    No log events retained for this run. The live log is kept for 24h after each
                    event.
                  </div>
                ) : (
                  <div
                    style={{
                      borderRadius: 8,
                      border: '1px solid var(--ide-border)',
                      background: 'var(--ide-bg-panel)',
                      padding: 8,
                    }}
                  >
                    {visibleLog.map((ev, i) => {
                      const t = (ev.type || 'log').toLowerCase();
                      const color = t.includes('tool')
                        ? 'var(--ide-accent)'
                        : t.includes('llm') || t.includes('completion')
                          ? 'var(--ide-green)'
                          : t.includes('fail') || t.includes('error')
                            ? 'var(--ide-red)'
                            : t.includes('warn')
                              ? 'var(--ide-yellow)'
                              : 'var(--ide-fg-muted)';
                      return (
                        <div
                          key={i}
                          style={{
                            display: 'flex',
                            gap: 8,
                            padding: '6px 8px',
                            borderBottom:
                              i < visibleLog.length - 1 ? '1px solid var(--ide-border)' : 'none',
                            fontSize: 12,
                            lineHeight: 1.5,
                          }}
                        >
                          <span style={{flexShrink: 0}} aria-hidden='true'>
                            {logIcon(ev.type)}
                          </span>
                          <span
                            className='ide-mono'
                            style={{
                              color,
                              flexShrink: 0,
                              textTransform: 'uppercase',
                              fontSize: 10,
                              minWidth: 92,
                            }}
                          >
                            {ev.type || 'log'}
                          </span>
                          {ev.timestamp != null && (
                            <span
                              className='ide-mono'
                              style={{color: 'var(--ide-fg-muted)', flexShrink: 0, fontSize: 10}}
                            >
                              {new Date(ev.timestamp as string | number).toLocaleTimeString()}
                            </span>
                          )}
                          <span style={{wordBreak: 'break-word'}}>{logMessage(ev)}</span>
                        </div>
                      );
                    })}
                  </div>
                )}
              </div>
            );
          })()}

        {/* ── Timeline ── */}
        {tab === 'Timeline' && (
          <div className='ide-pane' style={{position: 'absolute', inset: 0, overflowY: 'auto'}}>
            <div className='ide-h1'>Timeline</div>
            {timeline && (timeline.tasks || []).length > 0 && (
              <div className='ide-card' style={{marginBottom: 12}}>
                <RunTimeline tasks={timeline.tasks} />
              </div>
            )}
            {timeline == null ? (
              <IdeLoader label='Loading timeline…' />
            ) : (timeline.tasks || []).length === 0 ? (
              <div className='ide-empty'>No timeline data yet. Tasks populate as the run runs.</div>
            ) : (
              <div className='ide-card'>
                {(() => {
                  const allTasks = timeline.tasks || [];
                  const starts = allTasks.map((t) => new Date(t.startedAt).getTime());
                  const ends = allTasks.map((t) =>
                    t.completedAt ? new Date(t.completedAt).getTime() : Date.now(),
                  );
                  const minT = Math.min(...starts);
                  const maxT = Math.max(...ends);
                  const span = Math.max(1, maxT - minT);
                  return allTasks.map((t, i) => {
                    const start = new Date(t.startedAt).getTime();
                    const end = t.completedAt ? new Date(t.completedAt).getTime() : Date.now();
                    const leftPct = ((start - minT) / span) * 100;
                    const widthPct = Math.max(2, ((end - start) / span) * 100);
                    const st = (t.status || '').toUpperCase();
                    const done = st === 'COMPLETED' || st === 'COMPLETE' || st === 'DONE';
                    const failed = st === 'FAILED';
                    return (
                      <div key={t.taskId || i} style={{marginBottom: 14}}>
                        <div
                          style={{
                            display: 'flex',
                            justifyContent: 'space-between',
                            gap: 8,
                            marginBottom: 5,
                          }}
                        >
                          <span
                            style={{
                              fontSize: 13,
                              color: 'var(--ide-fg-bright)',
                              flex: 1,
                              minWidth: 0,
                              whiteSpace: 'nowrap',
                              overflow: 'hidden',
                              textOverflow: 'ellipsis',
                            }}
                          >
                            {t.title || `Task ${i + 1}`}
                          </span>
                          <span
                            className='ide-mono'
                            style={{fontSize: 11, color: 'var(--ide-fg-muted)', flexShrink: 0}}
                          >
                            {fmtDur(end - start)}
                          </span>
                        </div>
                        <div
                          style={{
                            position: 'relative',
                            height: 8,
                            background: 'var(--ide-bg)',
                            borderRadius: 4,
                            overflow: 'hidden',
                          }}
                        >
                          <div
                            style={{
                              position: 'absolute',
                              left: `${leftPct}%`,
                              width: `${widthPct}%`,
                              top: 0,
                              bottom: 0,
                              borderRadius: 4,
                              background: failed
                                ? 'var(--ide-red)'
                                : done
                                  ? 'var(--ide-green)'
                                  : 'var(--ide-yellow)',
                            }}
                          />
                        </div>
                      </div>
                    );
                  });
                })()}
              </div>
            )}
          </div>
        )}

        {/* ── Validation ── */}
        {tab === 'Validation' && (
          <div className='ide-pane' style={{position: 'absolute', inset: 0, overflowY: 'auto'}}>
            <div className='ide-h1' style={{display: 'flex', alignItems: 'center', gap: 10}}>
              Evidence
              {report && (
                <button
                  className='ide-btn ghost'
                  style={{marginLeft: 'auto', padding: '4px 10px'}}
                  onClick={() =>
                    action(
                      'pdf2',
                      () => api.downloadValidationReportPdf(runId),
                      () => 'Downloaded.',
                    )
                  }
                >
                  ⬇ PDF
                </button>
              )}
            </div>
            {!report ? (
              <div className='ide-empty'>No evidence report.</div>
            ) : (
              <>
                <div className='ide-card'>
                  <Kv k='Pipeline' v={report.pipelineResult} />
                  <Kv k='Files generated' v={report.filesGenerated} />
                  <Kv k='Build breakers' v={report.buildBreakers} />
                  <Kv k='Review failures' v={report.reviewFailures} />
                  <Kv k='Issues caught' v={report.issuesCaught} />
                </div>
                {report.runGoalDecision && (
                  <>
                    <div className='ide-h2'>Final goal proof</div>
                    <div className='ide-card'>
                      <div style={{display: 'flex', alignItems: 'center', gap: 8}}>
                        <IdeBadge kind={goalStateKind(report.runGoalDecision.state)}>
                          {report.runGoalDecision.state || 'UNKNOWN'}
                        </IdeBadge>
                        {report.runGoalDecision.recommendedDecision && (
                          <span className='ide-mono' style={{fontSize: 12}}>
                            {report.runGoalDecision.recommendedDecision}
                          </span>
                        )}
                      </div>
                      {report.runGoalDecision.reason && (
                        <div style={{fontSize: 13, marginTop: 8}}>
                          {report.runGoalDecision.reason}
                        </div>
                      )}
                      {report.runGoalDecision.recommendedNextStep && (
                        <div style={{fontSize: 12, marginTop: 8, color: 'var(--ide-fg-muted)'}}>
                          Next: {report.runGoalDecision.recommendedNextStep}
                        </div>
                      )}
                      {report.runGoalDecision.incompleteItems &&
                        report.runGoalDecision.incompleteItems.length > 0 && (
                          <div style={{marginTop: 10}}>
                            <div
                              className='ide-mono'
                              style={{fontSize: 11, color: 'var(--ide-fg-muted)'}}
                            >
                              Remaining proof
                            </div>
                            <ul style={{margin: '6px 0 0 18px', padding: 0, fontSize: 12}}>
                              {report.runGoalDecision.incompleteItems.slice(0, 4).map((item, i) => (
                                <li key={i}>{item}</li>
                              ))}
                            </ul>
                          </div>
                        )}
                      {report.runGoalDecision.risks && report.runGoalDecision.risks.length > 0 && (
                        <div style={{marginTop: 10}}>
                          <div
                            className='ide-mono'
                            style={{fontSize: 11, color: 'var(--ide-fg-muted)'}}
                          >
                            Risks
                          </div>
                          <ul style={{margin: '6px 0 0 18px', padding: 0, fontSize: 12}}>
                            {report.runGoalDecision.risks.slice(0, 4).map((risk, i) => (
                              <li key={i}>{risk}</li>
                            ))}
                          </ul>
                        </div>
                      )}
                    </div>
                  </>
                )}
                {report.verificationLedger && report.verificationLedger.length > 0 && (
                  <>
                    <div className='ide-h2'>Evidence ledger</div>
                    <div className='ide-card' style={{padding: 0}}>
                      {report.verificationLedger.map((e, i) => (
                        <div
                          key={e.id || i}
                          style={{
                            padding: '11px 16px',
                            borderBottom:
                              i < (report.verificationLedger?.length ?? 0) - 1
                                ? '1px solid var(--ide-border)'
                                : 'none',
                          }}
                        >
                          <div style={{display: 'flex', gap: 8, alignItems: 'center'}}>
                            <IdeBadge kind={evidenceKind(e.status)}>{e.status}</IdeBadge>
                            <span style={{fontWeight: 600}}>{e.label}</span>
                            <span
                              className='ide-mono'
                              style={{fontSize: 11, color: 'var(--ide-fg-muted)'}}
                            >
                              {e.source}
                            </span>
                            <span
                              className='ide-mono'
                              style={{
                                fontSize: 11,
                                color: confidenceColor(e.confidence),
                                marginLeft: 'auto',
                              }}
                            >
                              {e.confidence}
                            </span>
                          </div>
                          <div style={{fontSize: 13, marginTop: 4}}>{e.summary}</div>
                          <div
                            className='ide-mono'
                            style={{fontSize: 11, color: 'var(--ide-fg-muted)', marginTop: 4}}
                          >
                            {e.artifact}
                            {e.total != null && e.observed != null
                              ? ` · ${e.observed}/${e.total}`
                              : ''}
                          </div>
                        </div>
                      ))}
                    </div>
                  </>
                )}
                <div className='ide-h2'>Issues · {issues.length}</div>
                {issues.length === 0 ? (
                  <div className='ide-empty'>No issues flagged.</div>
                ) : (
                  <div className='ide-card' style={{padding: 0}}>
                    {issues.map((is, i) => {
                      const sv = SEV[is.severity] ?? {l: is.severity, k: 'warn' as const};
                      return (
                        <div
                          key={i}
                          style={{
                            padding: '11px 16px',
                            borderBottom:
                              i < issues.length - 1 ? '1px solid var(--ide-border)' : 'none',
                          }}
                        >
                          <div style={{display: 'flex', gap: 8, alignItems: 'center'}}>
                            <IdeBadge kind={sv.k}>{sv.l}</IdeBadge>
                            {is.filePath && (
                              <span
                                className='ide-mono'
                                style={{fontSize: 11, color: 'var(--ide-fg-muted)'}}
                              >
                                {is.filePath}
                              </span>
                            )}
                            <span
                              style={{
                                fontSize: 10,
                                color: 'var(--ide-fg-muted)',
                                marginLeft: 'auto',
                              }}
                            >
                              {is.stage}
                            </span>
                          </div>
                          <div style={{fontSize: 13, marginTop: 4}}>{is.description}</div>
                          {is.fix && (
                            <div style={{fontSize: 12, color: 'var(--ide-green)', marginTop: 3}}>
                              Fix: {is.fix}
                            </div>
                          )}
                        </div>
                      );
                    })}
                  </div>
                )}
              </>
            )}
          </div>
        )}

        {/* ── Cost ── */}
        {tab === 'Cost' && (
          <div className='ide-pane' style={{position: 'absolute', inset: 0, overflowY: 'auto'}}>
            <div className='ide-h1'>Cost</div>
            <div className='ide-card'>
              <Kv k='Actual cost' v={usd(run?.actualCostUsd)} />
              <Kv k='Hard cap' v={usd(run?.hardCapUsd)} />
              <Kv k='Credits charged' v={run?.creditsCharged ?? '—'} />
              <Kv
                k='Estimate range'
                v={
                  run?.costEstimateLow != null
                    ? `${usd(run.costEstimateLow)} – ${usd(run.costEstimateHigh)}`
                    : '—'
                }
              />
              <Kv k='Input tokens' v={num(run?.totalInputTokens)} />
              <Kv k='Output tokens' v={num(run?.totalOutputTokens)} />
            </div>
            <div className='ide-h2'>Per phase</div>
            <div className='ide-card'>
              <Kv k='Classifier' v={usd(run?.classifierCostUsd)} />
              <Kv k='Outline' v={usd(run?.outlineCostUsd)} />
              <Kv k='Expansion' v={usd(run?.expansionCostUsd)} />
            </div>
          </div>
        )}

        {/* ── PR ── */}
        {tab === 'PR' && (
          <div className='ide-pane' style={{position: 'absolute', inset: 0, overflowY: 'auto'}}>
            <div className='ide-h1'>Pull request</div>
            {msg && (
              <div className='ide-card' style={{marginBottom: 12, color: 'var(--ide-blue)'}}>
                {msg}
              </div>
            )}
            {run?.githubPrUrl ? (
              <>
                <div className='ide-card'>
                  <div
                    className='ide-mono'
                    style={{fontSize: 12, wordBreak: 'break-all', marginBottom: 10}}
                  >
                    {run.githubPrUrl}
                  </div>
                  <a className='ide-btn' href={run.githubPrUrl} target='_blank' rel='noreferrer'>
                    ↗ Open on GitHub
                  </a>
                </div>
                <PrCommentInbox runId={runId} variant='ide' />
              </>
            ) : (
              <div className='ide-card'>
                <div style={{marginBottom: 10, color: 'var(--ide-fg-muted)'}}>
                  No pull request yet. Push the generated changes when you are ready to open one.
                  Pushing requires a GitHub token under Integrations.
                </div>
                <button
                  className='ide-btn'
                  disabled={busy === 'push'}
                  onClick={() =>
                    action(
                      'push',
                      () => api.pushToScm(runId),
                      () => 'Pushed — PR opening.',
                    )
                  }
                >
                  {busy === 'push' ? 'Pushing…' : '⤴ Push to GitHub'}
                </button>
              </div>
            )}
          </div>
        )}

        {/* ── Tests ── */}
        {tab === 'Tests' && (
          <div className='ide-pane' style={{position: 'absolute', inset: 0, overflowY: 'auto'}}>
            <div className='ide-h1' style={{display: 'flex', alignItems: 'center', gap: 10}}>
              Tests
              <button
                className='ide-btn ghost'
                style={{marginLeft: 'auto', padding: '4px 10px'}}
                disabled={busy === 'runtests'}
                onClick={() =>
                  action(
                    'runtests',
                    () => api.runTests(runId),
                    () => 'Test run queued.',
                  )
                }
              >
                ↻ Re-run tests
              </button>
            </div>
            {!tests ? (
              <div className='ide-empty'>No test report yet.</div>
            ) : (
              <>
                <div className='ide-card' style={{display: 'flex', gap: 18}}>
                  <div>
                    <div style={{fontSize: 22, fontWeight: 700}}>
                      {tests.passed}/{tests.totalTests}
                    </div>
                    <div style={{fontSize: 11, color: 'var(--ide-fg-muted)'}}>passed</div>
                  </div>
                  <div>
                    <div style={{fontSize: 22, fontWeight: 700, color: 'var(--ide-red)'}}>
                      {tests.failed}
                    </div>
                    <div style={{fontSize: 11, color: 'var(--ide-fg-muted)'}}>failed</div>
                  </div>
                  <div>
                    <div style={{fontSize: 22, fontWeight: 700}}>{tests.errors}</div>
                    <div style={{fontSize: 11, color: 'var(--ide-fg-muted)'}}>errors</div>
                  </div>
                  <div
                    style={{
                      marginLeft: 'auto',
                      alignSelf: 'center',
                      fontSize: 12,
                      color: 'var(--ide-fg-muted)',
                    }}
                  >
                    {fmtDur(tests.executionDurationMs)}
                  </div>
                </div>

                {/* Test plan — generated test tasks + compile status. */}
                {(testPlan?.tasks?.length ?? 0) > 0 && (
                  <>
                    <div className='ide-h2'>Test plan · {testPlan?.tasks.length}</div>
                    <div className='ide-card' style={{padding: 0}}>
                      {(testPlan?.tasks ?? []).map((tk, i, arr) => (
                        <div
                          key={tk.id}
                          style={{
                            padding: '10px 16px',
                            borderBottom:
                              i < arr.length - 1 ? '1px solid var(--ide-border)' : 'none',
                          }}
                        >
                          <div style={{display: 'flex', alignItems: 'center', gap: 10}}>
                            <IdeBadge
                              kind={
                                tk.status === 'COMPILED'
                                  ? 'ok'
                                  : tk.status === 'FAILED'
                                    ? 'bad'
                                    : 'warn'
                              }
                            >
                              {tk.status}
                            </IdeBadge>
                            <span style={{flex: 1, fontSize: 13, fontWeight: 500}}>
                              {tk.taskName}
                            </span>
                            {tk.testCategory && (
                              <span style={{fontSize: 10, color: 'var(--ide-fg-muted)'}}>
                                {tk.testCategory}
                              </span>
                            )}
                          </div>
                          {tk.compilationError && (
                            <div
                              className='ide-mono'
                              style={{fontSize: 11, color: 'var(--ide-red)', marginTop: 4}}
                            >
                              {tk.compilationError}
                            </div>
                          )}
                        </div>
                      ))}
                    </div>
                  </>
                )}

                {/* Root-cause groups — each renders its suggestedFix via Markdown +
                    an "Apply fix as new run" affordance (mirrors Signal ResultPage). */}
                {tests.rootCauseGroups?.length > 0 && (
                  <>
                    <div className='ide-h2'>Root causes · {tests.rootCauseGroups.length}</div>
                    {tests.rootCauseGroups.map((g, i) => {
                      const gAny = g as unknown as {
                        signature?: string;
                        description?: string;
                        count?: number;
                        failureCount?: number;
                        suggestion?: string;
                        suggestedFix?: string;
                      };
                      const fix = gAny.suggestedFix || gAny.suggestion;
                      const affected = gAny.failureCount ?? gAny.count ?? 0;
                      return (
                        <div className='ide-card' key={i} style={{marginBottom: 10}}>
                          <div style={{fontSize: 13, fontWeight: 600, marginBottom: 4}}>
                            {gAny.description || gAny.signature || `Root cause ${i + 1}`}
                          </div>
                          <div
                            style={{
                              fontSize: 11,
                              color: 'var(--ide-fg-muted)',
                              marginBottom: fix ? 10 : 0,
                            }}
                          >
                            {affected} affected test{affected === 1 ? '' : 's'}
                          </div>
                          {fix && (
                            <div
                              style={{
                                background: 'var(--ide-bg-input)',
                                padding: '10px 12px',
                                borderRadius: 6,
                                fontSize: 12,
                                lineHeight: 1.5,
                              }}
                            >
                              <Markdown>{fix}</Markdown>
                            </div>
                          )}
                          {gAny.signature && (
                            <button
                              className='ide-btn'
                              style={{marginTop: 10, padding: '4px 12px'}}
                              disabled={busy === `applyfix-${i}`}
                              onClick={() =>
                                action(
                                  `applyfix-${i}`,
                                  async () => {
                                    const r = (await api.applyTestFixes(
                                      runId,
                                      gAny.signature,
                                    )) as unknown as {runId?: string} | undefined;
                                    if (r?.runId) navigate(`/live/${r.runId}`);
                                    return r;
                                  },
                                  (r) => {
                                    const nid = (r as {runId?: string} | undefined)?.runId;
                                    return nid ? 'Fix run started.' : 'Test-fix run queued.';
                                  },
                                )
                              }
                            >
                              {busy === `applyfix-${i}` ? 'Applying…' : '⤳ Apply fix as new run'}
                            </button>
                          )}
                        </div>
                      );
                    })}
                  </>
                )}

                {/* Per-test result rows + mark-invalid on failed/error rows. */}
                {(tests.results?.length ?? 0) > 0 && (
                  <>
                    <div className='ide-h2'>All tests · {tests.results.length}</div>
                    <div className='ide-card' style={{padding: 0}}>
                      {tests.results.slice(0, 60).map((r, i) => {
                        const st = (r.status || 'UNKNOWN').toUpperCase();
                        const bad = st === 'FAILED' || st === 'ERROR';
                        return (
                          <div
                            key={r.testResultId ?? i}
                            style={{
                              padding: '10px 16px',
                              borderBottom:
                                i < Math.min(tests.results.length, 60) - 1
                                  ? '1px solid var(--ide-border)'
                                  : 'none',
                            }}
                          >
                            <div style={{display: 'flex', alignItems: 'center', gap: 10}}>
                              <IdeBadge kind={st === 'PASSED' ? 'ok' : bad ? 'bad' : 'warn'}>
                                {st}
                              </IdeBadge>
                              <span style={{flex: 1, fontSize: 13, minWidth: 0}}>
                                <span style={{fontWeight: 500}}>{r.testMethod}</span>{' '}
                                <span
                                  className='ide-mono'
                                  style={{fontSize: 11, color: 'var(--ide-fg-muted)'}}
                                >
                                  {r.testClass}
                                </span>
                              </span>
                              <span style={{fontSize: 11, color: 'var(--ide-fg-muted)'}}>
                                {fmtDur(r.durationMs)}
                              </span>
                              {bad && r.testResultId != null && (
                                <button
                                  className='ide-btn ghost'
                                  style={{padding: '3px 8px'}}
                                  disabled={busy === `invalid-${r.testResultId}`}
                                  onClick={() =>
                                    setConfirm({
                                      title: 'Mark test invalid',
                                      body: 'Mark this test as invalid?',
                                      confirmLabel: 'Mark invalid',
                                      onConfirm: () =>
                                        action(
                                          `invalid-${r.testResultId}`,
                                          () => api.markTestInvalid(r.testResultId),
                                          () => 'Marked invalid.',
                                        ),
                                    })
                                  }
                                >
                                  Mark invalid
                                </button>
                              )}
                            </div>
                            {r.failureMessage && (
                              <div
                                className='ide-mono'
                                style={{
                                  fontSize: 11,
                                  color: 'var(--ide-red)',
                                  marginTop: 4,
                                  whiteSpace: 'pre-wrap',
                                  wordBreak: 'break-word',
                                }}
                              >
                                {r.failureMessage}
                              </div>
                            )}
                          </div>
                        );
                      })}
                    </div>
                  </>
                )}
              </>
            )}
          </div>
        )}

        {/* ── Chat ── */}
        {tab === 'Chat' && (
          <div
            className='ide-pane'
            style={{position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column'}}
          >
            <div className='ide-h1'>Ask about this work</div>
            <div style={{fontSize: 12, color: 'var(--ide-fg-muted)', marginBottom: 12}}>
              Ask the agent about its decisions, or describe a change and start a fresh iteration
              from this work&apos;s codebase.
            </div>
            {msg && (
              <div className='ide-card' style={{marginBottom: 12, color: 'var(--ide-blue)'}}>
                {msg}
              </div>
            )}
            <div
              style={{
                flex: 1,
                minHeight: 0,
                overflowY: 'auto',
                display: 'flex',
                flexDirection: 'column',
                gap: 10,
                marginBottom: 12,
              }}
            >
              {chat == null ? (
                <IdeLoader label='Loading chat…' />
              ) : chat.length === 0 && !chatBusy ? (
                <div className='ide-empty'>
                  No messages yet. Ask the agent anything about this work.
                </div>
              ) : (
                chat.map((m, i) => {
                  const isUser = m.role === 'user';
                  return (
                    <div
                      key={i}
                      className='ide-card'
                      style={{
                        alignSelf: isUser ? 'flex-end' : 'flex-start',
                        maxWidth: '85%',
                        background: isUser ? 'var(--ide-blue)' : undefined,
                        color: isUser ? 'var(--ide-accent-fg)' : undefined,
                        padding: '10px 14px',
                      }}
                    >
                      <div
                        className='ide-mono'
                        style={{fontSize: 10, opacity: 0.7, marginBottom: 4}}
                      >
                        {isUser ? 'YOU' : 'RUN COPILOT'}
                      </div>
                      {/* Both roles render through Markdown (all-chat-markdown rule). */}
                      <Markdown>{m.content || ''}</Markdown>
                    </div>
                  );
                })
              )}
              {chatBusy && (
                <div style={{alignSelf: 'flex-start', fontSize: 12, color: 'var(--ide-fg-muted)'}}>
                  Thinking…
                </div>
              )}
              <div ref={chatEndRef} />
            </div>
            <div style={{display: 'flex', gap: 8, alignItems: 'flex-end'}}>
              <div style={{display: 'grid', gap: 6, flex: 1}}>
                <textarea
                  className='ide-input'
                  value={chatInput}
                  onChange={(e) => setChatInput(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' && !e.shiftKey) {
                      e.preventDefault();
                      void sendChat();
                    }
                  }}
                  placeholder='Ask anything about this work…'
                  rows={2}
                  disabled={chatBusy}
                  style={{resize: 'vertical', minHeight: 56, maxHeight: 200}}
                />
                {runSlashSuggestions.length > 0 && (
                  <div className='ide-card' style={{padding: 8, display: 'grid', gap: 4}}>
                    {runSlashSuggestions.map((s) => (
                      <button
                        key={s.command}
                        type='button'
                        onClick={() => setChatInput(s.args ? `${s.command} ` : s.command)}
                        style={{
                          border: 0,
                          background: 'transparent',
                          color: 'var(--ide-fg)',
                          display: 'flex',
                          justifyContent: 'space-between',
                          gap: 12,
                          textAlign: 'left',
                          cursor: 'pointer',
                        }}
                      >
                        <span className='ide-mono'>{uiSlashUsage(s)}</span>
                        <span style={{color: 'var(--ide-fg-muted)'}}>{s.description}</span>
                      </button>
                    ))}
                  </div>
                )}
              </div>
              <div style={{display: 'flex', flexDirection: 'column', gap: 6, flexShrink: 0}}>
                <button
                  className='ide-btn'
                  onClick={sendChat}
                  disabled={chatBusy || !chatInput.trim()}
                >
                  Send
                </button>
                <button
                  className='ide-btn ghost'
                  onClick={iterateAsNewRun}
                  disabled={chatBusy || !chatInput.trim()}
                  title='Start follow-up work from this result with your change applied'
                >
                  Start follow-up work
                </button>
              </div>
            </div>
          </div>
        )}
      </div>

      <ConfirmModal
        open={!!confirm}
        title={confirm?.title ?? ''}
        body={confirm?.body ?? ''}
        confirmLabel={confirm?.confirmLabel ?? 'Confirm'}
        danger={confirm?.danger}
        onConfirm={() => confirm?.onConfirm()}
        onClose={() => setConfirm(null)}
      />
    </div>
  );
}
