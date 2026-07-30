import {useEffect, useMemo, useRef, useState} from 'react';
import {Link, useNavigate, useParams} from 'react-router-dom';
import {PrCommentInbox} from '@/signal/components/PrCommentInbox';
import {humanStatusReason, providerLabel} from '@/signal/lib/runUtils';
import {RunTimeline} from '@/ide/RunTimeline';
import {RunTechnicalDetails} from '@/ide/RunTechnicalDetails';
import {LoadError} from '@/ide/LoadError';
import {toLoadFailure, type LoadFailure} from '@/lib/loadState';
import IdeRunScreens from '@/ide/IdeRunScreens';
import Crumbs from '@/ide/Crumbs';
import {useDocumentTitle} from '@/hooks/useDocumentTitle';
import {Markdown} from '@/signal/components/Markdown';
import {ConfirmModal} from '@/signal/components/Modal';
import * as api from '@/lib/api';
import {
  normalizeUiProvider,
  UI_PROVIDER_USAGE,
  uiSlashHelp,
  uiSlashSuggestions,
  uiSlashUsage,
} from '@/lib/uiSlash';
import {agentTurnToRunChatMessages} from '@/ide/agentChatHistory';
import {selectScopedAgentSession, webAgentSessionRequest} from '@/ide/agentSession';
import {streamWebAgentChatReply} from '@/ide/agentChatStream';
import {
  attemptSummary,
  costReconciliation,
  knownCallCost,
  phaseCostRows,
  stepCallsView,
} from '@/ide/runCostLedger';
import {providerIsDispatchable} from '@/ide/providerReadiness';
import {useProviderReadiness} from '@/ide/useProviderReadiness';
import {useAuthStore, selectIsSuperAdmin} from '@/stores/useAuthStore';
import {
  IdeBadge,
  IdeCodeViewer,
  IdeFileTree,
  IdeLoader,
  FileIcon,
  ResizeHandle,
  hunkRevertRequest,
  type DiffLine,
} from '@/ide/IdeKit';
import {ScoreBadge} from '@/ide/ScoreBadge';
import {
  confidenceColor,
  continuationApprovalCandidate,
  continuationApprovalReason,
  countProducedWorkspaceFiles,
  customerContinuationSummary,
  customerLimitSummary,
  customerStepSummary,
  customerStepStatusLabel,
  customerStepTitle,
  evidenceKind,
  emptyCodeTabMessage,
  fileCountSummary,
  fmtDur,
  goalDecisionHelp,
  goalDecisionLabel,
  goalProofKind,
  goalProofLabel,
  goalStateLabel,
  goalStateKind,
  hasRunCostEvidence,
  isNoisyLogEvent,
  isFailedTerminalStatus,
  lineDiff,
  logIcon,
  logLabel,
  logMeta,
  logMessage,
  missingScoreExplanation,
  modelRankingCandidateLabel,
  modelRankingCandidateMetrics,
  modelRankingDetails,
  modelRankingLabel,
  num,
  latestContinuationAction,
  publicationTrustFacts,
  runArtifactActionAvailability,
  friendlyAiFailureMessage,
  runChatResponseContent,
  runPhaseDiagnosis,
  SEV,
  shouldUseRunEvidenceChat,
  shouldShowModelRanking,
  isTestExecutionTerminal,
  TEST_EXECUTION_MAX_POLLS,
  TEST_EXECUTION_POLL_INTERVAL_MS,
  TABS,
  TAB_LABEL,
  testRerunState,
  taskBudgetFacts,
  taskGoalLine,
  terminalFailureEvidence,
  testEvidenceSummary,
  timelineTiming,
  testResultSummary,
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

export default function IdeRunResult() {
  const params = useParams();
  const navigate = useNavigate();
  const isSuperAdmin = useAuthStore(selectIsSuperAdmin);
  const providerReadiness = useProviderReadiness();
  const runId = params.runId || params.runShort || '';
  const [run, setRun] = useState<api.RunDetail | null>(null);
  const [costEvidence, setCostEvidence] = useState<api.RunCostEvidence | null>(null);
  const [report, setReport] = useState<api.ValidationReportData | null>(null);
  const [publication, setPublication] = useState<api.PublicationEligibility | null>(null);
  const [publicationFailure, setPublicationFailure] = useState<LoadFailure | null>(null);
  // The validation report is produced after the executor's task-boundary decision and can carry
  // stronger final proof. Prefer it so Steps does not keep saying "Keep working" while Evidence
  // already says the completed run is proven.
  const effectiveRunGoal = report?.runGoalDecision ?? run?.runGoalDoneness;
  const goalProofReferences = report?.goalProofReferences ?? [];
  // D4 — the run-detail surfaces below all render affirmative absence claims
  // ("No evidence report.", "No files", "No timeline data yet.", and the
  // fabricated "patch and benchmark runs aren't scored" explanation). Track the
  // failure separately so a fetch error can never masquerade as one of them.
  const [reportFailure, setReportFailure] = useState<LoadFailure | null>(null);
  const [filesFailure, setFilesFailure] = useState<LoadFailure | null>(null);
  const [timelineFailure, setTimelineFailure] = useState<LoadFailure | null>(null);
  const [tests, setTests] = useState<api.TestReportData | null>(null);
  // D4 — a failed read here rendered "No test report yet." and disabled the
  // Re-run button with "No generated code or test plan is available to re-run".
  const [testsFailure, setTestsFailure] = useState<LoadFailure | null>(null);
  const [costEvidenceFailure, setCostEvidenceFailure] = useState<LoadFailure | null>(null);
  // RR-1552 — separates "the ledger was withheld from this reader" from "this run made no
  // provider calls"; both used to render as the latter.
  const stepCalls = useMemo(() => stepCallsView(costEvidence), [costEvidence]);
  const [testPlan, setTestPlan] = useState<api.TestPlanData | null>(null);
  const [testRunPending, setTestRunPending] = useState(false);
  const [files, setFiles] = useState<string[]>([]);
  const [producedWorkspaceFileCount, setProducedWorkspaceFileCount] = useState(0);
  const [openFiles, setOpenFiles] = useState<string[]>([]);
  const [activeFile, setActiveFile] = useState<string | null>(null);
  // RR-767 — a cache entry is either loaded source (`content`/`diff`) or an
  // explicit `failure`. A failed read must NOT be stored as diagnostic prose in
  // `content`, which the code viewer would render indistinguishably from real
  // workspace source (copyable, reviewable, revertable).
  const [cache, setCache] = useState<
    Record<string, {content: string; diff: DiffLine[] | null; failure?: LoadFailure}>
  >({});
  const [filesWidth, setFilesWidth] = useState(240);
  const [tab, setTab] = useState<Tab>('Overview');
  const [tabMenuOpen, setTabMenuOpen] = useState(false);
  const [msg, setMsg] = useState('');
  const [busy, setBusy] = useState('');
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<'not-found' | 'forbidden' | 'error' | null>(null);
  // Single confirm-modal slot — multiple destructive actions on this page
  // (delete run / deploy / stop preview / mark-test-invalid) drive it via a
  // discriminated payload that carries the action to run on confirm.
  const [confirm, setConfirm] = useState<{
    title: string;
    body: string;
    confirmLabel: string;
    danger?: boolean;
    onConfirm: () => void | Promise<void>;
  } | null>(null);
  // RR-763 — a one-shot pending latch so a confirmation dialog cannot fire its
  // (paid / destructive) callback twice while the first is still in flight. The
  // ref decides synchronously; the state drives the disabled/'…' rendering.
  const [confirmBusy, setConfirmBusy] = useState(false);
  const confirmBusyRef = useRef(false);
  // RR-768 — the single in-flight exclusive run-lifecycle op. A second conflicting
  // mutation is refused while this is set, and the shared busy label is cleared
  // only by the op that still owns it (no last-writer-wins clobber).
  const exclusiveBusyRef = useRef<string | null>(null);
  // RES-1 — run-management overflow menu (Fork / Archive / Delete).
  const [moreOpen, setMoreOpen] = useState(false);
  // Timeline + Chat — IDE parity with Signal ResultPage.
  const [timeline, setTimeline] = useState<api.TimelineData | null>(null);
  const [chat, setChat] = useState<api.RunChatMessage[] | null>(null);
  // RR-766 — a failed history read is not an empty conversation. Track it so the
  // panel shows a retryable error instead of a false "No messages yet".
  const [chatFailure, setChatFailure] = useState<LoadFailure | null>(null);
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
  const [eventReplay, setEventReplay] = useState<api.RunReplayLog | null>(null);

  async function loadEventLog() {
    const replay = await api.getRunEventLog(runId);
    setEventReplay(replay);
    setEventLog(replay.events);
  }

  // RR-757 — the [runId] load effect launches run/report/files requests. Without a request
  // generation it can commit run A's response under run B's URL after navigation or out-of-order
  // completion — an evidence-integrity issue that can also surface stale data across project
  // contexts. `loadGen` is bumped per load; every commit checks it, and openFile() captures it too.
  const loadGen = useRef(0);

  // D4 — extracted so the Overview and Evidence failure states can offer Retry.
  function reloadReport() {
    if (!runId) return;
    const gen = loadGen.current;
    api
      .getValidationReport(runId)
      .then((r) => {
        if (gen !== loadGen.current) return;
        setReport(r);
        setReportFailure(null);
      })
      .catch((e: unknown) => {
        if (gen !== loadGen.current) return;
        setReportFailure(toLoadFailure(e));
      });
  }

  function reloadPublicationEligibility() {
    if (!runId) return;
    const gen = loadGen.current;
    api
      .getPublicationEligibility(runId)
      .then((result) => {
        if (gen !== loadGen.current) return;
        setPublication(result);
        setPublicationFailure(null);
      })
      .catch((e: unknown) => {
        if (gen !== loadGen.current) return;
        setPublication(null);
        setPublicationFailure(toLoadFailure(e));
      });
  }

  useEffect(() => {
    if (!runId) return;
    const gen = ++loadGen.current;
    // Reset run-scoped state synchronously so the previous run's data is never shown under the new
    // URL while requests are in flight (RR-757). This includes lazy tab data and the selected tab:
    // React keeps this component mounted when /result/:runId changes, so retaining either would
    // render evidence from run A under run B until the user manually changed tabs.
    setTab('Overview');
    setTabMenuOpen(false);
    setRun(null);
    setCostEvidence(null);
    setCostEvidenceFailure(null);
    setReport(null);
    setReportFailure(null);
    setPublication(null);
    setPublicationFailure(null);
    setTests(null);
    setTestsFailure(null);
    setTestPlan(null);
    setFiles([]);
    setProducedWorkspaceFileCount(0);
    setFilesFailure(null);
    setOpenFiles([]);
    setActiveFile(null);
    setCache({});
    setTimeline(null);
    setTimelineFailure(null);
    setChat(null);
    setChatFailure(null);
    setRunAgentSessionId(null);
    setChatInput('');
    setEventLog(null);
    setEventReplay(null);
    setPreview(null);
    setPreviewLogs(null);
    setPreviewStarted(false);
    setMsg('');
    setBusy('');
    setLoadError(null);
    setLoading(true);
    api
      .getRun(runId)
      .then((r) => {
        if (gen !== loadGen.current) return;
        setRun(r);
      })
      .catch((e: unknown) => {
        if (gen !== loadGen.current) return;
        const status = typeof e === 'object' && e ? Number((e as {status?: number}).status) : 0;
        setLoadError(status === 404 ? 'not-found' : status === 403 ? 'forbidden' : 'error');
      })
      .finally(() => {
        if (gen === loadGen.current) setLoading(false);
      });
    reloadReport();
    reloadPublicationEligibility();
    api
      .getFilesWithStatus(runId)
      .then((entries) => {
        if (gen !== loadGen.current) return;
        const f = entries.map((entry) => entry.path);
        setFiles(f);
        setProducedWorkspaceFileCount(countProducedWorkspaceFiles(entries));
        setFilesFailure(null);
        if (f.length) openFile(f[0]);
      })
      .catch((e: unknown) => {
        if (gen !== loadGen.current) return;
        setFilesFailure(toLoadFailure(e));
      });
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
        .then((t) => {
          setTests(t);
          setTestsFailure(null);
        })
        .catch((e: unknown) => setTestsFailure(toLoadFailure(e)));
    if (tab === 'Tests' && !testPlan)
      api
        .getTestPlan(runId)
        .then(setTestPlan)
        .catch(() => setTestPlan(null));
    if (tab === 'Timeline' && !timeline)
      api
        .getTimeline(runId)
        .then((t) => {
          setTimeline(t);
          setTimelineFailure(null);
        })
        .catch((e: unknown) => setTimelineFailure(toLoadFailure(e)));
    if (tab === 'Logs' && !eventLog)
      void loadEventLog().catch(() => {
        setEventReplay({
          events: [],
          available: false,
          reason: 'STORE_UNAVAILABLE',
          retainedUntil: null,
          lastEventId: null,
          truncated: false,
        });
        setEventLog([]);
      });
    if (tab === 'Cost' && !costEvidence)
      api
        .getLiveCost(runId)
        .then((c) => {
          setCostEvidence(c);
          setCostEvidenceFailure(null);
        })
        .catch((e: unknown) => setCostEvidenceFailure(toLoadFailure(e)));
    // RR-766 — do not re-fire the load when the last attempt failed (chat stays
    // null on failure); the Retry button owns the re-attempt.
    if (tab === 'Chat' && !chat && !chatFailure) void loadRunChat();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tab]);

  // Manual test execution is accepted asynchronously. Reconcile the report
  // and plan until the backend marks the plan terminal, so the Tests tab does
  // not remain stuck at the pre-execution 0/0 snapshot. The bound prevents a
  // broken preview/runner from creating an unbounded request loop.
  useEffect(() => {
    if (!runId || !testRunPending) return;
    let cancelled = false;
    let polls = 0;
    let timer: ReturnType<typeof setTimeout> | null = null;

    const poll = async () => {
      polls += 1;
      // RR-758 — a single rejected read used to escape as an unhandled rejection
      // that never scheduled another timer and never cleared `testRunPending`,
      // leaving a permanent "Tests running" banner + disabled rerun. Handle the
      // failure: keep polling within the budget, then terminate into an explicit
      // recoverable failure state rather than spinning forever.
      let nextTests: Awaited<ReturnType<typeof api.getTestReport>> | null = null;
      let nextPlan: Awaited<ReturnType<typeof api.getTestPlan>> | null = null;
      try {
        [nextTests, nextPlan] = await Promise.all([
          api.getTestReport(runId),
          api.getTestPlan(runId),
        ]);
      } catch (e: unknown) {
        if (cancelled) return;
        if (polls >= TEST_EXECUTION_MAX_POLLS) {
          setTestRunPending(false);
          setTestsFailure(toLoadFailure(e));
          return;
        }
        timer = setTimeout(poll, TEST_EXECUTION_POLL_INTERVAL_MS);
        return;
      }
      if (cancelled) return;
      if (nextTests) setTests(nextTests);
      if (nextPlan) setTestPlan(nextPlan);
      // A recovered poll clears any earlier failure so a stale error cannot keep
      // hiding a now-successful result.
      setTestsFailure(null);

      const status = nextTests?.status || nextPlan?.status;
      if (isTestExecutionTerminal(status)) {
        setTestRunPending(false);
        setMsg(
          status?.toUpperCase() === 'FAILED'
            ? 'Test run failed. Review the test errors below.'
            : 'Test run complete — results refreshed.',
        );
        return;
      }
      if (polls >= TEST_EXECUTION_MAX_POLLS) {
        setTestRunPending(false);
        setMsg('Test run is still processing. Refresh Tests later to see the final report.');
        return;
      }
      timer = setTimeout(poll, TEST_EXECUTION_POLL_INTERVAL_MS);
    };

    void poll();
    return () => {
      cancelled = true;
      if (timer) clearTimeout(timer);
    };
  }, [runId, testRunPending]);

  useEffect(() => {
    if (!runId || eventLog) return;
    const s = (run?.status || '').toUpperCase();
    if (!['REVIEWING', 'VALIDATING', 'GENERATING_REPORT', 'REPORTING'].includes(s)) return;
    void api
      .getRunEventLog(runId)
      .then((replay) => {
        setEventReplay(replay);
        setEventLog(replay.events);
      })
      // D4: this used to fall through to "No activity is retained for this
      // run." The honest STORE_UNAVAILABLE branch already exists two effects
      // up; route the failure into it instead of inventing a retention claim.
      .catch(() => {
        setEventReplay({
          events: [],
          available: false,
          reason: 'STORE_UNAVAILABLE',
          retainedUntil: null,
          lastEventId: null,
          truncated: false,
        });
        setEventLog([]);
      });
  }, [eventLog, run?.status, runId]);

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
    const gen = loadGen.current; // RR-757: discard reads that resolve after a run change.
    try {
      const d = await api.getFileDiff(runId, path);
      if (gen !== loadGen.current) return;
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
        if (gen !== loadGen.current) return;
        setCache((c) => ({...c, [path]: {content, diff: null}}));
      } catch (e: unknown) {
        if (gen !== loadGen.current) return;
        // RR-767 — record a typed failure, not fabricated source content.
        setCache((c) => ({...c, [path]: {content: '', diff: null, failure: toLoadFailure(e)}}));
      }
    }
  }

  // RR-767 — drop a failed entry and re-read the file for the Retry button.
  function retryOpenFile(path: string) {
    setCache((c) => {
      const next = {...c};
      delete next[path];
      return next;
    });
    void openFile(path);
  }
  function closeFile(path: string, e?: React.MouseEvent) {
    e?.stopPropagation();
    setOpenFiles((o) => {
      const next = o.filter((f) => f !== path);
      if (activeFile === path) setActiveFile(next[next.length - 1] ?? null);
      return next;
    });
  }

  // RR-764 — reconcile the authoritative run + report (+ visible tests) after a
  // mutation settles, so controls do not stay enabled against stale status and
  // invalidated tests/scores reflect the new state without a full reload.
  async function reloadRun() {
    if (!runId) return;
    const gen = loadGen.current;
    try {
      const r = await api.getRun(runId);
      if (gen === loadGen.current) setRun(r);
    } catch {
      // Keep the last-known run; the action already surfaced any failure message.
    }
    reloadReport();
    reloadPublicationEligibility();
    if (tab === 'Tests') {
      try {
        const t = await api.getTestReport(runId);
        if (gen === loadGen.current) {
          setTests(t);
          setTestsFailure(null);
        }
      } catch (e: unknown) {
        if (gen === loadGen.current) setTestsFailure(toLoadFailure(e));
      }
    }
  }

  // RR-768 — downloads are independent and non-conflicting; every other op is a
  // run-lifecycle mutation that must be serialized against the others.
  function isExclusiveAction(name: string): boolean {
    return name !== 'pdf' && name !== 'pdf2' && name !== 'code' && name !== 'share';
  }

  async function action(
    name: string,
    fn: () => Promise<unknown>,
    ok: (r: unknown) => string,
    settle?: () => void | Promise<void>,
  ) {
    const exclusive = isExclusiveAction(name);
    // RR-768 — refuse a second conflicting mutation while one is already running.
    if (exclusive && exclusiveBusyRef.current) return;
    if (exclusive) exclusiveBusyRef.current = name;
    setBusy(name);
    setMsg('');
    try {
      const r = await fn();
      setMsg(ok(r));
      // RR-764 — settle the affected resources on success.
      if (settle) await settle();
    } catch (e: unknown) {
      setMsg(e instanceof Error ? e.message : 'Failed.');
    } finally {
      if (exclusive) exclusiveBusyRef.current = null;
      // RR-768 — clear the label only if this op still owns it.
      setBusy((prev) => (prev === name ? '' : prev));
    }
  }

  // RR-762 — human label for the SCM provider that opened the PR; falls back to the neutral "SCM".
  function scmProviderLabel(provider?: string | null): string {
    switch ((provider || '').toUpperCase()) {
      case 'GITHUB':
        return 'GitHub';
      case 'BITBUCKET':
        return 'Bitbucket';
      default:
        return 'SCM';
    }
  }

  // RR-897 — /push-scm returns HTTP 200 for logical FAILED / NO_CONFIGURATION / PARTIAL / NO_CHANGES
  // outcomes, so an unconditional "Pushed — PR opening." success message hid missing credentials, an
  // empty diff, total failure and partial multi-repo failure. Map the logical status to honest copy;
  // only a real success claims a PR is opening.
  function pushOutcomeMessage(r: unknown): string {
    const res = (r ?? {}) as {
      status?: string;
      repoUrl?: string;
      prUrl?: string;
      message?: string;
      code?: string;
      publicationEligible?: boolean;
      retryable?: boolean;
      retryGuidance?: string;
    };
    const status = (res.status || '').toUpperCase();
    const eligibility = res.publicationEligible === false ? 'Publication ineligible' : undefined;
    const retry =
      res.retryable === false && status === 'FAILED' ? 'Direct retry disabled' : undefined;
    const diagnostic = [eligibility, retry, res.code, res.message, res.retryGuidance]
      .filter(Boolean)
      .join(' · ');
    switch (status) {
      case 'SUCCESS':
      case 'OK':
      case 'PUSHED':
        return res.prUrl ? `Pushed — PR opening at ${res.prUrl}` : 'Pushed successfully.';
      case 'NO_CHANGES':
        return diagnostic || 'Nothing to push — the workspace has no changes.';
      case 'NO_CONFIGURATION':
        return (
          diagnostic ||
          'No repository provider is configured for this run — connect one under Integrations first.'
        );
      case 'PARTIAL':
        return (
          diagnostic ||
          'Partial push — some repositories failed. Check the PR tab for per-repo status.'
        );
      case 'FAILED':
        return diagnostic || 'Push failed — no pull request was opened.';
      default:
        return diagnostic || (status ? `Push result: ${status}` : 'Push complete.');
    }
  }

  async function rerunTests() {
    if (!runId || testRunPending || busy === 'runtests') return;
    setBusy('runtests');
    setMsg('');
    try {
      await api.runTests(runId);
      setTestRunPending(true);
      setMsg('Test run queued — waiting for results…');
    } catch (e: unknown) {
      setMsg(e instanceof Error ? e.message : 'Could not queue tests.');
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

  function requestHunkRevert(filePath: string, diff: DiffLine[], hunkIndex: number) {
    if (!runId || !diff.length || !run) return;
    const request = hunkRevertRequest(filePath, diff, hunkIndex);
    if (!request) return;
    setConfirm({
      title: `Prepare revert for hunk ${hunkIndex + 1}`,
      body: 'This creates a fresh forked run containing only the requested hunk reversal. It does not mutate this run or your repository. You will review the new plan and diff before approval; run credits apply only after approval.',
      confirmLabel: 'Create revert run',
      onConfirm: async () => {
        setConfirm(null);
        setBusy('revert-hunk');
        setMsg('Creating a reviewable revert run…');
        try {
          const fork = await api.forkRun(runId, {userRequest: request});
          navigate(`/plan/${fork.runId}`);
        } catch (e: unknown) {
          setMsg(e instanceof Error ? e.message : 'Could not create the revert run.');
        } finally {
          setBusy('');
        }
      },
    });
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

  // RR-766 — durable turns are paginated (limit + `before` cursor). Replay the
  // full transcript in bounded pages instead of silently reading only the first
  // 50, so older evidence is not dropped from the view.
  async function loadAllRunAgentTurns(sessionId: string): Promise<api.AgentTurnResponse[]> {
    const PAGE = 100;
    const MAX_PAGES = 20; // hard bound: at most 2,000 turns rendered
    const byId = new Map<string, api.AgentTurnResponse>();
    let before: string | null = null;
    for (let page = 0; page < MAX_PAGES; page++) {
      const batch = await api.listAgentTurns(sessionId, {limit: PAGE, before});
      if (!batch.length) break;
      const sizeBefore = byId.size;
      for (const t of batch) byId.set(t.turnId, t);
      // Stop if the cursor made no progress (server without a `before` contract
      // would otherwise return the same page forever).
      if (byId.size === sizeBefore || batch.length < PAGE) break;
      // Cursor on the oldest turn seen so far so the next page walks further back.
      before = batch.reduce((min, t) => (t.sequence < min.sequence ? t : min), batch[0]).turnId;
    }
    // Present oldest → newest regardless of page order.
    return [...byId.values()].sort((a, b) => a.sequence - b.sequence);
  }

  async function loadRunChat() {
    if (!runId) return;
    setChatFailure(null);
    // RR-766 — legacy and durable reads fail independently. A real outage must
    // surface as an error, not collapse into a false "No messages yet".
    let legacy: api.RunChatMessage[] = [];
    let legacyFailed = false;
    try {
      legacy = await api.getRunChat(runId);
    } catch {
      legacyFailed = true;
    }
    let sessions: api.AgentSessionResponse[];
    try {
      sessions = await api.listAgentSessions(100);
    } catch (e: unknown) {
      if (legacyFailed) {
        setChatFailure(toLoadFailure(e));
        setChat(null);
      } else {
        setChat(legacy);
      }
      return;
    }
    const session = selectScopedAgentSession(sessions, {projectId: run?.projectId, runId});
    if (!session) {
      setChat(legacy);
      return;
    }
    setRunAgentSessionId(session.id);
    try {
      const turns = await loadAllRunAgentTurns(session.id);
      setChat([...legacy, ...turns.flatMap(agentTurnToRunChatMessages)]);
    } catch (e: unknown) {
      if (legacyFailed) {
        setChatFailure(toLoadFailure(e));
        setChat(null);
      } else {
        // Show the legacy history we do have; durable turns are unavailable.
        setChat(legacy);
      }
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
      if (shouldUseRunEvidenceChat(text)) {
        const reply = await api.sendRunChatMessage(runId, text);
        setChat((prev) => [
          ...(prev || []),
          {
            role: 'assistant',
            content: runChatResponseContent(reply),
          },
        ]);
        return;
      }
      const session = await ensureRunAgentSession();
      const reply = await streamWebAgentChatReply(session.id, text, {
        label: 'run agent',
        href: `/runs/${runId}/agent`,
      });
      setChat((prev) => [
        ...(prev || []),
        {
          role: 'assistant',
          content: friendlyAiFailureMessage(reply),
        },
      ]);
    } catch (e: unknown) {
      setMsg(friendlyAiFailureMessage(e));
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
      const session = await ensureRunAgentSession();
      await api.clearAgentSessionHistory(session.id);
      // RR-766 — only the durable agent-session transcript is clearable; the legacy
      // /runs/{id}/chat history is immutable server-side. Reload so the view reflects
      // the real persisted state (legacy retained) instead of a local blank that
      // silently reappears on the next reload.
      await loadRunChat();
      appendRunChatNote(
        'Cleared this session’s durable history. Earlier read-only run chat is retained and will still appear on reload.',
      );
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
        appendRunChatNote(`Usage: ${UI_PROVIDER_USAGE}`);
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
  const failedTerminal = isFailedTerminalStatus(statusUpper);
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
  const testsEvidence = report?.verificationLedger?.find((row) => row.id === 'tests');
  const testProof = testEvidenceSummary(measuredTestsPassed, measuredTestsTotal, testsEvidence);
  const testsSignal = testProof.measured ? testProof.label : score != null ? 'not measured' : '—';
  const testsSummary = tests ? testResultSummary(tests) : null;
  const scoredWithoutFunctionalTests = score != null && !testProof.functional;
  const prUrl = run?.scmPrUrl || run?.githubPrUrl || null;
  const active = activeFile ? cache[activeFile] : null;
  const issues = report?.issues ?? [];
  const reportedFiles = report?.filesGenerated ?? null;
  const workspaceFiles = producedWorkspaceFileCount;
  const fileCounts = fileCountSummary({reportedFiles, workspaceFiles});
  const hasExecutionEvidence =
    report != null ||
    Boolean(run?.tasks?.some((task) => task.startedAt != null || task.completedAt != null));
  const artifactActions = runArtifactActionAvailability({
    status: run?.status,
    changedWorkspaceFiles: producedWorkspaceFileCount,
    hasExecutionEvidence,
    hasValidationReport: report != null,
  });
  const hasCostEvidence = hasRunCostEvidence(run);
  const {canRerunTests, emptyMessage: noCodeTestMessage} = testRerunState({
    status: run?.status,
    hasTestPlan: Boolean(testPlan),
    hasTests: Boolean(tests),
  });
  const phaseDiagnosis = runPhaseDiagnosis({
    status: run?.status,
    tasks: run?.tasks,
    report,
    tests,
    eventLog,
  });
  const failureText =
    run?.failureReason ||
    (run?.statusReason ? humanStatusReason(run.statusReason) : '') ||
    (failedTasks.length > 0
      ? `${failedTasks.length} step${failedTasks.length === 1 ? '' : 's'} need attention.`
      : issues.length > 0
        ? `${issues.length} validation issue${issues.length === 1 ? '' : 's'} found.`
        : 'Open the logs and evidence to identify the failing stage.');
  const durableFailureEvidence = terminalFailureEvidence(run);
  const trustFacts = publicationTrustFacts(run, publication);
  // C2 — tab title carries the run name once loaded.
  useDocumentTitle(run?.runTitle || (run ? `R-${runId.slice(0, 6)}` : null));

  if (loading) {
    return (
      <div style={{position: 'absolute', inset: 0}}>
        <IdeLoader label='Loading run…' />
      </div>
    );
  }

  if (loadError || !run) {
    const forbidden = loadError === 'forbidden';
    const unavailable = loadError === 'error';
    return (
      <div className='ide-pane' role='alert'>
        <div className='ide-empty'>
          <strong>
            {forbidden ? 'Run access denied' : unavailable ? 'Run unavailable' : 'Run not found'}
          </strong>
          <div>
            {forbidden
              ? 'You do not have access to this run.'
              : unavailable
                ? 'The run could not be loaded. Try again shortly.'
                : 'This run does not exist or was removed.'}
          </div>
          <Link to='/runs'>Back to work</Link>
        </div>
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
            {tab === 'Code' && producedWorkspaceFileCount ? ` · ${producedWorkspaceFileCount}` : ''}
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
                  ? producedWorkspaceFileCount
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
              ? producedWorkspaceFileCount
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
                <ScoreBadge score={score} verificationLedger={report?.verificationLedger} />
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
                      {producedWorkspaceFileCount} generated file
                      {producedWorkspaceFileCount === 1 ? '' : 's'}
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

            {!failedTerminal && phaseDiagnosis && (
              <div className='ide-recovery-card'>
                <div className='ide-recovery-main'>
                  <div className='ide-recovery-kicker'>Current diagnosis</div>
                  <div className='ide-recovery-title'>{phaseDiagnosis.title}</div>
                  <div className='ide-recovery-copy'>{phaseDiagnosis.body}</div>
                  {phaseDiagnosis.facts.length > 0 && (
                    <div className='ide-recovery-meta'>
                      {phaseDiagnosis.facts.map((fact) => (
                        <span key={fact}>{fact}</span>
                      ))}
                    </div>
                  )}
                </div>
                <div className='ide-recovery-actions' aria-label='Diagnosis actions'>
                  <button className='ide-btn' onClick={() => setTab(phaseDiagnosis.primaryTab)}>
                    {phaseDiagnosis.primaryTab === 'Tasks' ? 'Open steps' : 'Open logs'}
                  </button>
                  <button className='ide-btn ghost' onClick={() => setTab('Validation')}>
                    Evidence
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
              {reportFailure ? (
                <div className='ide-card' style={{flex: 1, minWidth: 240}}>
                  <LoadError
                    failure={reportFailure}
                    what='verified score evidence'
                    onRetry={reloadReport}
                  />
                </div>
              ) : !report && !tests && score == null ? (
                <div
                  className='ide-card'
                  style={{flex: 1, minWidth: 240, color: 'var(--ide-fg-muted)', fontSize: 12}}
                >
                  {missingScoreExplanation(run)}
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
                  <Kv k='Tests' v={testsSignal} />
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
                  <Kv
                    k='Files'
                    v={
                      reportedFiles != null ? (
                        <span title={fileCounts.title}>{fileCounts.label}</span>
                      ) : (
                        workspaceFiles
                      )
                    }
                  />
                  <Kv k='Pipeline' v={report?.pipelineResult ?? '—'} />
                  {fileCounts.explanation && (
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
                      {fileCounts.explanation}
                    </div>
                  )}
                  {scoredWithoutFunctionalTests && (
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
                      No independently verified functional test run backs this score. Fresh
                      workspace tests remain useful code-level evidence, but they do not prove
                      deployed product journeys or external integrations end to end.
                    </div>
                  )}
                </div>
              )}
              <div className='ide-card' style={{flex: 1, minWidth: 240}}>
                <div className='ide-h2' style={{marginTop: 0}}>
                  Execution details
                </div>
                <Kv k='Status' v={run?.status ?? '—'} />
                {/* RR-761 — always render the run's PERSISTED provider/model. The viewer's current
                    FREE tier lock is prospective policy, not what a historical run used (it may
                    predate the lock, or have run under another tier / explicit route / failover).
                    Overwriting the recorded route with today's tier corrupts audit + debug evidence. */}
                <Kv k='Provider' v={(run?.aiProvider || '—').toLowerCase()} />
                <Kv
                  k='Requested route'
                  v={
                    run?.requestedProvider
                      ? `${run.requestedProvider.toLowerCase()}${run.requestedModel ? ` / ${run.requestedModel}` : ''}`
                      : '—'
                  }
                />
                <Kv
                  k='Route policy'
                  v={
                    run?.routingMode
                      ? `${run.routingMode}${run.routePinned ? ' (locked)' : ''}`
                      : '—'
                  }
                />
                <Kv
                  k='Worker'
                  v={
                    <span className='ide-mono' style={{fontSize: 11}}>
                      {run?.workerModel || '—'}
                    </span>
                  }
                />
                <Kv k='AI credits used' v={run?.aiCreditsCharged ?? '—'} />
                <Kv k='Run credits reserved' v={run?.creditsCharged ?? '—'} />
                {isSuperAdmin && <Kv k='Cost' v={usd(run?.actualCostUsd)} />}
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
                      reloadRun,
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
                ) : artifactActions.push ? (
                  <button
                    className='ide-btn'
                    disabled={busy === 'push'}
                    onClick={() =>
                      action('push', () => api.pushToScm(runId), pushOutcomeMessage, reloadRun)
                    }
                  >
                    {busy === 'push' ? 'Pushing…' : '⤴ Push changes'}
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
              {artifactActions.revertWorkspace &&
                ['COMPLETE', 'PARTIAL', 'FAILED', 'CANCELLED'].includes(
                  String(run?.status || '').toUpperCase(),
                ) && (
                  <button
                    className='ide-btn ghost'
                    style={{color: 'var(--ide-red)'}}
                    disabled={busy === 'revert-workspace'}
                    onClick={() =>
                      setConfirm({
                        title: 'Revert workspace to run start',
                        body: 'This resets the cloned workspace to the exact commit captured when this run started. It does not change the remote repository or open a PR. Continue?',
                        confirmLabel: 'Revert workspace',
                        danger: true,
                        onConfirm: () =>
                          action(
                            'revert-workspace',
                            () => api.syncRunToOriginal(runId),
                            (result) => {
                              const response = result as {status?: string; reposSynced?: number};
                              return response.status === 'failed'
                                ? 'Workspace revert failed.'
                                : `Workspace reverted (${response.reposSynced ?? 0} repos).`;
                            },
                            reloadRun,
                          ),
                      })
                    }
                  >
                    {busy === 'revert-workspace' ? 'Reverting…' : '↶ Revert workspace'}
                  </button>
                )}
              <button
                className='ide-btn ghost'
                disabled={busy === 'preview' || !artifactActions.preview}
                title={!artifactActions.preview ? artifactActions.workspaceReason : undefined}
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
                disabled={busy === 'deploy' || !artifactActions.deploy}
                title={!artifactActions.deploy ? artifactActions.workspaceReason : undefined}
                onClick={() =>
                  setConfirm({
                    title: 'Deploy to Cloud Run',
                    body: 'Deploy this run to a permanent Cloud Run service?',
                    confirmLabel: 'Deploy',
                    onConfirm: () =>
                      action(
                        'deploy',
                        async () => {
                          const deployment = await api.createDeployment({runId});
                          navigate(`/deployments?runId=${encodeURIComponent(runId)}`);
                          return deployment;
                        },
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
                disabled={busy === 'pdf' || !artifactActions.reportPdf}
                title={!artifactActions.reportPdf ? artifactActions.reportReason : undefined}
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
                disabled={busy === 'code' || !artifactActions.codeZip}
                title={
                  !artifactActions.codeZip
                    ? artifactActions.workspaceReason
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
                disabled={busy === 'share' || !artifactActions.shareReport}
                title={!artifactActions.shareReport ? artifactActions.reportReason : undefined}
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
              {(providerReadiness.supported
                ? providerReadiness.rows
                    .filter((row) => providerIsDispatchable(row))
                    .map((row) => row.provider.toLowerCase())
                : ['anthropic', 'openai']
              )
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
              {providerReadiness.supported &&
                !providerReadiness.loading &&
                providerReadiness.rows.every(
                  (row) =>
                    row.provider.toLowerCase() === (run?.aiProvider || '').toLowerCase() ||
                    !providerIsDispatchable(row),
                ) && (
                  <span style={{fontSize: 11, color: 'var(--ide-fg-muted)'}}>
                    {providerReadiness.error
                      ? 'Provider readiness unavailable; re-run choices are disabled.'
                      : 'No other provider is currently ready for dispatch.'}
                  </span>
                )}
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
                <span>
                  Explorer
                  {producedWorkspaceFileCount
                    ? ` · ${producedWorkspaceFileCount} generated`
                    : files.length
                      ? ` · ${files.length} bootstrap/workspace`
                      : ''}
                </span>
                {files.length > 0 && (
                  <button
                    className='ide-btn ghost'
                    style={{padding: '2px 8px', fontSize: 11}}
                    disabled={busy === 'code' || !artifactActions.codeZip}
                    title={
                      artifactActions.codeZip
                        ? 'Download the generated code as a zip'
                        : artifactActions.workspaceReason
                    }
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
              ) : filesFailure ? (
                <div className='ide-empty' style={{padding: 14}}>
                  File list unavailable
                </div>
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
                    aria-label='Select a file to view'
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
              {active?.failure ? (
                // RR-767 — the file could not be read; show a retryable failure
                // rather than fabricated "// could not load" source text.
                <LoadError
                  failure={active.failure}
                  what='this file'
                  onRetry={() => activeFile && retryOpenFile(activeFile)}
                />
              ) : active ? (
                <IdeCodeViewer
                  filename={activeFile || ''}
                  content={active.content}
                  diff={active.diff}
                  onRevertHunk={
                    active.diff
                      ? (hunkIndex) =>
                          requestHunkRevert(activeFile || '', active.diff ?? [], hunkIndex)
                      : undefined
                  }
                />
              ) : filesFailure ? (
                <LoadError failure={filesFailure} what='generated files' />
              ) : (
                <div className='ide-empty'>
                  {emptyCodeTabMessage({hasFiles: files.length > 0, status: run?.status})}
                </div>
              )}
            </div>
          </div>
        )}

        {/* ── Tasks ── */}
        {tab === 'Tasks' && (
          <div className='ide-pane' style={{position: 'absolute', inset: 0, overflowY: 'auto'}}>
            <h1 className='ide-h1'>Steps</h1>
            {effectiveRunGoal && (
              <div className='ide-card' style={{marginBottom: 12}}>
                <div style={{display: 'flex', alignItems: 'center', gap: 8}}>
                  <IdeBadge kind={goalStateKind(effectiveRunGoal.state)}>
                    {goalStateLabel(effectiveRunGoal.state)}
                  </IdeBadge>
                  {effectiveRunGoal.recommendedDecision && (
                    <span className='ide-mono' style={{fontSize: 12}}>
                      {goalDecisionLabel(effectiveRunGoal.recommendedDecision)}
                    </span>
                  )}
                </div>
                {goalDecisionHelp(effectiveRunGoal.recommendedDecision) && (
                  <div style={{fontSize: 12, marginTop: 8, color: 'var(--ide-fg-muted)'}}>
                    {goalDecisionHelp(effectiveRunGoal.recommendedDecision)}
                  </div>
                )}
                {(effectiveRunGoal.reason ||
                  effectiveRunGoal.recommendedNextStep ||
                  effectiveRunGoal.evidence?.length ||
                  effectiveRunGoal.incompleteItems?.length ||
                  effectiveRunGoal.risks?.length) && (
                  <RunTechnicalDetails style={{marginTop: 10}}>
                    {effectiveRunGoal.reason && (
                      <div>
                        <strong>Recorded reason:</strong> {effectiveRunGoal.reason}
                      </div>
                    )}
                    {effectiveRunGoal.recommendedNextStep && (
                      <div>
                        <strong>Recorded next step:</strong> {effectiveRunGoal.recommendedNextStep}
                      </div>
                    )}
                    {!!effectiveRunGoal.incompleteItems?.length && (
                      <div>
                        <strong>Incomplete:</strong> {effectiveRunGoal.incompleteItems.join(' · ')}
                      </div>
                    )}
                    {!!effectiveRunGoal.risks?.length && (
                      <div>
                        <strong>Risks:</strong> {effectiveRunGoal.risks.join(' · ')}
                      </div>
                    )}
                    {!!effectiveRunGoal.evidence?.length && (
                      <div>
                        <strong>Evidence:</strong>
                        <ul style={{margin: '4px 0 0', paddingLeft: 18}}>
                          {effectiveRunGoal.evidence.map((item, idx) => (
                            <li key={`${idx}:${item}`}>{item}</li>
                          ))}
                        </ul>
                      </div>
                    )}
                  </RunTechnicalDetails>
                )}
              </div>
            )}
            {!run?.tasks?.length ? (
              durableFailureEvidence ? (
                <div className='ide-card' role='alert'>
                  <div style={{fontWeight: 600}}>{durableFailureEvidence.title}</div>
                  <div style={{fontSize: 12, color: 'var(--ide-red)', marginTop: 6}}>
                    {durableFailureEvidence.detail}
                  </div>
                  <div style={{fontSize: 11, color: 'var(--ide-fg-muted)', marginTop: 8}}>
                    Execution did not start, so no worker steps were created.
                  </div>
                </div>
              ) : (
                <div className='ide-empty'>No steps.</div>
              )
            ) : (
              <div className='ide-card' style={{padding: 0}}>
                {run.tasks.map((t, i) => {
                  const taskComplete = t.status === 'COMPLETE' || t.status === 'SUCCESS';
                  const safeTitle = customerStepTitle(t, i);
                  const safeSummary = customerStepSummary(t);
                  const continuationSummary = customerContinuationSummary(t);
                  const limitSummary = customerLimitSummary(t);
                  const goalLine = taskGoalLine(t);
                  const continuationActionLine = latestContinuationAction(t);
                  const budgetFacts = taskBudgetFacts(t);
                  const incompleteItems = t.goalDoneness?.incompleteItems || [];
                  const risks = t.goalDoneness?.risks || [];
                  const approvalCandidate = continuationApprovalCandidate(t);
                  const approvalReason = continuationApprovalReason(t);
                  const policy = t.continuationDecision?.policy;
                  const rankingCandidates = policy?.topEligibleCandidates?.slice(0, 3) || [];
                  const showModelRanking =
                    isSuperAdmin && !taskComplete && shouldShowModelRanking(t);
                  const rankingDetails = policy ? modelRankingDetails(policy) : [];
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
                          {customerStepStatusLabel(t.status)}
                        </IdeBadge>
                        <span style={{flex: 1, fontWeight: 500}}>{safeTitle}</span>
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
                                reloadRun,
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
                            title='Approve the recommended recovery'
                            onClick={() =>
                              action(
                                `continuation-${t.taskId}`,
                                () => api.approveContinuation(runId, t.taskId),
                                (r) =>
                                  typeof r === 'object' && r && 'message' in r
                                    ? String(r.message)
                                    : 'Continuation approved.',
                                reloadRun,
                              )
                            }
                          >
                            Approve recovery
                          </button>
                        )}
                      </div>
                      <div style={{fontSize: 12, color: 'var(--ide-fg-muted)', marginTop: 6}}>
                        {safeSummary}
                      </div>
                      <div
                        className='ide-mono'
                        style={{fontSize: 11, color: 'var(--ide-fg-muted)', marginTop: 4}}
                      >
                        {(t.complexity || 'complexity pending').toUpperCase()} ·{' '}
                        {t.routingTaskCategory || 'category pending'} ·{' '}
                        {t.routingTier || 'tier pending'} ·{' '}
                        {t.effectiveRouteAttribution === 'LEDGER'
                          ? `${(t.effectiveProvider || 'unknown').toLowerCase()}/${t.effectiveModel || 'unknown'}`
                          : t.effectiveRouteAttribution === 'MIXED'
                            ? 'multiple effective routes'
                            : `${(t.provider || 'unknown').toLowerCase()}/${t.model || t.assignedModel || 'unknown'} (requested)`}
                        {t.routeDiverged ? ' · changed during execution' : ''}
                      </div>
                      {continuationSummary && (
                        <div style={{fontSize: 11, color: 'var(--ide-amber)', marginTop: 4}}>
                          {continuationSummary}
                        </div>
                      )}
                      {limitSummary && (
                        <div style={{fontSize: 11, color: 'var(--ide-amber)', marginTop: 4}}>
                          {limitSummary}
                        </div>
                      )}
                      <RunTechnicalDetails style={{marginTop: 8}}>
                        {safeTitle !== t.title && (
                          <div>
                            <strong>Planner step:</strong> {t.title}
                          </div>
                        )}
                        {t.objective && (
                          <div>
                            <strong>Objective:</strong> {t.objective}
                          </div>
                        )}
                        {t.failureReason && (
                          <div style={{color: 'var(--ide-red)'}}>
                            <strong>Failure:</strong> {t.failureReason}
                          </div>
                        )}
                        <div className='ide-mono'>
                          Requested route: {(t.provider || 'unknown').toLowerCase()} ·{' '}
                          {t.model || t.assignedModel || 'unknown'}
                        </div>
                        <div className='ide-mono'>
                          Effective route:{' '}
                          {t.effectiveRouteAttribution === 'LEDGER'
                            ? `${(t.effectiveProvider || 'unknown').toLowerCase()} · ${t.effectiveModel || 'unknown'}`
                            : t.effectiveRouteAttribution === 'MIXED'
                              ? 'multiple provider/model routes'
                              : 'not yet attributed'}{' '}
                          · {num(t.inputTokens + t.outputTokens)} tokens
                        </div>
                        <div>
                          Routing policy: {t.routingTier || 'pending'} ·{' '}
                          {t.routingTaskCategory || 'category pending'} ·{' '}
                          {t.routingSource || 'source pending'}
                        </div>
                        {goalLine && <div>{goalLine}</div>}
                        {approvalReason && (
                          <div style={{color: 'var(--ide-amber)'}}>{approvalReason}</div>
                        )}
                        {continuationActionLine && <div>{continuationActionLine}</div>}
                        {!taskComplete && t.goalDoneness?.recommendedNextStep && (
                          <div>Recorded next step: {t.goalDoneness.recommendedNextStep}</div>
                        )}
                        {t.goalDoneness?.reason && (
                          <div>Recorded reason: {t.goalDoneness.reason}</div>
                        )}
                        {showModelRanking && policy && (
                          <div
                            style={{
                              border: '1px solid var(--ide-border)',
                              borderRadius: 6,
                              padding: '7px 8px',
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
                              <span style={{color: 'var(--ide-fg-bright)'}}>
                                Model route choice
                              </span>
                              <span className='ide-mono'>
                                {modelRankingLabel(policy.rankingPolicy)}
                              </span>
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
                                    {idx + 1}. {modelRankingCandidateLabel(candidate)}
                                  </span>
                                  <span>{modelRankingCandidateMetrics(candidate)}</span>
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
                                <div style={{fontSize: 10, textTransform: 'uppercase'}}>
                                  {fact.k}
                                </div>
                                <div className='ide-mono'>{fact.v}</div>
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
                            }}
                          >
                            {incompleteItems.length > 0 && (
                              <div>
                                <div style={{color: 'var(--ide-fg-bright)'}}>Incomplete</div>
                                {incompleteItems.map((item, idx) => (
                                  <div key={`${idx}:${item}`}>- {item}</div>
                                ))}
                              </div>
                            )}
                            {risks.length > 0 && (
                              <div>
                                <div style={{color: 'var(--ide-fg-bright)'}}>Risks</div>
                                {risks.map((risk, idx) => (
                                  <div key={`${idx}:${risk}`}>- {risk}</div>
                                ))}
                              </div>
                            )}
                          </div>
                        )}
                        {!!t.goalDoneness?.evidence?.length && (
                          <div>
                            <div style={{color: 'var(--ide-fg-bright)'}}>Evidence</div>
                            <ul style={{margin: '4px 0 0', paddingLeft: 18}}>
                              {t.goalDoneness.evidence.map((item, idx) => (
                                <li key={`${idx}:${item}`}>{item}</li>
                              ))}
                            </ul>
                          </div>
                        )}
                      </RunTechnicalDetails>
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
                <h1 className='ide-h1' style={{display: 'flex', alignItems: 'center', gap: 8}}>
                  Activity log{visibleLog?.length ? ` · ${visibleLog.length}` : ''}{' '}
                  <span
                    className='ide-mono'
                    style={{fontSize: 11, color: 'var(--ide-fg-muted)', fontWeight: 400}}
                  >
                    {eventReplay?.available && eventReplay.retainedUntil
                      ? `· replay available until ${new Date(eventReplay.retainedUntil).toLocaleString()}`
                      : '· replay retained for up to 24h'}
                  </span>
                  <span style={{flex: 1}} />
                  {/* RES-6 — the replay is a snapshot; let the user re-pull it. */}
                  <button
                    className='ide-btn ghost'
                    style={{padding: '3px 10px', fontSize: 12}}
                    onClick={() => void loadEventLog().catch(() => {})}
                  >
                    ↻ Refresh
                  </button>
                </h1>
                {visibleLog == null ? (
                  <IdeLoader label='Loading event log…' />
                ) : visibleLog.length === 0 ? (
                  <div className='ide-empty'>
                    {eventReplay?.reason === 'STORE_UNAVAILABLE'
                      ? 'Activity replay is temporarily unavailable. Refresh to try again.'
                      : 'No activity is retained for this run. Replay expires 24 hours after the last event.'}
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
                      const label = logLabel(ev.type);
                      const t = (ev.type || 'log').toLowerCase();
                      const meta = logMeta(ev);
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
                            display: 'grid',
                            gridTemplateColumns: '18px 104px 76px minmax(0, 1fr)',
                            gap: 8,
                            padding: '8px 10px',
                            borderBottom:
                              i < visibleLog.length - 1 ? '1px solid var(--ide-border)' : 'none',
                            fontSize: 12,
                            lineHeight: 1.45,
                            alignItems: 'start',
                          }}
                        >
                          <span style={{flexShrink: 0}} aria-hidden='true'>
                            {logIcon(ev.type)}
                          </span>
                          <span
                            className='ide-mono'
                            style={{
                              color,
                              textTransform: 'uppercase',
                              fontSize: 10,
                              paddingTop: 2,
                              overflow: 'hidden',
                              textOverflow: 'ellipsis',
                              whiteSpace: 'nowrap',
                            }}
                            title={ev.type || 'log'}
                          >
                            {label}
                          </span>
                          <span
                            className='ide-mono'
                            style={{color: 'var(--ide-fg-muted)', fontSize: 10, paddingTop: 2}}
                          >
                            {ev.timestamp != null
                              ? new Date(ev.timestamp as string | number).toLocaleTimeString()
                              : '—'}
                          </span>
                          <span style={{minWidth: 0}}>
                            <span style={{display: 'block', wordBreak: 'break-word'}}>
                              {logMessage(ev)}
                            </span>
                            {meta.length > 0 && (
                              <span
                                style={{
                                  display: 'flex',
                                  flexWrap: 'wrap',
                                  gap: 6,
                                  marginTop: 5,
                                }}
                              >
                                {meta.map((m) => (
                                  <span
                                    key={m}
                                    className='ide-mono'
                                    style={{
                                      border: '1px solid var(--ide-border)',
                                      borderRadius: 999,
                                      padding: '1px 6px',
                                      color: 'var(--ide-fg-muted)',
                                      fontSize: 10,
                                      background:
                                        'color-mix(in srgb, var(--ide-bg-widget) 78%, transparent)',
                                    }}
                                  >
                                    {m}
                                  </span>
                                ))}
                              </span>
                            )}
                          </span>
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
            <h1 className='ide-h1'>Timeline</h1>
            {timeline && (timeline.tasks || []).length > 0 && (
              <div className='ide-card' style={{marginBottom: 12}}>
                <RunTimeline tasks={timeline.tasks} />
              </div>
            )}
            {timelineFailure ? (
              <LoadError failure={timelineFailure} what='timeline data' />
            ) : timeline == null ? (
              <IdeLoader label='Loading timeline…' />
            ) : (timeline.tasks || []).length === 0 ? (
              <div className='ide-empty'>No timeline data yet. Tasks populate as the run runs.</div>
            ) : (
              <div className='ide-card'>
                {(() => {
                  const allTasks = timeline.tasks || [];
                  const nowMs = Date.now();
                  const chartTasks = allTasks.flatMap((task) => {
                    const timing = timelineTiming(task, nowMs);
                    return timing ? [{task, timing}] : [];
                  });
                  if (chartTasks.length === 0) {
                    return (
                      <div className='ide-empty'>
                        Timing data is unavailable for these tasks. Their status is still shown
                        above.
                      </div>
                    );
                  }
                  const starts = chartTasks.map(({timing}) => timing.startMs);
                  const ends = chartTasks.map(({timing}) => timing.endMs);
                  const minT = Math.min(...starts);
                  const maxT = Math.max(...ends);
                  const span = Math.max(1, maxT - minT);
                  return chartTasks.map(({task: t, timing}, i) => {
                    const start = timing.startMs;
                    const end = timing.endMs;
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
                            {fmtDur(timing.durationMs)}
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
            <h1 className='ide-h1' style={{display: 'flex', alignItems: 'center', gap: 10}}>
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
            </h1>
            <div className='ide-h2'>Publication trust boundary</div>
            {publicationFailure && (
              <LoadError
                failure={publicationFailure}
                what='publication eligibility'
                onRetry={reloadPublicationEligibility}
              />
            )}
            <div className='ide-card' style={{padding: 0}}>
              {trustFacts.map((fact, index) => (
                <div
                  key={fact.label}
                  style={{
                    padding: '10px 16px',
                    borderBottom:
                      index < trustFacts.length - 1 ? '1px solid var(--ide-border)' : 'none',
                    display: 'grid',
                    gridTemplateColumns: 'minmax(150px, 0.35fr) minmax(0, 1fr)',
                    gap: 12,
                    alignItems: 'start',
                  }}
                >
                  <span style={{fontSize: 12, color: 'var(--ide-fg-muted)'}}>{fact.label}</span>
                  <div style={{display: 'flex', alignItems: 'flex-start', gap: 8, minWidth: 0}}>
                    <IdeBadge kind={fact.kind === 'neutral' ? 'warn' : fact.kind}>
                      {fact.kind === 'ok'
                        ? 'PASS'
                        : fact.kind === 'bad'
                          ? 'BLOCK'
                          : fact.kind === 'warn'
                            ? 'UNKNOWN'
                            : 'INFO'}
                    </IdeBadge>
                    <span
                      className='ide-mono'
                      style={{fontSize: 11, overflowWrap: 'anywhere', lineHeight: 1.5}}
                    >
                      {fact.value}
                    </span>
                  </div>
                </div>
              ))}
            </div>
            {publication && !publication.eligible && (
              <div className='ide-card' role='alert'>
                <div style={{fontWeight: 600}}>{publication.reason}</div>
                {publication.remediation && (
                  <div style={{fontSize: 12, color: 'var(--ide-fg-muted)', marginTop: 6}}>
                    {publication.remediation}
                  </div>
                )}
                <div className='ide-mono' style={{fontSize: 11, marginTop: 6}}>
                  {publication.retryable
                    ? 'Safe to retry after remediation'
                    : 'Direct retry blocked'}
                </div>
              </div>
            )}
            {reportFailure ? (
              <LoadError failure={reportFailure} what='evidence report' onRetry={reloadReport} />
            ) : !report ? (
              durableFailureEvidence ? (
                <div className='ide-card' role='alert'>
                  <div style={{fontWeight: 600}}>{durableFailureEvidence.title}</div>
                  <div style={{fontSize: 12, color: 'var(--ide-red)', marginTop: 6}}>
                    {durableFailureEvidence.detail}
                  </div>
                  <div style={{fontSize: 11, color: 'var(--ide-fg-muted)', marginTop: 8}}>
                    Validation did not run because execution never reached the worker phase.
                  </div>
                </div>
              ) : (
                <div className='ide-empty'>No evidence report.</div>
              )
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
                          {goalStateLabel(report.runGoalDecision.state)}
                        </IdeBadge>
                        {report.runGoalDecision.recommendedDecision && (
                          <span className='ide-mono' style={{fontSize: 12}}>
                            {goalDecisionLabel(report.runGoalDecision.recommendedDecision)}
                          </span>
                        )}
                      </div>
                      {goalDecisionHelp(report.runGoalDecision.recommendedDecision) && (
                        <div style={{fontSize: 12, marginTop: 8, color: 'var(--ide-fg-muted)'}}>
                          {goalDecisionHelp(report.runGoalDecision.recommendedDecision)}
                        </div>
                      )}
                      {report.runGoalDecision.goalSummary && (
                        <div style={{fontSize: 12, marginTop: 8}}>
                          Goal: {report.runGoalDecision.goalSummary}
                        </div>
                      )}
                      {goalProofReferences.length > 0 ? (
                        <div style={{marginTop: 10}}>
                          <div
                            className='ide-mono'
                            style={{fontSize: 11, color: 'var(--ide-fg-muted)'}}
                          >
                            Decision evidence
                          </div>
                          {goalProofReferences.map((reference) => (
                            <div
                              key={reference.evidenceId}
                              style={{
                                marginTop: 7,
                                paddingTop: 7,
                                borderTop: '1px solid var(--ide-border)',
                              }}
                            >
                              <div style={{display: 'flex', alignItems: 'center', gap: 8}}>
                                <IdeBadge kind={goalProofKind(reference.proofStatus)}>
                                  {goalProofLabel(reference.proofStatus)}
                                </IdeBadge>
                                <span style={{fontSize: 12, fontWeight: 600}}>
                                  {reference.label}
                                </span>
                                <span
                                  className='ide-mono'
                                  style={{fontSize: 11, color: 'var(--ide-fg-muted)'}}
                                >
                                  {reference.observedStatus}
                                </span>
                              </div>
                              {(reference.source || reference.confidence) && (
                                <div
                                  className='ide-mono'
                                  style={{fontSize: 11, color: 'var(--ide-fg-muted)', marginTop: 4}}
                                >
                                  {[reference.source, reference.confidence]
                                    .filter(Boolean)
                                    .join(' · ')}
                                </div>
                              )}
                              {reference.artifact && (
                                <div
                                  className='ide-mono'
                                  style={{fontSize: 11, color: 'var(--ide-fg-muted)', marginTop: 3}}
                                >
                                  {reference.artifact}
                                  {reference.total != null && reference.observed != null
                                    ? ` · ${reference.observed}/${reference.total}`
                                    : ''}
                                </div>
                              )}
                            </div>
                          ))}
                        </div>
                      ) : (
                        <>
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
                        </>
                      )}
                      {goalProofReferences.length === 0 &&
                        report.runGoalDecision.incompleteItems &&
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
                      {goalProofReferences.length === 0 &&
                        report.runGoalDecision.risks &&
                        report.runGoalDecision.risks.length > 0 && (
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
            <h1 className='ide-h1'>Cost</h1>
            <div className='ide-card'>
              <Kv k='AI credits used' v={run?.aiCreditsCharged ?? '—'} />
              <Kv k='Run credits reserved' v={run?.creditsCharged ?? '—'} />
              <Kv k='Input tokens' v={num(costEvidence?.inputTokens ?? run?.totalInputTokens)} />
              <Kv k='Output tokens' v={num(costEvidence?.outputTokens ?? run?.totalOutputTokens)} />
              <Kv k='Cache-read tokens' v={num(costEvidence?.cacheReadTokens)} />
              <Kv k='Cache-write tokens' v={num(costEvidence?.cacheWriteTokens)} />
              <Kv
                k='Reasoning tokens (included in output)'
                v={num(costEvidence?.reasoningTokens)}
              />
              {costEvidence && (
                <>
                  <Kv
                    k='Physical provider attempts'
                    v={num(costEvidence.physicalAttempts ?? costEvidence.stepCallsTotal)}
                  />
                  <Kv
                    k='Successful provider calls'
                    v={num(costEvidence.successfulCalls ?? costEvidence.calls)}
                  />
                  <Kv k='Failed provider attempts' v={num(costEvidence.failedAttempts)} />
                </>
              )}
              {isSuperAdmin && (
                <>
                  <Kv k='Actual cost' v={usd(costEvidence?.costUsd ?? run?.actualCostUsd)} />
                  <Kv k='Requested voluntary cap' v={usd(run?.userCostCapUsd)} />
                  <Kv k='Hard cap' v={usd(run?.hardCapUsd)} />
                  <Kv
                    k='Estimate range'
                    v={
                      run?.costEstimateLow != null
                        ? `${usd(run.costEstimateLow)} – ${usd(run.costEstimateHigh)}`
                        : '—'
                    }
                  />
                </>
              )}
            </div>
            {!hasCostEvidence && failedTerminal && (
              <div
                className='ide-card'
                style={{
                  marginTop: 12,
                  borderColor: 'color-mix(in srgb, var(--ide-yellow) 55%, var(--ide-border))',
                  background: 'color-mix(in srgb, var(--ide-yellow) 8%, var(--ide-bg-card))',
                  color: 'var(--ide-fg-muted)',
                  fontSize: 12.5,
                  lineHeight: 1.45,
                }}
              >
                No token or AI-credit usage was recorded for this failed run. That usually means the
                run stopped before a billable provider response, for example provider authentication
                failure or tool setup failure.
              </div>
            )}
            {isSuperAdmin && (
              <>
                <div className='ide-h2'>Per phase</div>
                <div className='ide-card'>
                  {phaseCostRows(run, costEvidence).map(([label, cost]) => (
                    <Kv key={label} k={label} v={usd(cost)} />
                  ))}
                </div>
                <div className='ide-h2'>Pipeline agents</div>
                <div className='ide-card'>
                  {(run?.agentTokens ?? []).length === 0 ? (
                    <div className='ide-empty'>No persisted agent-token attribution.</div>
                  ) : (
                    run?.agentTokens?.map((agent, index) => (
                      <div
                        key={`${agent.role}-${index}`}
                        className='ide-kv'
                        style={{alignItems: 'start'}}
                      >
                        <span>{agent.role}</span>
                        <span className='ide-mono' style={{fontSize: 11, textAlign: 'right'}}>
                          {agent.provider || '—'} · {agent.model || '—'}
                          <br />
                          {agent.inputTokens.toLocaleString()} in ·{' '}
                          {agent.outputTokens.toLocaleString()} out
                        </span>
                      </div>
                    ))
                  )}
                </div>
                <div className='ide-h2'>Provider calls</div>
                <div className='ide-card' style={{overflowX: 'auto'}}>
                  {costEvidenceFailure ? (
                    <LoadError failure={costEvidenceFailure} what='per-call attribution' />
                  ) : stepCalls.kind === 'restricted' ? (
                    // RR-1552 — the ledger exposes per-attempt key source, routing and spend, so
                    // the backend restricts it to org admins. Saying "none available" here would
                    // be a claim about the run; this is a claim about the reader's access.
                    <div className='ide-empty'>
                      Per-call attribution is restricted to organization admins.
                      {stepCalls.total > 0
                        ? ` This run recorded ${stepCalls.total.toLocaleString()} provider ${
                            stepCalls.total === 1 ? 'call' : 'calls'
                          }; the totals above include them.`
                        : ' The totals above still reflect the full run.'}
                    </div>
                  ) : stepCalls.kind !== 'rows' ? (
                    <div className='ide-empty'>
                      No per-call attribution is available for this run.
                    </div>
                  ) : (
                    <table style={{width: '100%', borderCollapse: 'collapse', fontSize: 11}}>
                      <thead>
                        <tr style={{textAlign: 'left', color: 'var(--ide-fg-muted)'}}>
                          <th>Role / task</th>
                          <th>Provider / model</th>
                          <th>Tokens</th>
                          <th>Cache / reasoning</th>
                          <th>Attempt</th>
                          <th style={{textAlign: 'right'}}>Cost</th>
                        </tr>
                      </thead>
                      <tbody>
                        {stepCalls.rows.map((call) => (
                          <tr key={call.id} style={{borderTop: '1px solid var(--ide-border)'}}>
                            <td style={{padding: '7px 8px 7px 0'}}>
                              {call.agentRole || 'unattributed'}
                              <div style={{color: 'var(--ide-fg-muted)'}}>
                                {call.taskId || call.stepId || 'run-level'}
                              </div>
                            </td>
                            <td>
                              {call.provider || '—'}
                              <div className='ide-mono'>{call.model || '—'}</div>
                            </td>
                            <td>
                              {call.inputTokens.toLocaleString()} in ·{' '}
                              {call.outputTokens.toLocaleString()} out
                            </td>
                            <td>
                              {call.cacheReadTokens.toLocaleString()} read ·{' '}
                              {call.cacheWriteTokens.toLocaleString()} write
                              <div>{call.reasoningTokens.toLocaleString()} reasoning</div>
                            </td>
                            <td
                              title={[
                                call.physicalAttemptId && `physical: ${call.physicalAttemptId}`,
                                call.parentAttemptId && `parent: ${call.parentAttemptId}`,
                                call.logicalCallId && `logical: ${call.logicalCallId}`,
                                call.errorCode && `error: ${call.errorCode}`,
                                call.error,
                              ]
                                .filter(Boolean)
                                .join('\n')}
                            >
                              {attemptSummary(call)}
                              <div
                                style={{
                                  color:
                                    call.attemptStatus === 'SUCCESS'
                                      ? 'var(--ide-green)'
                                      : 'var(--ide-yellow)',
                                }}
                              >
                                {call.attemptStatus || 'status unavailable'}
                              </div>
                              {call.parentAttemptId && (
                                <div className='ide-mono' style={{color: 'var(--ide-fg-muted)'}}>
                                  parent …{call.parentAttemptId.slice(-10)}
                                </div>
                              )}
                            </td>
                            <td
                              style={{textAlign: 'right'}}
                              title={
                                knownCallCost(call) === null
                                  ? 'Provider spend was not reported or could not be projected.'
                                  : undefined
                              }
                            >
                              {knownCallCost(call) === null ? 'Unknown' : usd(knownCallCost(call))}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  )}
                  {stepCalls.kind === 'rows' && stepCalls.hasMore && (
                    // RR-1558 paged the ledger newest-first. Without this the table silently
                    // looks like the complete history of the run.
                    <div style={{marginTop: 8, fontSize: 11, color: 'var(--ide-fg-muted)'}}>
                      Showing the {stepCalls.rows.length.toLocaleString()} most recent of{' '}
                      {stepCalls.total.toLocaleString()} provider calls.
                    </div>
                  )}
                </div>
                {(() => {
                  const reconciliation = costReconciliation(costEvidence);
                  if (!reconciliation) return null;
                  return (
                    <div className='ide-card' style={{marginTop: 12}}>
                      <Kv k='Displayed run total' v={usd(reconciliation.total)} />
                      <Kv k='Attributed provider calls' v={usd(reconciliation.attributed)} />
                      <Kv
                        k={
                          reconciliation.reconciled
                            ? 'Unattributed remainder'
                            : 'Unattributed / pending attribution'
                        }
                        v={usd(reconciliation.remainder)}
                      />
                    </div>
                  );
                })()}
              </>
            )}
          </div>
        )}

        {/* ── PR ── */}
        {tab === 'PR' && (
          <div className='ide-pane' style={{position: 'absolute', inset: 0, overflowY: 'auto'}}>
            <h1 className='ide-h1'>Pull request</h1>
            {msg && (
              <div className='ide-card' style={{marginBottom: 12, color: 'var(--ide-blue)'}}>
                {msg}
              </div>
            )}
            {/* RR-762 — consume the provider-neutral SCM PR URL (scmPrUrl || githubPrUrl) and label
                by the actual provider, so Bitbucket/provider-neutral runs are not shown as having no
                PR and prompted to configure GitHub. */}
            {prUrl ? (
              <>
                <div className='ide-card'>
                  <div
                    className='ide-mono'
                    style={{fontSize: 12, wordBreak: 'break-all', marginBottom: 10}}
                  >
                    {prUrl}
                  </div>
                  <a className='ide-btn' href={prUrl} target='_blank' rel='noreferrer'>
                    ↗ Open on {scmProviderLabel(run?.scmProvider)}
                  </a>
                </div>
                <PrCommentInbox runId={runId} variant='ide' />
              </>
            ) : (
              <div className='ide-card'>
                <div style={{marginBottom: 10, color: 'var(--ide-fg-muted)'}}>
                  {artifactActions.push
                    ? 'No pull request yet. Push the generated changes when you are ready to open one. Pushing requires a connected repository provider under Integrations.'
                    : `No pull request can be opened. ${artifactActions.workspaceReason}`}
                </div>
                <button
                  className='ide-btn'
                  disabled={busy === 'push' || !artifactActions.push}
                  title={!artifactActions.push ? artifactActions.workspaceReason : undefined}
                  onClick={() =>
                    action('push', () => api.pushToScm(runId), pushOutcomeMessage, reloadRun)
                  }
                >
                  {busy === 'push' ? 'Pushing…' : '⤴ Push changes'}
                </button>
              </div>
            )}
          </div>
        )}

        {/* ── Screens ── static UI render; builds and deploys nothing. */}
        {tab === 'Screens' && (
          <div className='ide-pane' style={{position: 'absolute', inset: 0, overflowY: 'auto'}}>
            <h1 className='ide-h1'>Screens</h1>
            <IdeRunScreens
              runId={runId}
              hasProducedWorkspace={artifactActions.preview}
              unavailableReason={artifactActions.workspaceReason}
            />
          </div>
        )}

        {/* ── Tests ── */}
        {tab === 'Tests' && (
          <div className='ide-pane' style={{position: 'absolute', inset: 0, overflowY: 'auto'}}>
            <h1 className='ide-h1' style={{display: 'flex', alignItems: 'center', gap: 10}}>
              Tests
              <button
                className='ide-btn ghost'
                style={{marginLeft: 'auto', padding: '4px 10px'}}
                disabled={busy === 'runtests' || testRunPending || !canRerunTests}
                title={
                  canRerunTests
                    ? 'Queue a test run for this workspace'
                    : 'No generated code or test plan is available to re-run'
                }
                onClick={() => void rerunTests()}
              >
                {testRunPending ? '⏳ Tests running…' : '↻ Re-run tests'}
              </button>
            </h1>
            {testRunPending && (
              <div className='ide-card' style={{marginBottom: 12, color: 'var(--ide-blue)'}}>
                Tests are running against the preview. This panel will refresh automatically.
              </div>
            )}
            {testsFailure ? (
              <LoadError failure={testsFailure} what='test results' />
            ) : !tests ? (
              <div className='ide-empty'>{noCodeTestMessage}</div>
            ) : (
              <>
                <div className='ide-card' style={{display: 'flex', gap: 18}}>
                  <div>
                    <div style={{fontSize: 22, fontWeight: 700}}>{testsSummary?.headline}</div>
                    <div style={{fontSize: 11, color: 'var(--ide-fg-muted)'}}>
                      {testsSummary?.label}
                    </div>
                  </div>
                  {testsSummary?.measured && (
                    <>
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
                    </>
                  )}
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
                {testsSummary?.explanation && (
                  <div className='ide-card' style={{marginTop: 10, color: 'var(--ide-yellow)'}}>
                    {testsSummary.explanation}
                  </div>
                )}
                {testsSummary?.measured && !testProof.functional && (
                  <div className='ide-card' style={{marginTop: 10, color: 'var(--ide-yellow)'}}>
                    {testProof.workspaceOnly
                      ? 'Execution scope: workspace test suite. These fresh results are code-level evidence, not end-to-end proof of the deployed product.'
                      : 'Execution provenance is unverified. These counts cannot certify product quality or final-goal completion.'}
                  </div>
                )}

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
                    {/* RR-765 — the list renders at most 60 rows; do not claim "All tests" when the
                        set is larger, or failures beyond row 60 look like they do not exist. */}
                    <div className='ide-h2'>
                      {tests.results.length > 60
                        ? `Tests · showing first 60 of ${tests.results.length}`
                        : `All tests · ${tests.results.length}`}
                    </div>
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
                                          reloadRun,
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
                    {tests.results.length > 60 && (
                      <div
                        style={{
                          fontSize: 11,
                          color: 'var(--ide-fg-muted)',
                          marginTop: 8,
                        }}
                      >
                        {tests.results.length - 60} more result
                        {tests.results.length - 60 === 1 ? '' : 's'} not shown. Download the full
                        test report to inspect every result.
                      </div>
                    )}
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
            <h1 className='ide-h1'>Ask about this work</h1>
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
              {chatFailure ? (
                // RR-766 — an outage is not an empty conversation.
                <LoadError failure={chatFailure} what='chat history' onRetry={loadRunChat} />
              ) : chat == null ? (
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
        busy={confirmBusy}
        // RR-763 — a synchronous one-shot latch: the confirm callback (often a paid
        // or destructive mutation) runs at most once even under rapid double-click
        // or repeated keyboard activation, and the dialog stays disabled until it
        // settles.
        onConfirm={async () => {
          if (confirmBusyRef.current) return;
          const c = confirm;
          if (!c) return;
          confirmBusyRef.current = true;
          setConfirmBusy(true);
          try {
            await c.onConfirm();
          } finally {
            confirmBusyRef.current = false;
            setConfirmBusy(false);
            setConfirm(null);
          }
        }}
        onClose={() => {
          if (!confirmBusyRef.current) setConfirm(null);
        }}
      />
    </div>
  );
}
