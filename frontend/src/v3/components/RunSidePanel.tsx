import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type ReactNode,
} from 'react';
import {
  type RunDetail,
  type TaskSummary,
  type ConversationMessage,
  retryTask,
  getLiveCost,
  conversationChat,
  getPrComments,
  dismissPrComment,
  applyPrComment,
  type PrCommentInboxDto,
  getTestPlan,
  runTests,
  getPreviewStatus,
  type TestPlanData,
  type LocalPreviewStatus,
  // 2026-05-09 — RunChat regression fix: re-import the v2 chat-action endpoints
  // (applyToRun / iterateRun + ValidationReport for the unresolved-issues helper)
  // so the v3 ChatPane can mount the same Apply / Fix / Iterate affordances the
  // v2 RunDetailTabs Chat tab has had since 2026-04. Backend (RunConversationController +
  // ApplyService) was preserved through the v3 cutover; the regression was frontend-only.
  applyToRun,
  iterateRun,
  getValidationReport,
  type ValidationReportData,
  type ReportIssue,
} from '@/lib/api';
import {useNavigate} from 'react-router-dom';
import {useSse} from '@/hooks/useSse';
import {useSseStore, type LogEntry} from '@/stores/useSseStore';
import PlanCard from './PlanCard';
import Pill from './Pill';
import RunPreviewPane from './RunPreviewPane';
import TaskTimeline from '@/components/runs/TaskTimeline';
import TestProgressTracker from '@/components/runs/TestProgressTracker';
import TestReportPanel from '@/components/runs/TestReportPanel';
import {MarkdownContent} from '@/components/shared/MarkdownContent';
import {
  AlertTriangle,
  ArrowUpCircle,
  Bug,
  CheckCircle2,
  Clock,
  DollarSign,
  Eye,
  FileCode,
  FileEdit,
  FilePlus2,
  FlaskConical,
  GitCommit,
  Globe,
  MessageSquare,
  PauseCircle,
  PlayCircle,
  RotateCcw,
  Rocket,
  Search,
  Shield,
  Terminal,
  Wrench,
  XCircle,
  Zap,
} from 'lucide-react';

/**
 * Right-side panel on RunDetailPage — surfaces the operator views that
 * got cut when the v2 RunDetailTabs (Overview / Plan / Timeline / Live
 * Log / Code) was retired alongside the v2 chrome.
 *
 * Six tabs:
 *   - Overview — provider · model · cost · tokens · GitHub PR link, and
 *               a phase rollup (planner / worker / reviewer) when the
 *               cost endpoint is wired. Polls /runs/{id}/cost every 5s
 *               while the run is in flight (mirrors v2 RunDetailTabs).
 *   - Plan    — task list with live status; embeds PlanCard inline when
 *               the run is AWAITING_APPROVAL so users hitting the URL
 *               directly can approve without bouncing back to Compose.
 *   - Timeline — chronological swimlane (TaskTimeline.tsx, reused from
 *               the v2 RunDetailTabs surface).
 *   - Log     — SSE event stream (tool calls, phase transitions, retry
 *               triggers), tail-follow.
 *   - Tests   — TestProgressTracker (live) + TestReportPanel (results)
 *               for the post-run test-gen pipeline. Quiet for runs that
 *               haven't kicked off test gen yet.
 *   - Preview — auto-preview iframe; falls back to a "Start preview"
 *               CTA when the deploy/preview-engine hasn't kicked in yet.
 *   - Chat    — 2026-05-07 — run-scoped chat. Wraps POST /api/conversation
 *               with the run's spec / status / verdict pre-seeded into
 *               the thread so the assistant can answer "what changed?",
 *               "why did this fail?", "summarise the diff" without the
 *               user re-pasting context. Available regardless of project
 *               linkage (works on FREE / unlinked runs too — the chat
 *               endpoint has no tier gate).
 *
 * No new backend. Reuses /runs/{id}/events SSE channel + /runs/{id}/preview/*
 * + /runs/{id}/tasks/{taskId}/retry + /runs/{id}/cost — all of which the
 * backend kept shipping when v2 RunDetailTabs was deleted.
 */
type Tab = 'overview' | 'plan' | 'timeline' | 'log' | 'tests' | 'preview' | 'chat';

export default function RunSidePanel({
  runId,
  detail,
  onRefetch,
}: {
  runId: string;
  detail: RunDetail | null;
  onRefetch?: () => void;
}) {
  const status = detail?.status ?? '';
  const isInFlight = !['COMPLETE', 'PARTIAL', 'FAILED', 'CANCELLED', 'ABORTED'].includes(status);
  const isAwaitingApproval = status === 'AWAITING_APPROVAL';

  // Default tab:
  //   - AWAITING_APPROVAL → Plan (gate is the action)
  //   - in-flight non-gate → Plan (so users can watch tasks march)
  //   - terminal-ok → Overview (cost / PR / verdict at a glance)
  //   - terminal-fail → Plan (what failed where)
  const [tab, setTab] = useState<Tab>('plan');
  useEffect(() => {
    if (isAwaitingApproval) setTab('plan');
    else if (status === 'COMPLETE') setTab('overview');
  }, [isAwaitingApproval, status]);

  // 2026-05-07 — Phase P1: pull pending PR review comments for this run on
  // mount + every PR_COMMENT_RECEIVED SSE event. Drives both the Chat-tab
  // badge (count) and the ChatPane inbox listing (list). Single source of
  // truth here so both consumers stay coherent without re-fetching twice.
  const [prComments, setPrComments] = useState<PrCommentInboxDto[]>([]);
  const refetchPrComments = useMemo(
    () => () => {
      if (!runId) return;
      getPrComments(runId)
        .then(setPrComments)
        .catch(() => setPrComments([]));
    },
    [runId],
  );
  useEffect(() => {
    refetchPrComments();
  }, [refetchPrComments]);
  const sseEntriesForPr = useSseStore((s) => s.entries);
  const lastEntryIdForPr = sseEntriesForPr[sseEntriesForPr.length - 1]?.id ?? null;
  const lastEntryTypeForPr = sseEntriesForPr[sseEntriesForPr.length - 1]?.type ?? null;
  const seenPrEntryRef = useRef<string | null>(null);
  useEffect(() => {
    if (!lastEntryIdForPr || lastEntryIdForPr === seenPrEntryRef.current) return;
    seenPrEntryRef.current = lastEntryIdForPr;
    if (lastEntryTypeForPr === 'PR_COMMENT_RECEIVED') refetchPrComments();
  }, [lastEntryIdForPr, lastEntryTypeForPr, refetchPrComments]);

  const pendingPrComments = prComments.filter((c) => c.status === 'PENDING').length;

  return (
    <aside
      // 2026-05-10 (mobile audit) — sizing now lives in the v3-rundetail-side
      // class (defined in v3.css) so a min-width: 768px media query can drop
      // both the calc()-based maxHeight and the 480px minHeight on mobile.
      // On the stacked mobile layout the side panel is the bottom-most pane;
      // capping it there cropped half the chat thread off-screen, and the
      // 480px floor forced inflated whitespace on a Plan tab with 1 task.
      className='v3-rundetail-side'
      style={{
        background: 'var(--v3-surface)',
        border: '1px solid var(--v3-border)',
        borderRadius: 8,
        display: 'flex',
        flexDirection: 'column',
        overflow: 'hidden',
      }}
      aria-label='Run side panel'
    >
      <TabBar
        tab={tab}
        onChange={setTab}
        showInFlightDot={isInFlight}
        showApprovalBadge={isAwaitingApproval}
        chatBadgeCount={pendingPrComments}
      />
      <div style={{flex: 1, overflow: 'auto', display: 'flex', flexDirection: 'column'}}>
        {tab === 'overview' && <OverviewPane runId={runId} detail={detail} live={isInFlight} />}
        {tab === 'plan' && (
          <PlanPane
            runId={runId}
            detail={detail}
            isAwaitingApproval={isAwaitingApproval}
            onApproved={onRefetch}
          />
        )}
        {tab === 'timeline' && <TimelinePane detail={detail} />}
        {tab === 'log' && <LogPane runId={runId} live={isInFlight} />}
        {tab === 'tests' && <TestsPane runId={runId} status={status} />}
        {tab === 'preview' && <RunPreviewPane runId={runId} />}
        {tab === 'chat' && (
          <ChatPane
            detail={detail}
            prComments={prComments}
            onPrCommentChanged={refetchPrComments}
          />
        )}
      </div>
    </aside>
  );
}

// ── Tab bar ─────────────────────────────────────────────────────────────

function TabBar({
  tab,
  onChange,
  showInFlightDot,
  showApprovalBadge,
  chatBadgeCount,
}: {
  tab: Tab;
  onChange: (t: Tab) => void;
  showInFlightDot: boolean;
  showApprovalBadge: boolean;
  chatBadgeCount: number;
}) {
  const items: {key: Tab; label: string; badge?: ReactNode}[] = [
    {key: 'overview', label: 'Overview'},
    {
      key: 'plan',
      label: 'Plan',
      badge: showApprovalBadge ? (
        <span
          style={{
            marginLeft: 6,
            fontSize: 9,
            padding: '1px 6px',
            borderRadius: 999,
            background: 'rgba(245,158,11,0.18)',
            color: '#FBBF24',
            border: '1px solid rgba(245,158,11,0.40)',
            fontWeight: 600,
          }}
        >
          Action
        </span>
      ) : null,
    },
    {key: 'timeline', label: 'Timeline'},
    {
      key: 'log',
      label: 'Log',
      badge: showInFlightDot ? (
        <span
          aria-label='live'
          style={{
            marginLeft: 6,
            width: 6,
            height: 6,
            borderRadius: 999,
            background: 'var(--v3-brand)',
            display: 'inline-block',
            animation: 'v3PulseDot 1.4s ease-in-out infinite',
          }}
        />
      ) : null,
    },
    {key: 'tests', label: 'Tests'},
    {key: 'preview', label: 'Preview'},
    {
      key: 'chat',
      label: 'Chat',
      // 2026-05-07 — Phase P1: numeric badge when there are pending incoming PR
      // review comments on the run's PR. Same amber palette as the Plan-tab
      // Action badge so "needs your attention" reads consistently across tabs.
      badge:
        chatBadgeCount > 0 ? (
          <span
            aria-label={`${chatBadgeCount} pending PR comment${chatBadgeCount === 1 ? '' : 's'}`}
            style={{
              marginLeft: 6,
              fontSize: 9,
              padding: '1px 6px',
              borderRadius: 999,
              background: 'rgba(245,158,11,0.18)',
              color: '#FBBF24',
              border: '1px solid rgba(245,158,11,0.40)',
              fontWeight: 600,
            }}
          >
            {chatBadgeCount}
          </span>
        ) : null,
    },
  ];

  return (
    <div
      role='tablist'
      // 2026-05-10 (mobile audit) — 7 tabs (Overview / Plan / Timeline / Log /
      // Tests / Preview / Chat) cannot fit at flex:1 on a 320-360px viewport
      // (each label needs ~50-60px @ 11.5px font, total ~420px). Switched to
      // horizontal-scroll with `flex: 0 0 auto` per tab so tabs keep their
      // intrinsic width and the strip scrolls. scrollbar-hide ensures the
      // touch hint stays invisible until the user swipes. Below 720px the
      // tabs auto-shrink to keep four-five visible without scrolling.
      className='scrollbar-hide v3-scroll-fade-x'
      style={{
        display: 'flex',
        borderBottom: '1px solid var(--v3-border)',
        background: 'var(--v3-bg)',
        overflowX: 'auto',
      }}
    >
      {items.map((it) => {
        const isActive = tab === it.key;
        return (
          <button
            key={it.key}
            role='tab'
            type='button'
            aria-selected={isActive}
            onClick={() => onChange(it.key)}
            style={{
              flex: '1 1 auto',
              minWidth: 'max-content',
              padding: '10px 12px',
              fontSize: 11.5,
              fontWeight: isActive ? 600 : 500,
              whiteSpace: 'nowrap',
              color: isActive ? 'var(--v3-text)' : 'var(--v3-text-muted)',
              background: 'transparent',
              border: 'none',
              borderBottom: `2px solid ${isActive ? 'var(--v3-brand)' : 'transparent'}`,
              cursor: 'pointer',
              fontFamily: 'inherit',
              display: 'inline-flex',
              alignItems: 'center',
              justifyContent: 'center',
              transition: 'color 0.12s, border-color 0.12s',
            }}
          >
            {it.label}
            {it.badge}
          </button>
        );
      })}
      <style>{`@keyframes v3PulseDot { 0%,100% {opacity: 0.45} 50% {opacity: 1} }`}</style>
    </div>
  );
}

// ── Plan pane ───────────────────────────────────────────────────────────

function PlanPane({
  runId,
  detail,
  isAwaitingApproval,
  onApproved,
}: {
  runId: string;
  detail: RunDetail | null;
  isAwaitingApproval: boolean;
  onApproved?: () => void;
}) {
  if (!detail) {
    return (
      <div style={{padding: 16, fontSize: 12, color: 'var(--v3-text-subtle)'}}>Loading plan…</div>
    );
  }

  return (
    <div style={{padding: 12, display: 'flex', flexDirection: 'column', gap: 12}}>
      {isAwaitingApproval && (
        <div>
          <PlanCard runId={runId} onApproved={onApproved} />
        </div>
      )}

      {detail.runSummary && !isAwaitingApproval && (
        <div
          style={{
            fontSize: 12,
            color: 'var(--v3-text-muted)',
            lineHeight: 1.55,
            padding: '8px 10px',
            background: 'var(--v3-bg)',
            border: '1px solid var(--v3-border)',
            borderRadius: 6,
          }}
        >
          {detail.runSummary}
        </div>
      )}

      {detail.tasks && detail.tasks.length > 0 && (
        <div>
          <div
            style={{
              fontSize: 11,
              fontWeight: 600,
              color: 'var(--v3-text-subtle)',
              letterSpacing: 0.5,
              textTransform: 'uppercase',
              padding: '0 2px 6px',
            }}
          >
            Tasks ({detail.tasks.length})
          </div>
          <ul
            style={{
              margin: 0,
              padding: 0,
              listStyle: 'none',
              display: 'flex',
              flexDirection: 'column',
              gap: 6,
            }}
          >
            {detail.tasks.map((t, i) => (
              <TaskRow
                key={t.taskId}
                index={i}
                task={t}
                runId={runId}
                canRetry={!isAwaitingApproval}
              />
            ))}
          </ul>
        </div>
      )}

      {(!detail.tasks || detail.tasks.length === 0) && !isAwaitingApproval && (
        <div style={{padding: 12, fontSize: 12, color: 'var(--v3-text-subtle)'}}>
          The planner hasn't emitted tasks yet. They'll appear here once the plan is ready.
        </div>
      )}
    </div>
  );
}

const TASK_STATUS_TONE: Record<string, 'ok' | 'err' | 'warn' | 'info' | 'neutral'> = {
  COMPLETE: 'ok',
  COMPLETED: 'ok',
  DONE: 'ok',
  WORKING: 'info',
  PLANNING: 'info',
  RUNNING: 'info',
  EXECUTING: 'info',
  REVIEWING: 'info',
  PARTIAL: 'warn',
  PARTIAL_WITH_WARNINGS: 'warn',
  PENDING: 'neutral',
  QUEUED: 'neutral',
  WAITING: 'neutral',
  FAILED: 'err',
  ERROR: 'err',
  CANCELLED: 'neutral',
};

function TaskRow({
  index,
  task,
  runId,
  canRetry,
}: {
  index: number;
  task: TaskSummary;
  runId: string;
  canRetry: boolean;
}) {
  const [retrying, setRetrying] = useState(false);
  const upperStatus = task.status?.toUpperCase() ?? '';
  // 2026-05-10 — soft-completion detection. Backend persists status="COMPLETE"
  // with `failureReason` populated (from agent_task.last_llm_failure) when the
  // executor accepts work that bypassed the completeTask handshake but wrote
  // files + passed the smoke gate (SequentialExecutor:583/613/638). In-memory
  // the executor knows this is `TaskOutcome.Status.COMPLETED_WITH_WARNING`,
  // but the DB column is just COMPLETE — so the task chip rendered green
  // while the failureReason box rendered red right below it. Same task, two
  // opposite signals → looked like a UI bug. Detect the combination here and
  // collapse to a single amber/warning treatment so the row reads coherently.
  const hasSoftCompletionWarning =
    upperStatus === 'COMPLETE' &&
    typeof task.failureReason === 'string' &&
    task.failureReason.trim().length > 0;
  const tone = hasSoftCompletionWarning ? 'warn' : (TASK_STATUS_TONE[upperStatus] ?? 'neutral');
  const tokens = (task.inputTokens ?? 0) + (task.outputTokens ?? 0);
  const isFailed = ['FAILED', 'ERROR', 'PARTIAL'].includes(upperStatus);
  const isRunning = ['WORKING', 'PLANNING', 'RUNNING', 'EXECUTING', 'REVIEWING'].includes(
    upperStatus,
  );

  return (
    <li
      style={{
        background: 'var(--v3-bg)',
        border: '1px solid var(--v3-border)',
        borderRadius: 6,
        padding: '8px 10px',
      }}
    >
      <div style={{display: 'flex', alignItems: 'flex-start', gap: 8}}>
        <span
          style={{
            fontFamily: 'var(--v3-font-mono)',
            fontSize: 10,
            color: 'var(--v3-text-subtle)',
            flexShrink: 0,
            minWidth: 18,
          }}
        >
          {String(index + 1).padStart(2, '0')}
        </span>
        <div style={{flex: 1, minWidth: 0}}>
          <div
            style={{
              fontSize: 12,
              color: 'var(--v3-text)',
              fontWeight: 500,
              lineHeight: 1.4,
              wordBreak: 'break-word',
            }}
          >
            {task.title}
          </div>
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 6,
              marginTop: 4,
              flexWrap: 'wrap',
            }}
          >
            <Pill tone={tone} size='sm' dot={isRunning}>
              {hasSoftCompletionWarning
                ? 'completed with warnings'
                : (task.status ?? 'pending').toLowerCase().replace(/_/g, ' ')}
            </Pill>
            {tokens > 0 && (
              <span
                style={{
                  fontFamily: 'var(--v3-font-mono)',
                  fontSize: 10,
                  color: 'var(--v3-text-subtle)',
                }}
              >
                {tokens.toLocaleString()} tok
              </span>
            )}
            {task.durationMs > 0 && (
              <span
                style={{
                  fontFamily: 'var(--v3-font-mono)',
                  fontSize: 10,
                  color: 'var(--v3-text-subtle)',
                }}
              >
                {(task.durationMs / 1000).toFixed(1)}s
              </span>
            )}
            {isFailed && canRetry && (
              <button
                type='button'
                disabled={retrying}
                onClick={async () => {
                  setRetrying(true);
                  try {
                    await retryTask(runId, task.taskId);
                  } catch (e) {
                    alert((e as Error)?.message ?? 'Retry failed');
                  } finally {
                    setRetrying(false);
                  }
                }}
                style={{
                  marginLeft: 'auto',
                  padding: '2px 8px',
                  fontSize: 10.5,
                  fontWeight: 500,
                  background: 'transparent',
                  color: 'var(--v3-brand-hover, var(--v3-brand))',
                  border: '1px solid rgba(91,141,239,0.40)',
                  borderRadius: 4,
                  cursor: retrying ? 'wait' : 'pointer',
                  fontFamily: 'inherit',
                }}
              >
                {retrying ? '…' : 'Retry'}
              </button>
            )}
          </div>
          {task.failureReason && (
            <div
              style={{
                marginTop: 6,
                padding: '4px 8px',
                // Soft-completion (status=COMPLETE + failureReason): the worker
                // bailed without completeTask but the executor accepted the
                // work because files + smoke passed. Render in amber (matching
                // the chip) — red implied genuine failure. For real failures
                // (FAILED / PARTIAL / ERROR) the existing red treatment stays.
                background: hasSoftCompletionWarning
                  ? 'color-mix(in srgb, var(--v3-warning) 10%, transparent)'
                  : 'rgba(239,68,68,0.10)',
                border: hasSoftCompletionWarning
                  ? '1px solid color-mix(in srgb, var(--v3-warning) 35%, transparent)'
                  : '1px solid rgba(239,68,68,0.30)',
                borderRadius: 4,
                fontSize: 11,
                color: hasSoftCompletionWarning ? 'var(--v3-warning)' : '#FCA5A5',
                lineHeight: 1.4,
              }}
            >
              {task.failureReason}
            </div>
          )}
        </div>
      </div>
    </li>
  );
}

// ── Log pane ────────────────────────────────────────────────────────────

// ── Log pane (v3 port of v2 LiveLog) ───────────────────────────────────────
//
// 2026-05-07 — full UX port of `frontend/src/components/runs/LiveLog.tsx`
// (v2 dev-branch implementation). The previous v3 LogPane just rendered
// `time + type pill + payload string` per row. The user reported "the
// live log had better presentation earlier than just showing JSON" —
// referring to the v2 surface that categorised events into:
//   1. Milestone banners (icon + colour + human text + timestamp) for
//      every lifecycle event the backend emits (RUN_STATUS, TASK_STATUS,
//      GATE_*, COST_UPDATE, DEPLOY_*, REVIEW_*, TEST_GEN_*, PREVIEW_*,
//      VALIDATION_*, etc.).
//   2. Compact file-op rows for `[write_file result]` / `[edit_file
//      result]` / `[commit result]` / `[run result]` log lines so the
//      user sees "Created src/foo.ts" instead of the raw tool dump.
//   3. Suppressed noise rows for low-value internals
//      (TOOL_DISPATCHED, LLM_REQUEST_SENT, "Iteration N (",
//      STALL_TRUNCATION, COMPACTION_TRIGGERED).
//   4. Emoji-prefix banner formatting (✅⚠️❌🔧) and known-agent
//      taskId banners (harness/planner/compiler/reviewer/preview-agent).
//   5. Status bar at the bottom with a pulsing dot when streaming.
//   6. Newest-LAST ordering with auto-scroll-to-bottom + pause-on-scroll-up.
//
// Reused as-is from v2; tokens swapped for v3 (--v3-success / --v3-warning
// / --v3-error / --v3-brand). Uses inline-style banners instead of
// Tailwind classes so there's no v2 token leak.

const MILESTONE_TYPES = new Set([
  'RUN_STATUS',
  'TASK_STATUS',
  'GATE_OPENED',
  'GATE_RESOLVED',
  'COMPLETE',
  'COST_UPDATE',
  'TOKEN_USAGE',
  'QUEUED',
  'RECOVERY_ATTEMPTED',
  'RUN_RESTARTED',
  'BUDGET_EXCEEDED',
  'VALIDATION_REPORT_READY',
  'RUN_PROVIDER_SWITCHED',
  // Deploy / preview engine
  'DEPLOY_STARTED',
  'DEPLOY_BUILDING',
  'DEPLOY_PUSHING',
  'DEPLOY_LIVE',
  'DEPLOY_FAILED',
  'ENGINE_DEPLOY_STARTED',
  'ENGINE_PHASE',
  'ENGINE_BUILDING',
  'ENGINE_DEPLOYING',
  'ENGINE_DEPLOY_LIVE',
  'ENGINE_DEPLOY_FAILED',
  // Reviewer
  'REVIEW_STARTED',
  'REVIEW_ISSUE_FOUND',
  'REVIEW_AUTO_FIX_STARTED',
  'REVIEW_AUTO_FIX_RESULT',
  'REVIEW_COMPLETE',
  // Test gen pipeline
  'TEST_GEN_STARTED',
  'TEST_PLANNING_STARTED',
  'TEST_PLANNING_COMPLETED',
  'TEST_GENERATION_STARTED',
  'TEST_COMPILATION_STARTED',
  'TEST_COMPILATION_PASSED',
  'TEST_COMPILATION_FAILED',
  'TEST_EXECUTION_STARTED',
  'TEST_EXECUTION_SKIPPED',
  'TEST_REPORT_READY',
  'TEST_PIPELINE_FAILED',
  // Context / validation
  'CONTEXT_EXTRACTED',
  'CLARIFICATION_NEEDED',
  'CLARIFICATION_RESOLVED',
  'MODEL_ESCALATED',
  'API_SPEC_READY',
  // Preview lifecycle
  'PREVIEW_ANALYZING',
  'PREVIEW_BUILDING',
  'PREVIEW_STARTING',
  'PREVIEW_READY',
  'PREVIEW_STOPPED',
  'PREVIEW_TIMED_OUT',
  'PREVIEW_FAILED',
  'VALIDATION_STARTED',
  'VALIDATION_PASSED',
  'VALIDATION_FAILED',
  // Batch (v2 batch lifecycle, kept for forward-compat)
  'BATCH_PROGRESS',
  'BATCH_COMPLETE',
  'BATCH_FAILED',
  // 2026-05-07 — Phase L1: deterministic auto-format pass between executor and reviewer.
  'LINT_AUTOFIX_APPLIED',
  // 2026-05-07 — Phase P1: incoming PR review comment on a PR ExecuteSpec opened.
  'PR_COMMENT_RECEIVED',
]);

type FileOp = {kind: 'write' | 'edit' | 'commit' | 'command'; path?: string};

function detectFileOp(msg: string): FileOp | null {
  if (/\[(write_file|writeFile)\s+result\]/i.test(msg)) {
    const m = msg.match(/(?:\bto\b|\bfile:\b)\s+([\w./\\-]+\.\w+)/i);
    return {kind: 'write', path: m?.[1]};
  }
  if (/\[(edit_file|editFile)\s+result\]/i.test(msg)) {
    const m = msg.match(/(?:\bin\b|\bfile:\b)\s+([\w./\\-]+\.\w+)/i);
    return {kind: 'edit', path: m?.[1]};
  }
  if (/\[(commit|git_commit|gitCommit)\s+result\]/i.test(msg)) return {kind: 'commit'};
  if (/\[(run|run_command|run_build|run_tests|shell)\s+result\]/i.test(msg))
    return {kind: 'command'};
  return null;
}

/** taskId like "task-1-project_setup" → "Project Setup". */
function humanizeTaskId(taskId: string): string {
  if (!taskId) return '';
  const parts = taskId.split('-');
  const nameIdx = parts.findIndex((p) => isNaN(Number(p)) && p !== 'task' && p !== 'worker');
  const name = parts.slice(nameIdx >= 0 ? nameIdx : 2).join(' ');
  return name ? name.replace(/_/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase()) : taskId;
}

function formatTimestamp(ts: number): string {
  return new Date(ts).toISOString().split('T')[1].slice(0, 8);
}

function formatLogMessage(data: Record<string, unknown>): string {
  return String(data.message ?? data.payload ?? '');
}

// Token palette — v3 inline so palette swaps cascade through tokens.css.
const TONE = {
  blue: {
    fg: 'var(--v3-brand-hover)',
    bd: 'color-mix(in srgb, var(--v3-brand) 50%, transparent)',
    bg: 'color-mix(in srgb, var(--v3-brand) 8%, transparent)',
  },
  blueSoft: {
    fg: 'var(--v3-text-muted)',
    bd: 'color-mix(in srgb, var(--v3-brand) 35%, transparent)',
    bg: 'color-mix(in srgb, var(--v3-brand) 4%, transparent)',
  },
  green: {
    fg: 'var(--v3-success)',
    bd: 'color-mix(in srgb, var(--v3-success) 50%, transparent)',
    bg: 'color-mix(in srgb, var(--v3-success) 8%, transparent)',
  },
  greenSoft: {
    fg: 'color-mix(in srgb, var(--v3-success) 80%, var(--v3-text))',
    bd: 'color-mix(in srgb, var(--v3-success) 35%, transparent)',
    bg: 'color-mix(in srgb, var(--v3-success) 5%, transparent)',
  },
  amber: {
    fg: 'var(--v3-warning)',
    bd: 'color-mix(in srgb, var(--v3-warning) 50%, transparent)',
    bg: 'color-mix(in srgb, var(--v3-warning) 8%, transparent)',
  },
  red: {
    fg: 'var(--v3-error)',
    bd: 'color-mix(in srgb, var(--v3-error) 50%, transparent)',
    bg: 'color-mix(in srgb, var(--v3-error) 8%, transparent)',
  },
  neutral: {
    fg: 'var(--v3-text-muted)',
    bd: 'var(--v3-border)',
    bg: 'var(--v3-bg)',
  },
} as const;
type ToneKey = keyof typeof TONE;

function bannerStyle(tone: ToneKey): CSSProperties {
  const t = TONE[tone];
  return {
    display: 'flex',
    alignItems: 'center',
    gap: 8,
    padding: '5px 10px',
    borderRadius: 4,
    border: `1px solid ${t.bd}`,
    background: t.bg,
    color: t.fg,
    fontSize: 11.5,
    fontWeight: 500,
    margin: '3px 0',
  };
}

interface MilestoneSpec {
  icon: ReactNode;
  text: string;
  tone: ToneKey;
}

/** Map (type, payload) → banner spec, or null to fall through to the LOG path. */
function deriveMilestone(entry: LogEntry): MilestoneSpec | null {
  const {type, data} = entry;
  const payloadStr = String((data as Record<string, unknown>)?.payload ?? '');
  const status = String((data as Record<string, unknown>)?.status ?? payloadStr);

  // Run / task status
  if (type === 'TASK_STATUS') {
    const label = humanizeTaskId(String((data as Record<string, unknown>)?.taskId ?? ''));
    const s = status.toUpperCase();
    if (s === 'IN_PROGRESS' || s === 'RUNNING' || s === 'WORKING')
      return {icon: <Zap className='h-3.5 w-3.5' />, text: `Task started: ${label}`, tone: 'blue'};
    if (s === 'COMPLETE' || s === 'COMPLETED')
      return {
        icon: <CheckCircle2 className='h-3.5 w-3.5' />,
        text: `Task done: ${label}`,
        tone: 'green',
      };
    if (s === 'FAILED')
      return {
        icon: <XCircle className='h-3.5 w-3.5' />,
        text: `Task failed: ${label}`,
        tone: 'red',
      };
    if (s === 'PARTIAL')
      return {
        icon: <AlertTriangle className='h-3.5 w-3.5' />,
        text: `Task partial: ${label}`,
        tone: 'amber',
      };
    return {
      icon: <Clock className='h-3.5 w-3.5' />,
      text: `Task ${s.toLowerCase()}: ${label}`,
      tone: 'neutral',
    };
  }
  if (type === 'RUN_STATUS') {
    const s = status.toUpperCase();
    if (s === 'COMPLETE')
      return {
        icon: <CheckCircle2 className='h-3.5 w-3.5' />,
        text: 'Run completed successfully',
        tone: 'green',
      };
    if (s === 'PARTIAL')
      return {
        icon: <AlertTriangle className='h-3.5 w-3.5' />,
        text: 'Run partial — some tasks need manual retry',
        tone: 'amber',
      };
    if (s === 'FAILED')
      return {
        icon: <XCircle className='h-3.5 w-3.5' />,
        text: 'Run failed — check tasks for retry',
        tone: 'red',
      };
    if (s === 'CANCELLED' || s === 'ABORTED')
      return {
        icon: <PauseCircle className='h-3.5 w-3.5' />,
        text: `Run ${s.toLowerCase()}`,
        tone: 'neutral',
      };
    return {icon: <Rocket className='h-3.5 w-3.5' />, text: `Run → ${s}`, tone: 'blue'};
  }

  // Gates
  if (type === 'GATE_OPENED')
    return {
      icon: <PauseCircle className='h-3.5 w-3.5' />,
      text: `Waiting for ${payloadStr || 'approval'} approval`,
      tone: 'amber',
    };
  if (type === 'GATE_RESOLVED') {
    const decision = payloadStr.toUpperCase();
    const approved = decision === 'APPROVED';
    return {
      icon: approved ? <PlayCircle className='h-3.5 w-3.5' /> : <XCircle className='h-3.5 w-3.5' />,
      text: `Gate ${decision.toLowerCase()}`,
      tone: approved ? 'green' : 'red',
    };
  }

  // Cost / tokens
  if (type === 'COST_UPDATE') {
    const costNum = Number((data as Record<string, unknown>)?.totalCost ?? Number(payloadStr) ?? 0);
    return {
      icon: <DollarSign className='h-3.5 w-3.5' />,
      text: `Cost: $${costNum.toFixed(4)}`,
      tone: 'greenSoft',
    };
  }
  if (type === 'TOKEN_USAGE')
    return {
      icon: <DollarSign className='h-3.5 w-3.5' />,
      text: `Tokens · ${payloadStr}`,
      tone: 'amber',
    };

  // Generic completion
  if (type === 'COMPLETE')
    return {
      icon: <CheckCircle2 className='h-3.5 w-3.5' />,
      text: 'Pipeline complete',
      tone: 'green',
    };
  if (type === 'RECOVERY_ATTEMPTED')
    return {
      icon: <RotateCcw className='h-3.5 w-3.5' />,
      text: `Recovery: ${payloadStr}`,
      tone: 'amber',
    };
  if (type === 'BUDGET_EXCEEDED')
    return {
      icon: <XCircle className='h-3.5 w-3.5' />,
      text: `Budget exceeded${payloadStr ? `: ${payloadStr}` : ''}`,
      tone: 'red',
    };
  if (type === 'VALIDATION_REPORT_READY')
    return {
      icon: <CheckCircle2 className='h-3.5 w-3.5' />,
      text: 'Validation report ready',
      tone: 'green',
    };
  if (type === 'RUN_PROVIDER_SWITCHED')
    return {
      icon: <ArrowUpCircle className='h-3.5 w-3.5' />,
      text: `Provider switched · ${payloadStr}`,
      tone: 'amber',
    };

  // Deploy / preview engine
  if (type === 'DEPLOY_STARTED' || type === 'ENGINE_DEPLOY_STARTED')
    return {icon: <Rocket className='h-3.5 w-3.5' />, text: 'Deployment started', tone: 'blue'};
  if (type === 'DEPLOY_BUILDING' || type === 'ENGINE_BUILDING')
    return {
      icon: <Rocket className='h-3.5 w-3.5' />,
      text: 'Building container image…',
      tone: 'blueSoft',
    };
  if (type === 'DEPLOY_PUSHING' || type === 'ENGINE_DEPLOYING')
    return {
      icon: <Rocket className='h-3.5 w-3.5' />,
      text: type.includes('PUSHING') ? 'Pushing image…' : 'Deploying service…',
      tone: 'blueSoft',
    };
  if (type === 'DEPLOY_LIVE' || type === 'ENGINE_DEPLOY_LIVE') {
    const url = payloadStr.split('|').pop();
    return {icon: <CheckCircle2 className='h-3.5 w-3.5' />, text: `Live at ${url}`, tone: 'green'};
  }
  if (type === 'DEPLOY_FAILED' || type === 'ENGINE_DEPLOY_FAILED') {
    const err = payloadStr.split('|').pop();
    return {icon: <XCircle className='h-3.5 w-3.5' />, text: `Deploy failed: ${err}`, tone: 'red'};
  }
  if (type === 'ENGINE_PHASE') {
    const parts = payloadStr.split('|');
    return {
      icon: <Rocket className='h-3.5 w-3.5' />,
      text: `Deploy: ${parts[1] ?? parts[0] ?? ''}`,
      tone: 'blueSoft',
    };
  }

  // Reviewer V2 events
  if (type === 'REVIEW_STARTED')
    return {
      icon: <Eye className='h-3.5 w-3.5' />,
      text: `Code review started${payloadStr ? ` (${payloadStr})` : ''}`,
      tone: 'blue',
    };
  if (type === 'REVIEW_ISSUE_FOUND') {
    const parts = payloadStr.split('|');
    const severity = parts[1] ?? 'UNKNOWN';
    const desc = parts[2] ?? parts[0] ?? '';
    const isCritical = severity === 'CRITICAL';
    return {
      icon: isCritical ? (
        <Bug className='h-3.5 w-3.5' />
      ) : (
        <AlertTriangle className='h-3.5 w-3.5' />
      ),
      text: `Issue: ${desc}`,
      tone: isCritical ? 'red' : 'amber',
    };
  }
  if (type === 'REVIEW_AUTO_FIX_STARTED')
    return {
      icon: <Wrench className='h-3.5 w-3.5' />,
      text: `Auto-fixing ${payloadStr}`,
      tone: 'blueSoft',
    };
  if (type === 'REVIEW_AUTO_FIX_RESULT') {
    const parts = payloadStr.split('|');
    const success = parts[1] === 'PASS';
    return {
      icon: success ? (
        <CheckCircle2 className='h-3.5 w-3.5' />
      ) : (
        <XCircle className='h-3.5 w-3.5' />
      ),
      text: `Auto-fix ${parts[0]}: ${success ? 'succeeded' : 'failed'}`,
      tone: success ? 'green' : 'red',
    };
  }
  if (type === 'REVIEW_COMPLETE') {
    const parts = payloadStr.split('|');
    const verdict = parts[0] ?? '';
    const isGood =
      verdict === 'APPROVED' || verdict === 'APPROVED_WITH_NOTES' || verdict === 'CLEAN';
    return {
      icon: isGood ? <CheckCircle2 className='h-3.5 w-3.5' /> : <XCircle className='h-3.5 w-3.5' />,
      text: `Review: ${verdict.replace(/_/g, ' ').toLowerCase()}`,
      tone: isGood ? 'green' : 'red',
    };
  }

  // Test gen pipeline
  if (type === 'TEST_GEN_STARTED')
    return {
      icon: <FlaskConical className='h-3.5 w-3.5' />,
      text: 'Test generation started',
      tone: 'blue',
    };
  if (type === 'TEST_PLANNING_STARTED')
    return {
      icon: <FlaskConical className='h-3.5 w-3.5' />,
      text: 'Planning test strategy…',
      tone: 'blueSoft',
    };
  if (type === 'TEST_PLANNING_COMPLETED')
    return {
      icon: <FlaskConical className='h-3.5 w-3.5' />,
      text: `Test plan ready: ${payloadStr || '?'} test tasks`,
      tone: 'blueSoft',
    };
  if (type === 'TEST_GENERATION_STARTED')
    return {
      icon: <FileCode className='h-3.5 w-3.5' />,
      text: `Generating ${payloadStr || '?'} test suites…`,
      tone: 'blueSoft',
    };
  if (type === 'TEST_COMPILATION_STARTED')
    return {
      icon: <FlaskConical className='h-3.5 w-3.5' />,
      text: 'Compiling tests…',
      tone: 'blueSoft',
    };
  if (type === 'TEST_COMPILATION_PASSED')
    return {
      icon: <CheckCircle2 className='h-3.5 w-3.5' />,
      text: 'Tests compiled successfully',
      tone: 'greenSoft',
    };
  if (type === 'TEST_COMPILATION_FAILED')
    return {
      icon: <XCircle className='h-3.5 w-3.5' />,
      text: `Test compilation failed: ${payloadStr.slice(0, 100)}`,
      tone: 'red',
    };
  if (type === 'TEST_EXECUTION_STARTED')
    return {
      icon: <FlaskConical className='h-3.5 w-3.5' />,
      text: 'Running tests against preview…',
      tone: 'blue',
    };
  if (type === 'TEST_EXECUTION_SKIPPED')
    return {
      icon: <AlertTriangle className='h-3.5 w-3.5' />,
      text: `Test execution skipped: ${payloadStr}`,
      tone: 'amber',
    };
  if (type === 'TEST_REPORT_READY')
    return {
      icon: <CheckCircle2 className='h-3.5 w-3.5' />,
      text: 'Test report ready',
      tone: 'green',
    };
  if (type === 'TEST_PIPELINE_FAILED')
    return {
      icon: <XCircle className='h-3.5 w-3.5' />,
      text: `Test pipeline failed: ${payloadStr.slice(0, 100)}`,
      tone: 'red',
    };

  // Context / validation / preview
  if (type === 'CONTEXT_EXTRACTED')
    return {
      icon: <Search className='h-3.5 w-3.5' />,
      text: `Context: ${payloadStr.slice(0, 120)}`,
      tone: 'neutral',
    };
  if (type === 'CLARIFICATION_NEEDED')
    return {
      icon: <MessageSquare className='h-3.5 w-3.5' />,
      text: 'Clarification needed from user',
      tone: 'amber',
    };
  if (type === 'CLARIFICATION_RESOLVED')
    return {
      icon: <CheckCircle2 className='h-3.5 w-3.5' />,
      text: 'Clarification resolved',
      tone: 'greenSoft',
    };
  if (type === 'MODEL_ESCALATED') {
    const parts = payloadStr.split('|');
    return {
      icon: <ArrowUpCircle className='h-3.5 w-3.5' />,
      text: `Model escalated: ${parts[0]} → ${parts[1] ?? ''}`,
      tone: 'amber',
    };
  }
  if (type === 'API_SPEC_READY')
    return {
      icon: <FileCode className='h-3.5 w-3.5' />,
      text: 'API spec generated',
      tone: 'blueSoft',
    };
  if (type === 'PREVIEW_ANALYZING')
    return {
      icon: <Search className='h-3.5 w-3.5' />,
      text: `Analyzing project: ${payloadStr.slice(0, 100)}`,
      tone: 'neutral',
    };
  if (type === 'PREVIEW_BUILDING')
    return {
      icon: <Rocket className='h-3.5 w-3.5' />,
      text: `Building preview: ${payloadStr.slice(0, 100)}`,
      tone: 'blueSoft',
    };
  if (type === 'PREVIEW_STARTING')
    return {
      icon: <Rocket className='h-3.5 w-3.5' />,
      text: `Starting preview: ${payloadStr.slice(0, 100)}`,
      tone: 'blue',
    };
  if (type === 'PREVIEW_READY')
    return {
      icon: <Globe className='h-3.5 w-3.5' />,
      text: `Preview ready${payloadStr ? `: ${payloadStr.slice(0, 80)}` : ''}`,
      tone: 'green',
    };
  if (type === 'PREVIEW_STOPPED')
    return {
      icon: <PauseCircle className='h-3.5 w-3.5' />,
      text: 'Preview stopped',
      tone: 'neutral',
    };
  if (type === 'PREVIEW_TIMED_OUT')
    return {
      icon: <Clock className='h-3.5 w-3.5' />,
      text: `Preview timed out${payloadStr ? `: ${payloadStr.slice(0, 80)}` : ''}`,
      tone: 'amber',
    };
  if (type === 'PREVIEW_FAILED')
    return {
      icon: <XCircle className='h-3.5 w-3.5' />,
      text: `Preview failed${payloadStr ? `: ${payloadStr.slice(0, 100)}` : ''}`,
      tone: 'red',
    };
  if (type === 'VALIDATION_STARTED')
    return {
      icon: <Shield className='h-3.5 w-3.5' />,
      text: `Validating ${payloadStr}`,
      tone: 'neutral',
    };
  if (type === 'VALIDATION_PASSED')
    return {
      icon: <CheckCircle2 className='h-3.5 w-3.5' />,
      text: 'Validation passed',
      tone: 'greenSoft',
    };
  if (type === 'VALIDATION_FAILED')
    return {
      icon: <XCircle className='h-3.5 w-3.5' />,
      text: `Validation failed: ${payloadStr.slice(0, 100)}`,
      tone: 'red',
    };

  // Batch (forward-compat for V2 batch path)
  if (type === 'BATCH_PROGRESS')
    return {
      icon: <Rocket className='h-3.5 w-3.5' />,
      text: `Batch: ${payloadStr}`,
      tone: 'blueSoft',
    };
  if (type === 'BATCH_COMPLETE')
    return {icon: <CheckCircle2 className='h-3.5 w-3.5' />, text: 'Batch complete', tone: 'green'};
  if (type === 'BATCH_FAILED')
    return {
      icon: <XCircle className='h-3.5 w-3.5' />,
      text: `Batch failed: ${payloadStr.slice(0, 100)}`,
      tone: 'red',
    };

  // 2026-05-07 — Phase L1: deterministic formatter pass. Payload is a one-line summary
  // (e.g. "prettier+spotless-maven · 14 file(s) changed in 3.2s"). Use the wrench icon
  // since it's the universal "the platform fixed something for you" affordance.
  if (type === 'LINT_AUTOFIX_APPLIED')
    return {
      icon: <Wrench className='h-3.5 w-3.5' />,
      text: `Auto-format · ${payloadStr}`,
      tone: 'blueSoft',
    };

  // 2026-05-07 — Phase P1: PR review comment received. Payload format
  // "{author}|{anchor}|{bodyPreview}" so the banner can show "Reviewer @octocat
  // commented on src/foo.ts:42 — 'consider using…'" without the UI needing to
  // refetch the inbox first.
  if (type === 'PR_COMMENT_RECEIVED') {
    const parts = payloadStr.split('|');
    const author = parts[0] ?? 'reviewer';
    const anchor = parts[1] ?? '';
    const preview = parts.slice(2).join('|') || '';
    return {
      icon: <MessageSquare className='h-3.5 w-3.5' />,
      text: `@${author} on ${anchor} — ${preview}`,
      tone: 'amber',
    };
  }

  return null;
}

function MilestoneBanner({entry, spec}: {entry: LogEntry; spec: MilestoneSpec}) {
  return (
    <div style={bannerStyle(spec.tone)}>
      {spec.icon}
      <span style={{flex: 1, minWidth: 0}}>{spec.text}</span>
      <span className='v3-mono' style={{fontSize: 10, opacity: 0.55, flexShrink: 0}}>
        {formatTimestamp(entry.timestamp)}
      </span>
    </div>
  );
}

function FileOpRow({entry, op}: {entry: LogEntry; op: FileOp}) {
  const config = {
    write: {icon: <FilePlus2 className='h-3 w-3' />, label: 'Created', tone: 'blue' as ToneKey},
    edit: {icon: <FileEdit className='h-3 w-3' />, label: 'Edited', tone: 'neutral' as ToneKey},
    commit: {
      icon: <GitCommit className='h-3 w-3' />,
      label: 'Committed to Git',
      tone: 'greenSoft' as ToneKey,
    },
    command: {
      icon: <Terminal className='h-3 w-3' />,
      label: 'Command run',
      tone: 'neutral' as ToneKey,
    },
  }[op.kind];
  const t = TONE[config.tone];
  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 8,
        padding: '2px 6px',
        fontSize: 11,
        color: t.fg,
      }}
    >
      <span className='v3-mono' style={{color: 'var(--v3-text-subtle)', flexShrink: 0}}>
        {formatTimestamp(entry.timestamp)}
      </span>
      <span
        style={{
          display: 'inline-flex',
          alignItems: 'center',
          gap: 4,
          flexShrink: 0,
          fontWeight: 600,
        }}
      >
        {config.icon}
        {config.label}
      </span>
      {op.path && (
        <span
          className='v3-mono'
          style={{
            color: 'var(--v3-text-muted)',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap',
          }}
        >
          {op.path}
        </span>
      )}
    </div>
  );
}

function isInternalNoise(msg: string): boolean {
  return (
    /TOOL_DISPATCHED/i.test(msg) ||
    /LLM_REQUEST_SENT/i.test(msg) ||
    /\bIteration\s+\d+\s*\(/i.test(msg) ||
    /STALL_TRUNCATION/i.test(msg) ||
    /COMPACTION_TRIGGERED/i.test(msg)
  );
}

/**
 * For LOG entries that aren't file ops or noise: if the message is
 * emoji-prefixed (✅⚠️❌🔧) or comes from a known agent taskId
 * (harness/planner/compiler/reviewer/preview-agent), treat it as a
 * milestone-grade banner. Returns null otherwise → caller drops the row.
 */
function deriveLogBanner(msg: string, taskId: string): MilestoneSpec | null {
  const isHarnessTag =
    /^[✅⚠️❌🔧]/.test(msg) ||
    taskId === 'harness' ||
    taskId === 'planner' ||
    taskId === 'compiler' ||
    taskId === 'reviewer' ||
    taskId === 'report' ||
    taskId === 'preview-agent' ||
    msg.startsWith('Merging') ||
    msg.startsWith('Simple request') ||
    msg.startsWith('Reviewer') ||
    msg.startsWith('Compile') ||
    msg.startsWith('Background') ||
    msg.startsWith('Code ready') ||
    msg.startsWith('Plan') ||
    /^\[[\w-]+\]/.test(msg);

  if (!isHarnessTag) return null;

  let icon: ReactNode = <Rocket className='h-3.5 w-3.5' />;
  let tone: ToneKey = 'neutral';
  if (/^✅/.test(msg) || /\bsuccessfully\b/i.test(msg) || /\bpassed\b/i.test(msg)) {
    icon = <CheckCircle2 className='h-3.5 w-3.5' />;
    tone = 'green';
  } else if (/^⚠️/.test(msg) || /\bWARNING\b/.test(msg)) {
    icon = <AlertTriangle className='h-3.5 w-3.5' />;
    tone = 'amber';
  } else if (/^❌/.test(msg) || /\bfailed\b/i.test(msg) || /\bError\b/.test(msg)) {
    icon = <XCircle className='h-3.5 w-3.5' />;
    tone = 'red';
  } else if (/^🔧/.test(msg) || (taskId === 'reviewer' && /fix/i.test(msg))) {
    icon = <Wrench className='h-3.5 w-3.5' />;
    tone = 'blueSoft';
  } else if (taskId === 'planner' || /^Plan/i.test(msg)) {
    icon = <Zap className='h-3.5 w-3.5' />;
    tone = 'blue';
  } else if (
    taskId === 'harness' ||
    /^Merging|^\[harness\]/i.test(msg) ||
    /^Background/i.test(msg)
  ) {
    icon = <GitCommit className='h-3.5 w-3.5' />;
    tone = 'blueSoft';
  } else if (taskId === 'compiler' || /^Compile|^Compiling/i.test(msg)) {
    icon = <Terminal className='h-3.5 w-3.5' />;
    tone = 'blue';
  } else if (taskId === 'reviewer' || /^Reviewer|^\[reviewer\]/i.test(msg)) {
    icon = <Eye className='h-3.5 w-3.5' />;
    tone = 'blueSoft';
  } else if (taskId === 'preview-agent') {
    icon = <Globe className='h-3.5 w-3.5' />;
    tone = 'blueSoft';
  } else if (/^Simple request/i.test(msg)) {
    icon = <Zap className='h-3.5 w-3.5' />;
    tone = 'blue';
  }

  const cleanMsg = msg.replace(/^[✅⚠️❌🔧]\s*/, '').replace(/^\[[\w-]+\]\s*/, '');
  return {icon, text: cleanMsg.length > 200 ? cleanMsg.slice(0, 200) + '…' : cleanMsg, tone};
}

function LogPane({runId, live}: {runId: string; live: boolean}) {
  // Hook the SSE stream into the global store while this pane is mounted.
  // The hook handles connect/reconnect/scoped-token; we just consume entries.
  useSse(live ? runId : null);
  const entries = useSseStore((s) => s.entries);
  const sseStatus = useSseStore((s) => s.status);

  // Newest-LAST so the natural reading order matches a terminal log;
  // auto-scroll keeps the latest event in view unless the user has
  // scrolled up to read older entries.
  const recent = useMemo(() => entries.slice(-300), [entries]);

  const containerRef = useRef<HTMLDivElement | null>(null);
  const bottomRef = useRef<HTMLDivElement | null>(null);
  const isPausedRef = useRef(false);
  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;
    const onScroll = () => {
      const atBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 40;
      isPausedRef.current = !atBottom;
    };
    el.addEventListener('scroll', onScroll);
    return () => el.removeEventListener('scroll', onScroll);
  }, []);
  useEffect(() => {
    if (isPausedRef.current) return;
    bottomRef.current?.scrollIntoView({behavior: 'smooth', block: 'end'});
  }, [recent.length]);

  return (
    <div style={{display: 'flex', flexDirection: 'column', height: '100%'}}>
      <div
        style={{
          padding: '6px 12px',
          borderBottom: '1px solid var(--v3-border)',
          fontSize: 11,
          fontFamily: 'var(--v3-font-mono)',
          color: 'var(--v3-text-subtle)',
          background: 'var(--v3-bg)',
          display: 'flex',
          justifyContent: 'space-between',
          flexShrink: 0,
        }}
      >
        <span>{live ? 'Streaming live · oldest first' : 'Final log · oldest first'}</span>
      </div>
      <div
        ref={containerRef}
        style={{
          flex: 1,
          overflow: 'auto',
          padding: 8,
          fontSize: 11,
          lineHeight: 1.55,
        }}
      >
        {recent.length === 0 && (
          <div style={{padding: 12, color: 'var(--v3-text-subtle)', fontSize: 12}}>
            {live ? 'Waiting for events…' : 'No log captured for this run.'}
          </div>
        )}
        {recent.map((entry) => {
          // 1. Milestone events (lifecycle / cost / deploy / preview / test
          //    / review / validation / batch) → banner.
          if (MILESTONE_TYPES.has(entry.type)) {
            const spec = deriveMilestone(entry);
            if (spec) return <MilestoneBanner key={entry.id} entry={entry} spec={spec} />;
          }

          // 2. LOG events: file ops + emoji-prefix banners; suppress noise.
          if (entry.type === 'LOG') {
            const data = entry.data as Record<string, unknown>;
            const msg = formatLogMessage(data);
            if (!msg) return null;
            const op = detectFileOp(msg);
            if (op) return <FileOpRow key={entry.id} entry={entry} op={op} />;
            if (isInternalNoise(msg)) return null;
            const taskId = String(data.taskId ?? '');
            const banner = deriveLogBanner(msg, taskId);
            if (banner) return <MilestoneBanner key={entry.id} entry={entry} spec={banner} />;
            // Verbose internal log line — drop. (The user can still see
            // raw events via the backend log if needed.)
            return null;
          }

          // 3. Fallback for unknown types — muted small text so a new
          //    backend event can't go invisible.
          const fallback = String(
            (entry.data as Record<string, unknown>)?.payload ??
              (entry.data as Record<string, unknown>)?.message ??
              '',
          );
          if (!fallback) return null;
          return (
            <div
              key={entry.id}
              style={{
                display: 'flex',
                gap: 8,
                padding: '2px 4px',
                fontSize: 10.5,
                color: 'var(--v3-text-subtle)',
                opacity: 0.7,
              }}
            >
              <span className='v3-mono' style={{flexShrink: 0}}>
                {formatTimestamp(entry.timestamp)}
              </span>
              <span style={{flexShrink: 0}}>[{entry.type}]</span>
              <span
                style={{
                  flex: 1,
                  minWidth: 0,
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap',
                }}
              >
                {fallback.slice(0, 160)}
              </span>
            </div>
          );
        })}
        <div ref={bottomRef} />
      </div>
      {/* Status bar — pulsing dot when streaming, plain text otherwise. */}
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          padding: '6px 12px',
          borderTop: '1px solid var(--v3-border)',
          background: 'var(--v3-bg)',
          fontSize: 11,
          flexShrink: 0,
        }}
      >
        {sseStatus === 'CONNECTED' ? (
          <>
            <span
              style={{
                position: 'relative',
                width: 6,
                height: 6,
                display: 'inline-flex',
              }}
            >
              <span
                style={{
                  position: 'absolute',
                  inset: 0,
                  borderRadius: 999,
                  background: 'var(--v3-success)',
                  opacity: 0.65,
                  animation: 'v3PulseDot 1.4s ease-in-out infinite',
                }}
              />
              <span
                style={{
                  position: 'relative',
                  width: 6,
                  height: 6,
                  borderRadius: 999,
                  background: 'var(--v3-success)',
                }}
              />
            </span>
            <span style={{color: 'var(--v3-text-muted)'}}>Streaming</span>
          </>
        ) : sseStatus === 'RECONNECTING' || sseStatus === 'CONNECTING' ? (
          <span style={{color: 'var(--v3-warning)'}}>Reconnecting…</span>
        ) : (
          <span style={{color: 'var(--v3-text-muted)'}}>
            Disconnected · {entries.length} events
          </span>
        )}
      </div>
    </div>
  );
}

// ── Overview pane ───────────────────────────────────────────────────────
//
// Compact key-value rollup of the run's high-level signals: provider,
// model, cost, tokens, GitHub PR link, batch badge. Polls /runs/{id}/cost
// every 5s while in flight (mirrors v2 RunDetailTabs.OverviewTab) so cost
// shown matches the live spend, not the mount-time DB snapshot.

function OverviewPane({
  runId,
  detail,
  live,
}: {
  runId: string;
  detail: RunDetail | null;
  live: boolean;
}) {
  const [liveCost, setLiveCost] = useState<number | null>(null);
  const [liveTokens, setLiveTokens] = useState<{input: number; output: number} | null>(null);

  useEffect(() => {
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | null = null;

    const poll = async () => {
      try {
        const res = await getLiveCost(runId);
        if (cancelled) return;
        if (typeof res.costUsd === 'number') setLiveCost(res.costUsd);
        if (typeof res.inputTokens === 'number' && typeof res.outputTokens === 'number') {
          setLiveTokens({input: res.inputTokens, output: res.outputTokens});
        }
      } catch {
        // 404s pre-plan-approval are expected; swallow + retry.
      }
      if (!cancelled && live) {
        timer = setTimeout(poll, 5000);
      }
    };
    poll();
    return () => {
      cancelled = true;
      if (timer) clearTimeout(timer);
    };
  }, [runId, live]);

  if (!detail) {
    return <div style={paneEmptyStyle}>Loading overview…</div>;
  }

  // Run-total tokens — computed from agent + per-task usage since RunDetail
  // doesn't carry an aggregate field. Live cost endpoint returns the same
  // sum (planner + reviewer + worker tasks + classifier/outline/expansion/
  // coherence phases) so liveTokens, when present, wins.
  const taskInputSum = (detail.tasks ?? []).reduce((s, t) => s + (t.inputTokens || 0), 0);
  const taskOutputSum = (detail.tasks ?? []).reduce((s, t) => s + (t.outputTokens || 0), 0);
  const agentInputSum = (detail.agentTokens ?? []).reduce((s, a) => s + (a.inputTokens || 0), 0);
  const agentOutputSum = (detail.agentTokens ?? []).reduce((s, a) => s + (a.outputTokens || 0), 0);
  const dbInputTokens = taskInputSum + agentInputSum;
  const dbOutputTokens = taskOutputSum + agentOutputSum;
  const inputTokens = Math.max(liveTokens?.input ?? 0, dbInputTokens);
  const outputTokens = Math.max(liveTokens?.output ?? 0, dbOutputTokens);
  const totalTokens = inputTokens + outputTokens;
  const cost = liveCost ?? detail.actualCostUsd ?? null;
  const estLow = detail.costEstimateLow ?? null;
  const estHigh = detail.costEstimateHigh ?? null;
  const cap = detail.hardCapUsd ?? null;

  // Provider on RunDetail is `aiProvider`. Per-task `model` is the per-call
  // model (planner / worker tier resolves at dispatch); RunDetail itself
  // doesn't carry a single model — pull the most-recent task's model as a
  // best-effort summary, fall back to '—'.
  const provider = detail.aiProvider ?? null;
  const lastTaskModel =
    (detail.tasks ?? [])
      .map((t) => t.model)
      .filter((m): m is string => !!m)
      .pop() ?? null;
  const isBatch = detail.approvedExecutionMode === 'BATCH';
  const prUrl = detail.githubPrUrl ?? detail.scmPrUrl ?? null;

  return (
    <div style={{padding: 12, display: 'flex', flexDirection: 'column', gap: 12}}>
      {/* Title (run spec). 2026-05-08 — was hard-truncated to 280 chars,
          which on bench-mode runs (where we prepend ~5KB of failing-test
          output as ground truth) hid 95%+ of the prompt. Now: full content
          with a max-height + overflow scroll, plus a Show more / Show less
          toggle for prompts > ~600 chars so the side panel stays compact
          by default but the operator can read the whole thing without
          leaving the page. */}
      {detail.userRequest && <RunSpecBlock content={detail.userRequest} />}

      <KvSection label='Run'>
        <Kv k='Status' v={detail.status ?? '—'} />
        <Kv
          k='Provider'
          v={provider ? (PROVIDER_LABEL[provider.toUpperCase()] ?? provider) : '—'}
        />
        <Kv k='Model' v={lastTaskModel || '—'} mono />
        {isBatch && <Kv k='Mode' v='Batch (queued)' />}
        {detail.startedAt && <Kv k='Started' v={fmtAbsolute(detail.startedAt)} mono />}
        {detail.completedAt && <Kv k='Completed' v={fmtAbsolute(detail.completedAt)} mono />}
        {detail.startedAt && (
          <Kv
            k='Duration'
            v={fmtDuration(detail.startedAt, detail.completedAt ?? undefined)}
            mono
          />
        )}
      </KvSection>

      <KvSection label='Cost'>
        <Kv k='Spent' v={cost != null ? `$${cost.toFixed(4)}` : '—'} mono />
        {(estLow != null || estHigh != null) && (
          <Kv
            k='Estimate'
            v={
              estLow != null && estHigh != null && estLow !== estHigh
                ? `$${estLow.toFixed(2)}–$${estHigh.toFixed(2)}`
                : `$${(estHigh ?? estLow ?? 0).toFixed(2)}`
            }
            mono
          />
        )}
        {cap != null && <Kv k='Hard cap' v={`$${cap.toFixed(2)}`} mono />}
      </KvSection>

      <KvSection label='Tokens'>
        <Kv k='Total' v={fmtNum(totalTokens)} mono />
        <Kv k='Input' v={fmtNum(inputTokens)} mono />
        <Kv k='Output' v={fmtNum(outputTokens)} mono />
      </KvSection>

      {prUrl && (
        <a
          href={prUrl}
          target='_blank'
          rel='noopener noreferrer'
          style={{
            fontSize: 12,
            fontWeight: 600,
            color: 'var(--accent-blue-text, var(--v3-brand))',
            textDecoration: 'none',
            padding: '8px 10px',
            border: '1px solid var(--v3-border)',
            borderRadius: 6,
            background: 'var(--v3-bg)',
            display: 'inline-flex',
            alignItems: 'center',
            gap: 8,
          }}
        >
          Open pull request ↗
        </a>
      )}
    </div>
  );
}

const PROVIDER_LABEL: Record<string, string> = {
  ANTHROPIC: 'Anthropic',
  OPENAI: 'OpenAI',
  GEMINI: 'Gemini',
  GOOGLE: 'Gemini',
};

function KvSection({label, children}: {label: string; children: ReactNode}) {
  return (
    <div>
      <div
        style={{
          fontSize: 11,
          fontWeight: 600,
          color: 'var(--v3-text-subtle)',
          letterSpacing: 0.5,
          textTransform: 'uppercase',
          padding: '0 2px 6px',
        }}
      >
        {label}
      </div>
      <div
        style={{
          background: 'var(--v3-bg)',
          border: '1px solid var(--v3-border)',
          borderRadius: 6,
          padding: '6px 10px',
          display: 'flex',
          flexDirection: 'column',
          gap: 4,
        }}
      >
        {children}
      </div>
    </div>
  );
}

function Kv({k, v, mono}: {k: string; v: string; mono?: boolean}) {
  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'baseline',
        justifyContent: 'space-between',
        gap: 12,
        fontSize: 12,
      }}
    >
      <span style={{color: 'var(--v3-text-muted)'}}>{k}</span>
      <span
        style={{
          color: 'var(--v3-text)',
          fontFamily: mono ? 'var(--v3-font-mono)' : 'inherit',
          fontSize: mono ? 11.5 : 12,
          textAlign: 'right',
          maxWidth: '60%',
          overflow: 'hidden',
          textOverflow: 'ellipsis',
          whiteSpace: 'nowrap',
        }}
        title={v}
      >
        {v}
      </span>
    </div>
  );
}

function fmtNum(n: number): string {
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(2) + 'M';
  if (n >= 1_000) return (n / 1_000).toFixed(1) + 'K';
  return String(n);
}

function fmtAbsolute(iso: string): string {
  try {
    const d = new Date(iso);
    return d.toLocaleString(undefined, {
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    });
  } catch {
    return iso;
  }
}

function fmtDuration(startIso: string, endIso?: string): string {
  try {
    const start = new Date(startIso).getTime();
    const end = endIso ? new Date(endIso).getTime() : Date.now();
    const ms = Math.max(0, end - start);
    const sec = Math.round(ms / 1000);
    if (sec < 60) return `${sec}s`;
    const min = Math.floor(sec / 60);
    const s = sec % 60;
    if (min < 60) return s ? `${min}m ${s}s` : `${min}m`;
    const hr = Math.floor(min / 60);
    const m = min % 60;
    return m ? `${hr}h ${m}m` : `${hr}h`;
  } catch {
    return '—';
  }
}

const paneEmptyStyle: React.CSSProperties = {
  padding: 16,
  fontSize: 12,
  color: 'var(--v3-text-subtle)',
};

// ── Timeline pane ───────────────────────────────────────────────────────
//
// Reuses the v2 TaskTimeline swimlane. Renders a Gantt-ish bar per task
// (start offset + duration), status-coloured. Hover shows duration +
// model + tokens. Preserves the chronological view that the v2 chrome
// shipped on /runs/{id}#timeline.

function TimelinePane({detail}: {detail: RunDetail | null}) {
  if (!detail) {
    return <div style={paneEmptyStyle}>Loading timeline…</div>;
  }
  if (!detail.tasks || detail.tasks.length === 0) {
    return (
      <div style={paneEmptyStyle}>
        No tasks yet — the timeline appears once the planner emits tasks.
      </div>
    );
  }
  return (
    <div style={{padding: 12}}>
      <TaskTimeline tasks={detail.tasks} runStartedAt={detail.startedAt ?? null} />
    </div>
  );
}

// ── Tests pane ──────────────────────────────────────────────────────────
//
// TestProgressTracker (live progress while test gen is in flight) +
// TestReportPanel (results when COMPLETE). Both components handle their
// own quiet states for runs that haven't kicked off test gen yet, so we
// render them unconditionally.

function TestsPane({runId, status}: {runId: string; status: string}) {
  // 2026-05-07 — fetch the test plan so we can show "tests were generated but
  // execution blocked" when the preview engine couldn't run (Docker daemon
  // down, registry offline, etc.). Pre-fix `TestProgressTracker` rendered
  // null without live SSE, `TestReportPanel` rendered null when totalTests=0,
  // and the static fallback ("if no report is shown, this run did not
  // trigger the test pipeline") misled users into thinking nothing happened.
  const [plan, setPlan] = useState<TestPlanData | null>(null);
  const [planLoading, setPlanLoading] = useState(false);
  const [previewStatus, setPreviewStatus] = useState<LocalPreviewStatus | null>(null);
  const [runningTests, setRunningTests] = useState(false);
  const [runError, setRunError] = useState<string | null>(null);

  const isTerminal = ['COMPLETE', 'PARTIAL', 'FAILED'].includes(status);

  const refetchPlan = useCallback(() => {
    setPlanLoading(true);
    getTestPlan(runId)
      .then(setPlan)
      .catch(() => setPlan(null))
      .finally(() => setPlanLoading(false));
  }, [runId]);

  useEffect(() => {
    if (!isTerminal) return;
    refetchPlan();
    getPreviewStatus(runId)
      .then(setPreviewStatus)
      .catch(() => setPreviewStatus(null));
  }, [isTerminal, runId, refetchPlan]);

  // Re-fetch when test pipeline events arrive — keeps the pane in lockstep
  // with TestPreviewCoordinator without a manual refresh.
  const sseEntries = useSseStore((s) => s.entries);
  const lastEntryId = sseEntries[sseEntries.length - 1]?.id ?? null;
  const lastEntryType = sseEntries[sseEntries.length - 1]?.type ?? null;
  const seenTestEntryRef = useRef<string | null>(null);
  useEffect(() => {
    if (!lastEntryId || lastEntryId === seenTestEntryRef.current) return;
    seenTestEntryRef.current = lastEntryId;
    if (
      typeof lastEntryType === 'string' &&
      (lastEntryType.startsWith('TEST_') || lastEntryType.startsWith('PREVIEW_'))
    ) {
      refetchPlan();
      getPreviewStatus(runId)
        .then(setPreviewStatus)
        .catch(() => setPreviewStatus(null));
    }
  }, [lastEntryId, lastEntryType, refetchPlan, runId]);

  const compiledTasks = (plan?.tasks ?? []).filter((t) => t.status === 'COMPILED');
  const failedTasks = (plan?.tasks ?? []).filter((t) => t.status === 'FAILED');
  const previewRunning = previewStatus?.status === 'RUNNING';
  const previewBlocked =
    !!previewStatus && ['FAILED', 'TIMED_OUT', 'IDLE', 'STOPPED'].includes(previewStatus.status);
  const previewError =
    previewStatus?.errorMessage && previewStatus.errorMessage.trim().length > 0
      ? previewStatus.errorMessage
      : null;

  async function onRunTests() {
    if (runningTests) return;
    setRunningTests(true);
    setRunError(null);
    try {
      await runTests(runId);
      refetchPlan();
    } catch (e) {
      setRunError((e as Error)?.message ?? 'Failed to start test execution');
    } finally {
      setRunningTests(false);
    }
  }

  return (
    <div style={{padding: 12, display: 'flex', flexDirection: 'column', gap: 12}}>
      <div
        style={{
          fontSize: 11,
          fontWeight: 600,
          color: 'var(--v3-text-subtle)',
          letterSpacing: 0.5,
          textTransform: 'uppercase',
          padding: '0 2px',
        }}
      >
        Integration tests
      </div>
      <TestProgressTracker runId={runId} status={status} />
      <TestReportPanel runId={runId} status={status} />

      {/* 2026-05-07 — Generated-but-not-executed surface. Shows once we know
          test gen produced compiled tasks but the report hasn't arrived
          (TestReportPanel self-hides on totalTests=0). Lets users see what
          got built + retry execution when the preview comes back. */}
      {isTerminal && plan && compiledTasks.length > 0 && (
        <GeneratedTestsCard
          compiledTasks={compiledTasks}
          failedTasks={failedTasks}
          previewRunning={previewRunning}
          previewBlocked={previewBlocked}
          previewError={previewError}
          runningTests={runningTests}
          runError={runError}
          onRunTests={onRunTests}
        />
      )}

      {/* Quiet states — only render when the panel above isn't telling the
          user something more useful. */}
      {isTerminal && !planLoading && !plan && (
        <div
          style={{
            fontSize: 12,
            color: 'var(--v3-text-muted)',
            lineHeight: 1.55,
            padding: '8px 10px',
            background: 'var(--v3-bg)',
            border: '1px solid var(--v3-border)',
            borderRadius: 6,
          }}
        >
          Test generation runs after the build completes — this run did not trigger the test
          pipeline.
        </div>
      )}
      {!isTerminal && (
        <div
          style={{
            fontSize: 12,
            color: 'var(--v3-text-muted)',
            lineHeight: 1.55,
            padding: '8px 10px',
            background: 'var(--v3-bg)',
            border: '1px solid var(--v3-border)',
            borderRadius: 6,
          }}
        >
          Tests will appear here once the run reaches a terminal state.
        </div>
      )}
    </div>
  );
}

function GeneratedTestsCard({
  compiledTasks,
  failedTasks,
  previewRunning,
  previewBlocked,
  previewError,
  runningTests,
  runError,
  onRunTests,
}: {
  compiledTasks: NonNullable<TestPlanData['tasks']>;
  failedTasks: NonNullable<TestPlanData['tasks']>;
  previewRunning: boolean;
  previewBlocked: boolean;
  previewError: string | null;
  runningTests: boolean;
  runError: string | null;
  onRunTests: () => void;
}) {
  return (
    <div
      style={{
        padding: 12,
        background: 'var(--v3-bg)',
        border: '1px solid var(--v3-border)',
        borderRadius: 6,
        display: 'flex',
        flexDirection: 'column',
        gap: 10,
      }}
    >
      <div
        style={{
          display: 'flex',
          alignItems: 'baseline',
          gap: 8,
          flexWrap: 'wrap',
        }}
      >
        <div style={{fontSize: 13, fontWeight: 600, color: 'var(--v3-text)'}}>
          {compiledTasks.length} test class{compiledTasks.length === 1 ? '' : 'es'} generated
        </div>
        {failedTasks.length > 0 && (
          <span style={{fontSize: 11, color: 'var(--v3-warning)'}}>
            ({failedTasks.length} failed to compile)
          </span>
        )}
        <span
          className='v3-mono'
          style={{
            marginLeft: 'auto',
            fontSize: 10.5,
            color: 'var(--v3-text-subtle)',
            textTransform: 'uppercase',
            letterSpacing: 0.4,
          }}
        >
          {previewRunning
            ? 'Preview ready'
            : previewBlocked
              ? 'Preview blocked'
              : 'Preview unknown'}
        </span>
      </div>

      <ul
        style={{
          margin: 0,
          padding: 0,
          listStyle: 'none',
          display: 'flex',
          flexDirection: 'column',
          gap: 4,
          fontSize: 11.5,
        }}
      >
        {compiledTasks.slice(0, 8).map((t) => (
          <li key={t.id} className='v3-mono' style={{color: 'var(--v3-text-muted)'}}>
            ✓ {t.taskName}
          </li>
        ))}
        {compiledTasks.length > 8 && (
          <li style={{color: 'var(--v3-text-subtle)', fontSize: 10.5}}>
            … + {compiledTasks.length - 8} more
          </li>
        )}
      </ul>

      {previewBlocked && (
        <div
          style={{
            fontSize: 11.5,
            lineHeight: 1.55,
            padding: '8px 10px',
            background: 'color-mix(in srgb, var(--v3-warning) 8%, transparent)',
            border: '1px solid color-mix(in srgb, var(--v3-warning) 30%, transparent)',
            borderRadius: 4,
            color: 'color-mix(in srgb, var(--v3-warning) 80%, var(--v3-text))',
          }}
        >
          Tests compiled but execution didn't run — the preview engine wasn't able to start the app.{' '}
          {previewError ? (
            <>
              Last error: <em>{previewError}</em>.
            </>
          ) : null}{' '}
          Start the preview from the Preview tab and click "Run tests" below to retry.
        </div>
      )}

      <div style={{display: 'flex', gap: 6, alignItems: 'center', flexWrap: 'wrap'}}>
        <button
          type='button'
          onClick={onRunTests}
          disabled={runningTests}
          style={{
            padding: '5px 12px',
            fontSize: 11.5,
            fontWeight: 600,
            background: 'var(--v3-brand)',
            color: 'var(--v3-text-on-brand)',
            border: 'none',
            borderRadius: 4,
            cursor: runningTests ? 'not-allowed' : 'pointer',
            opacity: runningTests ? 0.6 : 1,
            fontFamily: 'inherit',
          }}
        >
          {runningTests ? 'Triggering…' : 'Run tests'}
        </button>
        {runError && <span style={{fontSize: 11, color: 'var(--v3-error)'}}>{runError}</span>}
      </div>
    </div>
  );
}

// ── Chat pane ──────────────────────────────────────────────────────────────
//
// 2026-05-07 — run-scoped chat. Wraps POST /api/conversation (the same
// endpoint the pre-run "Refine with assistant" modal uses; no tier gate)
// with the run's spec / status / verdict pre-seeded as conversation
// history so the assistant can answer questions like "what changed?",
// "why did this fail?", "summarise the diff" without the user re-pasting
// context.
//
// We deliberately don't use the legacy `/runs/{id}/chat` endpoint — V2
// returns 410 Gone there per docs/plans/harness-v2-remaining-work.md.
// `/api/conversation` is stateless from the backend's perspective; we
// rebuild the seed context from `detail` on every send so a stale tab
// can't drift away from the truth.
//
// 2026-05-09 — module-scoped cache so ChatPane re-mounts (every Tab
// switch on the side panel unmounts the inactive pane) restore the
// last conversation instantly instead of starting empty. The backend
// is stateless, so without this the user's chat history vanishes the
// moment they peek at the Code tab + come back. Cache is per-runId
// because different runs have different contexts.

const v3RunChatCache = new Map<string, ConversationMessage[]>();

/**
 * 2026-05-09 — shared style for the v3 chat action buttons (Apply / Fix / Iterate).
 * Keeps the row visually coherent with the v3 design tokens: rounded pill, mono
 * label sized like a chip, tone-coded background. The {@code busy} flag drops
 * opacity + flips cursor so the user gets feedback even before the toast lands.
 */
function chatActionButtonStyle(
  tone: 'success' | 'warning' | 'brand',
  busy: boolean,
): CSSProperties {
  const accent =
    tone === 'success'
      ? 'var(--v3-success, #10B981)'
      : tone === 'warning'
        ? 'var(--v3-warning, #F59E0B)'
        : 'var(--v3-brand, #5B8DEF)';
  return {
    display: 'inline-flex',
    alignItems: 'center',
    gap: 6,
    padding: '6px 11px',
    background: `color-mix(in srgb, ${accent} 14%, transparent)`,
    color: accent,
    border: `1px solid color-mix(in srgb, ${accent} 36%, transparent)`,
    borderRadius: 999,
    fontSize: 11.5,
    fontFamily: 'inherit',
    fontWeight: 600,
    cursor: busy ? 'progress' : 'pointer',
    opacity: busy ? 0.7 : 1,
    whiteSpace: 'nowrap',
  };
}

function ChatPane({
  detail,
  prComments,
  onPrCommentChanged,
}: {
  detail: RunDetail | null;
  prComments: PrCommentInboxDto[];
  onPrCommentChanged: () => void;
}) {
  const navigate = useNavigate();
  // Seed from the cache so a tab-switch round-trip re-renders the prior
  // thread immediately (see v3RunChatCache comment). The cache is keyed
  // by runId, so opening a different run still gets a clean slate.
  const cacheKey = detail?.runId ?? '__no_run__';
  const [messages, setMessages] = useState<ConversationMessage[]>(
    () => v3RunChatCache.get(cacheKey) ?? [],
  );
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);

  // Keep the cache in lockstep so the next mount sees fresh data — incl.
  // the trailing assistant turn, action toasts (which don't change
  // messages but might be triggered alongside), and any iteration the
  // user has already kicked off.
  useEffect(() => {
    if (messages.length > 0) v3RunChatCache.set(cacheKey, messages);
  }, [cacheKey, messages]);

  // If the user navigates between runs without leaving the panel, sync
  // the visible thread to the new run's cache (or empty if first visit).
  useEffect(() => {
    setMessages(v3RunChatCache.get(cacheKey) ?? []);
  }, [cacheKey]);
  const [error, setError] = useState<string | null>(null);
  const [actionToast, setActionToast] = useState<string | null>(null);
  const threadRef = useRef<HTMLDivElement | null>(null);
  // Phase P2 — track per-comment apply state so the user gets feedback while
  // the new run kicks off + the GitHub branch updates.
  const [applyingId, setApplyingId] = useState<number | null>(null);
  // 2026-05-09 — re-add the v2 chat-action affordances (Apply / Fix / Iterate)
  // that the v3 ChatPane shipped without. Each action is a single button with
  // its own busy state so the user can fire one without locking the others.
  const [applyingChat, setApplyingChat] = useState(false);
  const [fixingIssues, setFixingIssues] = useState(false);
  const [iteratingRun, setIteratingRun] = useState(false);
  // ValidationReport drives the "Fix N issues" button copy + payload. Fetched
  // lazily on first render of a terminal run so the cost is amortised across
  // chat use.
  const [report, setReport] = useState<ValidationReportData | null>(null);
  const TERMINAL = useMemo(
    () => new Set(['COMPLETE', 'PARTIAL', 'FAILED', 'CANCELLED', 'ABORTED']),
    [],
  );
  const isTerminal = !!detail && TERMINAL.has(detail.status);
  useEffect(() => {
    if (!detail || !isTerminal) return;
    let cancelled = false;
    void getValidationReport(detail.runId)
      .then((r) => {
        if (!cancelled) setReport(r);
      })
      .catch(() => {
        // Validation report can legitimately be null on FAILED/CANCELLED runs;
        // swallow so the chat still works without the Fix N Issues button.
      });
    return () => {
      cancelled = true;
    };
  }, [detail, isTerminal]);

  const pendingComments = prComments.filter((c) => c.status === 'PENDING');
  const recentlyAddressed = prComments
    .filter((c) => c.status === 'ADDRESSED' || c.status === 'DISMISSED')
    .slice(0, 3);

  // 2026-05-09 — scroll-pin: only follow new messages if the user is
  // already at the bottom; if they scrolled up to read history, don't
  // yank them back on every reply or thinking-indicator tick.
  const isPinnedRef = useRef(true);
  const handleThreadScroll = (e: React.UIEvent<HTMLDivElement>) => {
    const el = e.currentTarget;
    const distance = el.scrollHeight - el.scrollTop - el.clientHeight;
    isPinnedRef.current = distance < 80;
  };
  useEffect(() => {
    if (isPinnedRef.current) {
      threadRef.current?.scrollTo({top: threadRef.current.scrollHeight, behavior: 'smooth'});
    }
  }, [messages, busy]);

  // Build a single-shot context preface every time we send so the
  // assistant always sees the latest run state. Cheap (a few hundred
  // chars at most) and keeps the conversation honest if the run flips
  // mid-chat (e.g. WORKING → COMPLETE).
  const seedContext = useMemo(() => {
    if (!detail) return '';
    const lines = [
      `I just submitted this request to ExecuteSpec: "${detail.userRequest ?? '(no spec)'}"`,
      `Status: ${detail.status ?? 'UNKNOWN'}.`,
    ];
    if (detail.aiProvider) lines.push(`AI provider: ${detail.aiProvider}.`);
    const tasks = detail.tasks ?? [];
    if (tasks.length > 0) {
      const done = tasks.filter((t) => t.status === 'COMPLETE').length;
      lines.push(`Tasks: ${done}/${tasks.length} complete.`);
    }
    if (detail.actualCostUsd != null) {
      lines.push(`Cost so far: $${detail.actualCostUsd.toFixed(4)}.`);
    }
    if (detail.githubPrUrl) {
      lines.push(`Draft PR: ${detail.githubPrUrl}`);
    }
    lines.push(
      'Help me understand what happened in this run, answer follow-up questions, and suggest next steps. Skip the requirements-clarification mode — I already submitted; treat this as a post-run debrief.',
    );
    return lines.join(' ');
  }, [detail]);

  async function send(userText: string) {
    setBusy(true);
    setError(null);
    try {
      const isFirst = messages.length === 0;
      // On the first turn we synthesise a seeded thread so the
      // assistant has run context before the user's first question.
      // Subsequent turns just append to the running thread.
      const thread: ConversationMessage[] = isFirst
        ? [
            {role: 'user', content: seedContext},
            {
              role: 'assistant',
              content:
                'Got it — I have the run context. What would you like to know about this run?',
            },
            {role: 'user', content: userText},
          ]
        : [...messages, {role: 'user', content: userText}];
      const res = await conversationChat(thread);
      // For the first turn we drop the seed pair from the rendered
      // thread (keep them in conversationChat's internal context only)
      // so the user sees a clean "Q → A" without the synthesised
      // preface. The seedContext effect rebuilds it fresh on every
      // call, so this is safe.
      const visible: ConversationMessage[] = isFirst
        ? [
            {role: 'user', content: userText},
            {role: 'assistant', content: res.reply},
          ]
        : [...thread, {role: 'assistant', content: res.reply}];
      setMessages(visible);
    } catch (e) {
      setError((e as Error)?.message ?? 'Chat failed');
    } finally {
      setBusy(false);
    }
  }

  async function onSubmit(e?: React.FormEvent) {
    e?.preventDefault();
    const text = input.trim();
    if (!text || busy) return;
    setInput('');
    await send(text);
  }

  // 2026-05-07 — Phase P1: dismiss a PR review comment without taking action.
  async function onDismiss(commentId: number) {
    if (!detail?.runId) return;
    try {
      await dismissPrComment(detail.runId, commentId);
      onPrCommentChanged();
    } catch (e) {
      setError((e as Error)?.message ?? 'Dismiss failed');
    }
  }

  // 2026-05-07 — Phase P2: address a PR review comment by dispatching an
  // ApplyService run. The new run pushes its diff to the PR's branch (same
  // branch the original PR is from), so the GitHub PR auto-updates.
  async function onApply(commentId: number) {
    if (!detail?.runId || applyingId !== null) return;
    setApplyingId(commentId);
    setError(null);
    try {
      await applyPrComment(detail.runId, commentId);
      onPrCommentChanged();
    } catch (e) {
      setError((e as Error)?.message ?? 'Apply failed');
    } finally {
      setApplyingId(null);
    }
  }

  // 2026-05-07 — Phase P2: quote a PR review comment into the chat thread so
  // the user can ask the assistant about it before deciding to apply.
  function onQuote(c: PrCommentInboxDto) {
    const anchor = c.filePath
      ? `${c.filePath}${c.lineNumber ? `:${c.lineNumber}` : ''}`
      : 'PR-level';
    const quoted = `> @${c.author} on ${anchor}:\n> ${c.body.replace(/\n/g, '\n> ')}\n\n`;
    setInput((prev) => (prev.trim() ? `${prev}\n\n${quoted}` : quoted));
  }

  // 2026-05-09 — Apply / Fix / Iterate handlers. Restored from the v2 RunConversation
  // surface. Each one targets a different post-run improvement intent:
  //
  //   Apply to Run    — user clicks after the assistant proposed a concrete change in chat;
  //                     we forward the latest assistant turn as the apply prompt. Spawns
  //                     an apply-run that pushes its diff to the PR's branch.
  //   Fix N Issues    — auto-builds a prompt from validation_report.issues (CRITICAL +
  //                     MAJOR severity, capped) and dispatches a single apply for them.
  //   Iterate as New  — takes the latest assistant turn (or all messages if none) as
  //                     the spec for a brand-new run. Useful when chat goes off-spec.
  //
  // Compile as Spec is intentionally omitted from this restoration — it relied on a v2
  // popover flow that's incompatible with the v3 side-panel shape; tracked as a follow-up
  // if user requests it.
  const lastAssistantText = useMemo(() => {
    for (let i = messages.length - 1; i >= 0; i--) {
      if (messages[i].role === 'assistant') return messages[i].content;
    }
    return null;
  }, [messages]);
  // Mirrors v2 RunConversation.unresolvedIssues — every reviewer-flagged finding
  // EXCEPT lint-only issues (those auto-resolve via the executor's lint pass).
  const unresolvedIssues = useMemo<ReportIssue[]>(() => {
    if (!report?.issues) return [];
    return report.issues.filter(
      (it) =>
        it.severity === 'WOULD_BREAK_BUILD' ||
        it.severity === 'WOULD_FAIL_REVIEW' ||
        it.severity === 'WOULD_CAUSE_BUG',
    );
  }, [report]);

  async function onApplyToRun() {
    if (!detail?.runId || applyingChat || !lastAssistantText) return;
    setApplyingChat(true);
    setError(null);
    setActionToast(null);
    try {
      const result = await applyToRun(detail.runId, lastAssistantText, 'COPILOT');
      setActionToast(
        `Apply queued — ${result.taskCount ?? 1} change${(result.taskCount ?? 1) === 1 ? '' : 's'} dispatched. Watch the live log.`,
      );
    } catch (e) {
      setError((e as Error)?.message ?? 'Apply failed.');
    } finally {
      setApplyingChat(false);
    }
  }

  async function onFixIssues() {
    if (!detail?.runId || fixingIssues || unresolvedIssues.length === 0) return;
    setFixingIssues(true);
    setError(null);
    setActionToast(null);
    const prompt = unresolvedIssues
      .slice(0, 8)
      .map(
        (it, i) =>
          `${i + 1}. [${it.severity}] ${it.filePath ?? '(no file)'}${it.fix ? ` — suggested fix: ${it.fix}` : ''}: ${it.description}`,
      )
      .join('\n');
    try {
      const result = await applyToRun(
        detail.runId,
        `Fix the following review issues from this run:\n\n${prompt}`,
        'REVIEW',
      );
      setActionToast(
        `Fixing ${unresolvedIssues.length} issue${unresolvedIssues.length === 1 ? '' : 's'} — ${result.taskCount ?? 1} task${(result.taskCount ?? 1) === 1 ? '' : 's'} dispatched.`,
      );
    } catch (e) {
      setError((e as Error)?.message ?? 'Could not start the fix-issues run.');
    } finally {
      setFixingIssues(false);
    }
  }

  async function onIterateAsNewRun() {
    if (!detail?.runId || iteratingRun) return;
    // Pick the most useful spec text: latest assistant turn if available, else the
    // joined chat history, else the original user request as a fallback.
    const seedSpec =
      lastAssistantText ??
      messages.map((m) => `${m.role === 'user' ? '' : 'Assistant: '}${m.content}`).join('\n\n') ??
      detail.userRequest ??
      '';
    if (!seedSpec.trim()) {
      setError('Nothing to iterate from yet — start the conversation first.');
      return;
    }
    setIteratingRun(true);
    setError(null);
    setActionToast(null);
    try {
      const {runId: newRunId} = await iterateRun(detail.runId, seedSpec);
      setActionToast('New run created — opening it…');
      // Tiny delay so the user sees the toast flash before navigation.
      window.setTimeout(() => {
        navigate(
          detail.projectId ? `/projects/${detail.projectId}/runs/${newRunId}` : `/runs/${newRunId}`,
        );
      }, 600);
    } catch (e) {
      setError((e as Error)?.message ?? 'Could not start the new run.');
    } finally {
      setIteratingRun(false);
    }
  }

  // Quick-question chips help users start a conversation without
  // staring at an empty textbox. Each chip is just a pre-filled
  // first-turn user message; the seed context kicks in on send.
  const QUICK_QUESTIONS = [
    'Summarise what this run changed.',
    detail?.status === 'FAILED' || detail?.status === 'PARTIAL'
      ? 'Why did this run fail and how do I fix it?'
      : 'What should I review before merging?',
    'What follow-up runs would you suggest?',
  ];

  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        flex: 1,
        minHeight: 0,
      }}
    >
      {/* 2026-05-09 — run-context banner so the chat tab has visible
       *  anchoring to the run being discussed (status / title / repo /
       *  age / cost / PR-link). Without this the chat opens cold and
       *  the user has to remember which run / repo they're on — feedback
       *  was "Run Chat is supposed to have details about the Run as well".
       *  Only renders when we have a detail object; the placeholder run
       *  during initial fetch is hidden so the panel doesn't flash. */}
      {detail && <ChatRunContextBanner run={detail} />}

      <div
        ref={threadRef}
        onScroll={handleThreadScroll}
        style={{
          flex: 1,
          overflowY: 'auto',
          padding: '12px 14px',
          display: 'flex',
          flexDirection: 'column',
          gap: 10,
          minHeight: 200,
        }}
      >
        {/* 2026-05-07 — Phase P1: pending PR review comments listed at the top
            of the Chat tab. Each row carries the comment text + author + file
            anchor + Dismiss + (P2) Quote in chat + Apply. The list updates live
            via the PR_COMMENT_RECEIVED SSE event handler in the parent. */}
        {pendingComments.length > 0 && (
          <div
            style={{
              border: '1px solid color-mix(in srgb, var(--v3-warning) 30%, transparent)',
              background: 'color-mix(in srgb, var(--v3-warning) 6%, transparent)',
              borderRadius: 8,
              padding: 10,
              display: 'flex',
              flexDirection: 'column',
              gap: 8,
            }}
          >
            <div
              className='v3-eyebrow'
              style={{
                color: 'var(--v3-warning)',
                display: 'flex',
                alignItems: 'center',
                gap: 6,
              }}
            >
              <MessageSquare className='h-3 w-3' />
              {pendingComments.length} pending PR comment{pendingComments.length === 1 ? '' : 's'}
            </div>
            {pendingComments.map((c) => (
              <PrCommentRow
                key={c.id}
                comment={c}
                applying={applyingId === c.id}
                onApply={() => onApply(c.id)}
                onQuote={() => onQuote(c)}
                onDismiss={() => onDismiss(c.id)}
              />
            ))}
          </div>
        )}

        {/* Recently-resolved comments (1 line each, neutral) so the user has a
            mini audit trail without needing to leave the run. Capped at 3. */}
        {recentlyAddressed.length > 0 && (
          <div
            style={{
              fontSize: 11,
              color: 'var(--v3-text-subtle)',
              padding: '6px 10px',
              borderTop: '1px dashed var(--v3-border)',
            }}
          >
            <span className='v3-eyebrow' style={{marginRight: 6}}>
              Recent
            </span>
            {recentlyAddressed.map((c, i) => (
              <span key={c.id}>
                {i > 0 && <span style={{color: 'var(--v3-text-subtle)'}}> · </span>}
                <a
                  href={c.githubUrl}
                  target='_blank'
                  rel='noreferrer'
                  style={{color: 'var(--v3-text-muted)', textDecoration: 'none'}}
                >
                  @{c.author}
                </a>
                <span style={{color: 'var(--v3-text-subtle)'}}>
                  {' '}
                  {c.status === 'ADDRESSED' ? '✓ addressed' : 'dismissed'}
                </span>
              </span>
            ))}
          </div>
        )}

        {messages.length === 0 && pendingComments.length === 0 && (
          <div
            style={{
              textAlign: 'center',
              color: 'var(--v3-text-muted)',
              fontSize: 12.5,
              lineHeight: 1.6,
              padding: '24px 12px',
            }}
          >
            Ask anything about this run — what changed, why a task failed, what to ship next. The
            assistant has the spec, status, and cost already loaded.
          </div>
        )}
        {messages.map((m, i) => (
          <ChatBubble key={i} role={m.role} content={m.content} />
        ))}
        {busy && (
          <div
            style={{
              fontSize: 12,
              color: 'var(--v3-text-subtle)',
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

      {messages.length === 0 && !busy && (
        <div
          style={{
            display: 'flex',
            flexWrap: 'wrap',
            gap: 6,
            padding: '0 12px 8px',
          }}
        >
          {QUICK_QUESTIONS.map((q, i) => (
            <button
              key={i}
              type='button'
              onClick={() => void send(q)}
              style={{
                padding: '4px 10px',
                background: 'transparent',
                color: 'var(--v3-text-muted)',
                border: '1px solid var(--v3-border)',
                borderRadius: 999,
                fontSize: 11.5,
                fontFamily: 'inherit',
                cursor: 'pointer',
                whiteSpace: 'normal',
                textAlign: 'left',
                lineHeight: 1.4,
              }}
            >
              {q}
            </button>
          ))}
        </div>
      )}

      {/* 2026-05-09 — chat action toast (transient) above the action row + input.
          One slot for both the success line ("Apply queued — N tasks dispatched")
          and the error line so layout doesn't jump. */}
      {actionToast && !error && (
        <div
          style={{
            margin: '0 12px 6px',
            padding: '6px 10px',
            background: 'color-mix(in srgb, var(--v3-success, #10B981) 12%, transparent)',
            border: '1px solid color-mix(in srgb, var(--v3-success, #10B981) 36%, transparent)',
            borderRadius: 6,
            fontSize: 12,
            color: 'var(--v3-text)',
          }}
        >
          {actionToast}
        </div>
      )}

      {/* 2026-05-09 — chat action row. Mirrors the v2 RunDetailTabs Chat tab
          actions (Apply / Fix N Issues / Iterate as New Run). Only renders for
          terminal runs — pre-terminal runs can't be applied to (worker still
          holds the workspace) and can't be iterated until the diff is final.
          Compile-as-Spec deliberately deferred — its v2 popover flow doesn't
          fit the v3 side-panel shape; tracked as a follow-up if user wants it. */}
      {isTerminal && (
        <div
          style={{
            display: 'flex',
            flexWrap: 'wrap',
            gap: 6,
            padding: '0 12px 8px',
            borderTop: '1px solid var(--v3-border)',
            paddingTop: 8,
          }}
        >
          {lastAssistantText && (
            <button
              type='button'
              onClick={() => void onApplyToRun()}
              disabled={applyingChat || fixingIssues || iteratingRun}
              title={
                "Apply the assistant's last suggestion as a follow-up run. The diff" +
                ' pushes to the same PR branch.'
              }
              style={chatActionButtonStyle('success', applyingChat)}
            >
              <Wrench className='h-3 w-3' />
              {applyingChat ? 'Applying…' : 'Apply to Run'}
            </button>
          )}
          {unresolvedIssues.length > 0 && (
            <button
              type='button'
              onClick={() => void onFixIssues()}
              disabled={applyingChat || fixingIssues || iteratingRun}
              title={`Auto-build a fix prompt from ${unresolvedIssues.length} unresolved review issue${
                unresolvedIssues.length === 1 ? '' : 's'
              } and dispatch as one apply-run.`}
              style={chatActionButtonStyle('warning', fixingIssues)}
            >
              <Shield className='h-3 w-3' />
              {fixingIssues
                ? 'Dispatching…'
                : `Fix ${unresolvedIssues.length} Issue${unresolvedIssues.length === 1 ? '' : 's'}`}
            </button>
          )}
          <button
            type='button'
            onClick={() => void onIterateAsNewRun()}
            disabled={applyingChat || fixingIssues || iteratingRun || messages.length === 0}
            title={
              messages.length === 0
                ? 'Start a conversation first — iterate uses the latest assistant turn as the spec.'
                : 'Spawn a brand-new run using the latest assistant turn as the spec.'
            }
            style={chatActionButtonStyle('brand', iteratingRun)}
          >
            <ArrowUpCircle className='h-3 w-3' />
            {iteratingRun ? 'Creating…' : 'Iterate as New Run'}
          </button>
        </div>
      )}

      <form
        onSubmit={onSubmit}
        style={{
          // 2026-05-10 (mobile audit) — paddingBottom respects iOS safe-area
          // so the composer sits above the home-indicator bar on iPhones.
          padding: '10px 12px',
          paddingBottom: 'max(10px, env(safe-area-inset-bottom))',
          borderTop: '1px solid var(--v3-border)',
          display: 'flex',
          gap: 6,
          background: 'var(--v3-bg)',
        }}
      >
        <input
          type='text'
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => {
            // Swallow Enter mid-IME composition so CJK candidate
            // selection doesn't accidentally submit the form.
            if (e.key === 'Enter' && e.nativeEvent.isComposing) {
              e.preventDefault();
              e.stopPropagation();
            }
          }}
          placeholder={messages.length === 0 ? 'Ask about this run…' : 'Reply…'}
          disabled={busy || !detail}
          style={{
            // 2026-05-10 (mobile audit) — minHeight 44 satisfies WCAG /
            // Apple HIG tap-target floor; was 30px (7+7+font-line) which is
            // too small for thumb input on phones.
            flex: 1,
            minWidth: 0,
            minHeight: 44,
            padding: '10px 12px',
            background: 'var(--v3-surface)',
            color: 'var(--v3-text)',
            border: '1px solid var(--v3-border)',
            borderRadius: 6,
            fontSize: 13,
            fontFamily: 'inherit',
          }}
        />
        <button
          type='submit'
          disabled={!input.trim() || busy || !detail}
          style={{
            // 2026-05-10 (mobile audit) — match input minHeight so the
            // composer is one consistent 44px row on phones.
            minHeight: 44,
            padding: '10px 16px',
            fontSize: 12.5,
            fontWeight: 600,
            background: input.trim() && !busy ? 'var(--v3-brand)' : 'var(--v3-surface-elevated)',
            color: input.trim() && !busy ? 'var(--v3-text-on-brand)' : 'var(--v3-text-subtle)',
            border: 'none',
            borderRadius: 6,
            cursor: input.trim() && !busy ? 'pointer' : 'not-allowed',
            fontFamily: 'inherit',
            flexShrink: 0,
          }}
        >
          Send
        </button>
      </form>
    </div>
  );
}

function ChatBubble({role, content}: {role: 'user' | 'assistant'; content: string}) {
  const isUser = role === 'user';
  return (
    <div style={{display: 'flex', justifyContent: isUser ? 'flex-end' : 'flex-start'}}>
      <div
        style={{
          maxWidth: '88%',
          background: isUser
            ? 'color-mix(in srgb, var(--v3-brand) 16%, transparent)'
            : 'var(--v3-bg)',
          border: `1px solid ${
            isUser ? 'color-mix(in srgb, var(--v3-brand) 40%, transparent)' : 'var(--v3-border)'
          }`,
          borderRadius: 10,
          padding: '7px 10px',
          fontSize: 12.5,
          lineHeight: 1.55,
          color: 'var(--v3-text)',
          wordBreak: 'break-word',
        }}
      >
        {/* 2026-05-07 — Issue 1: assistant replies render markdown so headings,
            bold, lists, fenced code blocks, tables, etc. all show up correctly.
            User messages stay as plain pre-wrap (anything they typed is text;
            rendering markdown they didn't author is a privacy / rendering-
            ambiguity risk). */}
        {isUser ? (
          <span style={{whiteSpace: 'pre-wrap'}}>{content}</span>
        ) : (
          <MarkdownContent content={content} compact />
        )}
      </div>
    </div>
  );
}

/**
 * 2026-05-07 — Phase P1 + P2: a single pending PR review comment row inside
 * ChatPane. Carries the comment text + author + file:line anchor + 4 actions:
 * "View on GitHub" (always), "Quote in chat" (Phase P2), "Address" (Phase P2 —
 * dispatches an apply run that pushes a fix to the PR's branch), and "Dismiss"
 * (Phase P1).
 */
function PrCommentRow({
  comment,
  applying,
  onApply,
  onQuote,
  onDismiss,
}: {
  comment: PrCommentInboxDto;
  applying: boolean;
  onApply: () => void;
  onQuote: () => void;
  onDismiss: () => void;
}) {
  const anchor = comment.filePath
    ? `${comment.filePath}${comment.lineNumber ? `:${comment.lineNumber}` : ''}`
    : 'PR-level';
  const preview = comment.body.length > 220 ? comment.body.slice(0, 220) + '…' : comment.body;
  return (
    <div
      style={{
        background: 'var(--v3-surface-elevated)',
        border: '1px solid var(--v3-border)',
        borderRadius: 6,
        padding: '8px 10px',
        display: 'flex',
        flexDirection: 'column',
        gap: 6,
      }}
    >
      <div
        style={{
          display: 'flex',
          alignItems: 'baseline',
          gap: 8,
          flexWrap: 'wrap',
          fontSize: 11,
        }}
      >
        <span style={{fontWeight: 600, color: 'var(--v3-text)'}}>@{comment.author}</span>
        <span className='v3-mono' style={{color: 'var(--v3-text-subtle)'}}>
          {anchor}
        </span>
        <a
          href={comment.githubUrl}
          target='_blank'
          rel='noreferrer'
          style={{
            marginLeft: 'auto',
            color: 'var(--v3-text-subtle)',
            fontSize: 10.5,
            textDecoration: 'none',
          }}
        >
          View ↗
        </a>
      </div>
      <div
        style={{
          fontSize: 12,
          color: 'var(--v3-text)',
          whiteSpace: 'pre-wrap',
          lineHeight: 1.5,
        }}
      >
        {preview}
      </div>
      <div style={{display: 'flex', gap: 6, flexWrap: 'wrap'}}>
        <button
          type='button'
          onClick={onApply}
          disabled={applying}
          style={{
            padding: '4px 10px',
            fontSize: 11,
            fontWeight: 600,
            background: 'var(--v3-brand)',
            color: 'var(--v3-text-on-brand)',
            border: 'none',
            borderRadius: 4,
            cursor: applying ? 'not-allowed' : 'pointer',
            opacity: applying ? 0.6 : 1,
            fontFamily: 'inherit',
          }}
        >
          {applying ? 'Applying…' : 'Address with chat'}
        </button>
        <button
          type='button'
          onClick={onQuote}
          style={{
            padding: '4px 10px',
            fontSize: 11,
            background: 'transparent',
            color: 'var(--v3-text-muted)',
            border: '1px solid var(--v3-border)',
            borderRadius: 4,
            cursor: 'pointer',
            fontFamily: 'inherit',
          }}
        >
          Quote in chat
        </button>
        <button
          type='button'
          onClick={onDismiss}
          style={{
            padding: '4px 10px',
            fontSize: 11,
            background: 'transparent',
            color: 'var(--v3-text-subtle)',
            border: '1px solid var(--v3-border)',
            borderRadius: 4,
            cursor: 'pointer',
            fontFamily: 'inherit',
          }}
        >
          Dismiss
        </button>
      </div>
    </div>
  );
}

/**
 * 2026-05-08 — was a single inline div that hard-truncated `userRequest`
 * to 280 chars. Bench-mode runs prepend the FAIL_TO_PASS pytest output
 * to the user request as ground truth (~5KB), so the truncated version
 * hid the part operators most wanted to inspect. Now: defaults to a
 * scrollable preview with line/char counts in the header, plus an
 * expand toggle for the full content. Pre-formatted text (whitespace
 * preserved) so command-line output keeps its layout.
 */
function RunSpecBlock({content}: {content: string}) {
  const [expanded, setExpanded] = useState(false);
  const [copied, setCopied] = useState(false);
  const lineCount = content.split('\n').length;
  const charCount = content.length;
  const showToggle = content.length > 600;
  const handleCopy = () => {
    navigator.clipboard.writeText(content).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    });
  };
  return (
    <div
      style={{
        background: 'var(--v3-bg)',
        border: '1px solid var(--v3-border)',
        borderRadius: 6,
        overflow: 'hidden',
      }}
    >
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 8,
          padding: '6px 10px',
          fontSize: 10,
          fontWeight: 600,
          textTransform: 'uppercase',
          letterSpacing: 0.6,
          color: 'var(--v3-text-subtle)',
          borderBottom: '1px solid var(--v3-border)',
        }}
      >
        <span>
          Goal · {lineCount} lines · {charCount.toLocaleString()} chars
        </span>
        <div style={{display: 'flex', gap: 4}}>
          <button
            type='button'
            onClick={handleCopy}
            style={{
              fontSize: 10,
              padding: '2px 8px',
              background: 'transparent',
              color: 'var(--v3-text-muted)',
              border: '1px solid var(--v3-border)',
              borderRadius: 4,
              cursor: 'pointer',
            }}
            title='Copy to clipboard'
          >
            {copied ? 'Copied' : 'Copy'}
          </button>
          {showToggle && (
            <button
              type='button'
              onClick={() => setExpanded((e) => !e)}
              style={{
                fontSize: 10,
                padding: '2px 8px',
                background: 'transparent',
                color: 'var(--v3-text-muted)',
                border: '1px solid var(--v3-border)',
                borderRadius: 4,
                cursor: 'pointer',
              }}
            >
              {expanded ? 'Collapse' : 'Show all'}
            </button>
          )}
        </div>
      </div>
      <div
        style={{
          fontSize: 12,
          color: 'var(--v3-text-muted)',
          lineHeight: 1.55,
          padding: '8px 10px',
          maxHeight: expanded ? 600 : 240,
          overflow: 'auto',
          whiteSpace: 'pre-wrap',
          wordBreak: 'break-word',
          fontFamily: 'var(--v3-font-mono, ui-monospace, monospace)',
        }}
      >
        {content}
      </div>
    </div>
  );
}

/**
 * Compact run-context banner for the v3 ChatPane. Shows what the chat is
 * scoped to (status pill / title-or-spec / repo / age / cost / PR link)
 * without spending vertical space on labels. Two rows: the first is the
 * primary identity (badge + title + meta chips), the second is the repo
 * + PR slug. Both rows hide when there's no data so an empty run doesn't
 * render placeholders.
 */
function ChatRunContextBanner({run}: {run: RunDetail}) {
  const TERMINAL = new Set(['COMPLETE', 'PARTIAL', 'FAILED', 'CANCELLED', 'ABORTED']);
  const isTerminal = TERMINAL.has(run.status);
  const titleText =
    run.runTitle?.trim() ||
    (run.userRequest && run.userRequest.length > 90
      ? run.userRequest.slice(0, 87).trimEnd() + '…'
      : run.userRequest) ||
    `Run ${run.runId.slice(0, 8)}`;
  const repoFull =
    run.repoUrl?.replace(/^https?:\/\/github\.com\//, '').replace(/\.git$/, '') ?? null;
  const startedRel = run.startedAt ? formatRelative(run.startedAt) : null;
  const completedRel = run.completedAt ? formatRelative(run.completedAt) : null;
  const durationStr =
    run.startedAt && run.completedAt
      ? formatDuration(new Date(run.completedAt).getTime() - new Date(run.startedAt).getTime())
      : run.startedAt && !isTerminal
        ? formatDuration(Date.now() - new Date(run.startedAt).getTime())
        : null;
  const prUrl = run.scmPrUrl ?? run.githubPrUrl ?? null;
  const cost = run.actualCostUsd ?? null;

  // Status tone mirrors Pill — keep colour selection in lockstep with
  // the rest of v3 so a glance at the panel + a glance at the header
  // tell the same story.
  const statusTone: 'ok' | 'warn' | 'err' | 'neutral' =
    run.status === 'COMPLETE'
      ? 'ok'
      : run.status === 'PARTIAL'
        ? 'warn'
        : run.status === 'FAILED' || run.status === 'CANCELLED' || run.status === 'ABORTED'
          ? 'err'
          : 'neutral';

  return (
    <div
      style={{
        padding: '8px 14px',
        borderBottom: '1px solid var(--v3-border)',
        background: 'color-mix(in srgb, var(--v3-surface) 60%, transparent)',
        flexShrink: 0,
        display: 'flex',
        flexDirection: 'column',
        gap: 4,
      }}
      title={run.userRequest ?? undefined}
    >
      <div style={{display: 'flex', alignItems: 'center', gap: 8, minWidth: 0}}>
        <Pill tone={statusTone} size='sm' dot>
          {run.status}
        </Pill>
        <span
          style={{
            fontSize: 12.5,
            color: 'var(--v3-text)',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap',
            flex: 1,
            minWidth: 0,
          }}
        >
          {titleText}
        </span>
        <span
          style={{
            fontSize: 10.5,
            color: 'var(--v3-text-subtle)',
            fontFamily: 'var(--v3-font-mono, ui-monospace, monospace)',
            whiteSpace: 'nowrap',
            flexShrink: 0,
          }}
          title={`Run ID: ${run.runId}`}
        >
          {run.runId.slice(0, 8)}
        </span>
      </div>
      {(repoFull || startedRel || durationStr || cost != null || prUrl) && (
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 10,
            fontSize: 11,
            color: 'var(--v3-text-muted)',
            fontFamily: 'var(--v3-font-mono, ui-monospace, monospace)',
            flexWrap: 'wrap',
          }}
        >
          {repoFull && (
            <span
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: 4,
                maxWidth: 280,
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'nowrap',
              }}
              title={run.repoUrl ?? ''}
            >
              <GitCommit className='h-3 w-3' />
              <span style={{overflow: 'hidden', textOverflow: 'ellipsis'}}>{repoFull}</span>
            </span>
          )}
          {startedRel && (
            <span
              title={`Started: ${run.startedAt}${run.completedAt ? `\nCompleted: ${run.completedAt}` : ''}`}
            >
              {isTerminal && completedRel ? completedRel : startedRel}
            </span>
          )}
          {durationStr && <span title='Duration'>{durationStr}</span>}
          {cost != null && cost > 0 && <span title='Total cost (USD)'>${cost.toFixed(2)}</span>}
          {prUrl && (
            <a
              href={prUrl}
              target='_blank'
              rel='noreferrer'
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: 3,
                color: 'var(--v3-brand)',
                textDecoration: 'none',
              }}
              title='Open the pull request opened by this run'
            >
              PR ↗
            </a>
          )}
        </div>
      )}
    </div>
  );
}

/** Small relative-time helper. Self-contained so the banner has no
 *  cross-file dependency. */
function formatRelative(iso: string): string {
  const then = new Date(iso).getTime();
  const diff = Date.now() - then;
  if (diff < 0) return 'just now';
  const sec = Math.floor(diff / 1000);
  if (sec < 60) return `${sec}s ago`;
  const min = Math.floor(sec / 60);
  if (min < 60) return `${min}m ago`;
  const hr = Math.floor(min / 60);
  if (hr < 24) return `${hr}h ago`;
  const day = Math.floor(hr / 24);
  if (day < 7) return `${day}d ago`;
  return new Date(iso).toLocaleDateString();
}

/** Compact ms → `1h 4m` / `4m 12s` / `12s`. Matches the cadence the
 *  v2 fmtMs helper uses but without the cross-import. */
function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms}ms`;
  const sec = Math.floor(ms / 1000);
  if (sec < 60) return `${sec}s`;
  const min = Math.floor(sec / 60);
  const remSec = sec % 60;
  if (min < 60) return remSec > 0 ? `${min}m ${remSec}s` : `${min}m`;
  const hr = Math.floor(min / 60);
  const remMin = min % 60;
  return remMin > 0 ? `${hr}h ${remMin}m` : `${hr}h`;
}
