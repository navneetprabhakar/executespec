import type * as api from '@/lib/api';
import type {DiffLine} from '@/ide/IdeKit';

export type Verdict = {label: string; kind: 'ok' | 'warn' | 'bad'};

export function verdictFor(score: number | null | undefined, status?: string | null): Verdict {
  const s = (status || '').toUpperCase();
  if (s === 'FAILED' || s === 'ERROR' || s === 'CANCELLED') return {label: 'Failed', kind: 'bad'};
  if (score == null) return {label: s === 'COMPLETE' ? 'Complete' : 'In progress', kind: 'warn'};
  if (score >= 85) return {label: 'Shippable', kind: 'ok'};
  if (score >= 70) return {label: 'Needs review', kind: 'warn'};
  return {label: 'Failed', kind: 'bad'};
}

export const SEV: Record<string, {l: string; k: 'ok' | 'warn' | 'bad'}> = {
  WOULD_BREAK_BUILD: {l: 'BUILD', k: 'bad'},
  WOULD_FAIL_REVIEW: {l: 'REVIEW', k: 'warn'},
  WOULD_CAUSE_BUG: {l: 'BUG', k: 'warn'},
  WOULD_FAIL_LINT: {l: 'LINT', k: 'ok'},
};

export const evidenceKind = (status?: string): 'ok' | 'warn' | 'bad' =>
  status === 'PASS' ? 'ok' : status === 'FAIL' ? 'bad' : 'warn';

export const confidenceColor = (confidence?: string) =>
  confidence === 'HIGH'
    ? 'var(--ide-green)'
    : confidence === 'LOW'
      ? 'var(--ide-red)'
      : 'var(--ide-yellow)';

export const usd = (n?: number | null) => (n == null ? '—' : `$${n.toFixed(2)}`);
export const num = (n?: number | null) => (n == null ? '—' : n.toLocaleString());

export const TABS = [
  'Overview',
  'Code',
  'Tasks',
  'Timeline',
  'Logs',
  'Validation',
  'Cost',
  'PR',
  'Tests',
  'Chat',
] as const;

export type Tab = (typeof TABS)[number];

export const TAB_LABEL: Record<Tab, string> = {
  Overview: 'Overview',
  Code: 'Code',
  Tasks: 'Steps',
  Timeline: 'Timeline',
  Logs: 'Activity log',
  Validation: 'Evidence',
  Cost: 'Cost',
  PR: 'Pull request',
  Tests: 'Tests',
  Chat: 'Ask',
};

export function toLines(s: string): string[] {
  return (s || '').replace(/\n$/, '').split('\n');
}

/**
 * LCS line diff: `base` (old) -> `head` (new). Deletions in a hunk are emitted before additions
 * that replace them, matching standard diff grouping.
 */
export function lineDiff(base: string, head: string): DiffLine[] {
  const a = toLines(base);
  const b = toLines(head);
  const n = a.length;
  const m = b.length;
  const lcs: number[][] = Array.from({length: n + 1}, () => new Array<number>(m + 1).fill(0));
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      lcs[i][j] = a[i] === b[j] ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
    }
  }
  const out: DiffLine[] = [];
  let i = 0;
  let j = 0;
  while (i < n && j < m) {
    if (a[i] === b[j]) {
      out.push({sign: ' ', text: a[i]});
      i++;
      j++;
    } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
      out.push({sign: '-', text: a[i]});
      i++;
    } else {
      out.push({sign: '+', text: b[j]});
      j++;
    }
  }
  while (i < n) out.push({sign: '-', text: a[i++]});
  while (j < m) out.push({sign: '+', text: b[j++]});
  return out;
}

export function fmtDur(ms?: number | null) {
  if (!ms) return '—';
  const s = Math.round(ms / 1000);
  return s < 60 ? `${s}s` : `${Math.floor(s / 60)}m ${s % 60}s`;
}

export function shouldUseRunEvidenceChat(message: string): boolean {
  const text = message.trim().toLowerCase();
  if (!text) return true;
  const explicitMutation =
    /\b(change|modify|edit|fix|implement|add|remove|refactor|rewrite|create|start|rerun|re-run|iterate|apply|push|deploy|open\s+(a\s+)?pr)\b/.test(
      text,
    );
  const questionPrefix = /^(why|what|where|which|how|explain|show|summarize|tell|list)\b/.test(
    text,
  );
  const evidenceKeyword =
    /\b(evidence|score|cost|token|credit|test|build|review|file|diff|log|failure|failed|partial|status|result|report|artifact|pr)\b/.test(
      text,
    );
  const hardEvidenceKeyword =
    /\b(evidence|score|cost|token|credit|log|report|artifact|status|result)\b/.test(text);
  if (explicitMutation && !questionPrefix && !hardEvidenceKeyword) return false;
  if (questionPrefix || evidenceKeyword) return true;
  return !explicitMutation;
}

export function isFailedTerminalStatus(status?: string | null): boolean {
  return ['FAILED', 'ERROR', 'CANCELLED', 'PARTIAL'].includes((status || '').toUpperCase());
}

export function hasRunCostEvidence(
  run:
    | Pick<
        api.RunDetail,
        | 'totalInputTokens'
        | 'totalOutputTokens'
        | 'aiCreditsCharged'
        | 'actualCostUsd'
        | 'classifierCostUsd'
        | 'outlineCostUsd'
        | 'expansionCostUsd'
      >
    | null
    | undefined,
): boolean {
  if (!run) return false;
  return (
    (run.totalInputTokens ?? 0) > 0 ||
    (run.totalOutputTokens ?? 0) > 0 ||
    (run.aiCreditsCharged ?? 0) > 0 ||
    (run.actualCostUsd ?? 0) > 0 ||
    (run.classifierCostUsd ?? 0) > 0 ||
    (run.outlineCostUsd ?? 0) > 0 ||
    (run.expansionCostUsd ?? 0) > 0
  );
}

export function testRerunState({
  status,
  hasTestPlan,
  hasTests,
}: {
  status?: string | null;
  hasTestPlan: boolean;
  hasTests: boolean;
}): {canRerunTests: boolean; emptyMessage: string} {
  const canRerunTests = hasTestPlan || hasTests;
  if (canRerunTests) {
    return {canRerunTests, emptyMessage: 'No test report yet.'};
  }
  const emptyMessage = isFailedTerminalStatus(status)
    ? 'No test plan or measured tests are available for this failed run. Re-run is disabled until the run has generated code or a test plan to execute.'
    : 'No test report yet.';
  return {canRerunTests, emptyMessage};
}

/**
 * Manual test execution is queued by the API and completes asynchronously. Keep
 * the result surface honest while that work is in flight instead of treating the
 * first 0/0 response as the final report.
 */
export function isTestExecutionTerminal(status?: string | null): boolean {
  return ['COMPLETED', 'FAILED'].includes((status || '').toUpperCase());
}

export const TEST_EXECUTION_POLL_INTERVAL_MS = 2000;
export const TEST_EXECUTION_MAX_POLLS = 45;

export function fileCountSummary({
  reportedFiles,
  workspaceFiles,
}: {
  reportedFiles?: number | null;
  workspaceFiles: number;
}): {label: string; title: string; mismatch: boolean; explanation: string | null} {
  const mismatch = reportedFiles != null && reportedFiles !== workspaceFiles;
  const label =
    reportedFiles != null
      ? `${reportedFiles} reported${mismatch ? ` · ${workspaceFiles} visible` : ''}`
      : String(workspaceFiles);
  return {
    label,
    title: `${workspaceFiles} file(s) currently visible in the Code tab`,
    mismatch,
    explanation: mismatch
      ? 'File counts differ because validation reports generated-file evidence, while the Code tab lists the current workspace files. Failed scaffold runs can expose seed files even when no product code was delivered.'
      : null,
  };
}

export function emptyCodeTabMessage({
  hasFiles,
  status,
}: {
  hasFiles: boolean;
  status?: string | null;
}): string {
  if (hasFiles) return 'Select a file';
  return isFailedTerminalStatus(status)
    ? 'No generated files. This failed run did not leave product code to inspect.'
    : 'No generated files';
}

export type RunPhaseDiagnosis = {
  title: string;
  body: string;
  facts: string[];
  primaryTab: Tab;
};

export function goalStateLabel(state?: string | null): string {
  switch ((state || '').toUpperCase()) {
    case 'DONE':
      return 'Goal proven';
    case 'FAILED':
      return 'Proof failed';
    case 'BLOCKED':
      return 'Blocked';
    case 'WASTE_RISK':
      return 'Waste risk';
    case 'IN_PROGRESS':
      return 'Still checking';
    case 'UNKNOWN':
      return 'Proof unknown';
    default:
      return state ? titleCaseType(state) : 'Proof unknown';
  }
}

export function goalDecisionLabel(decision?: string | null): string {
  switch ((decision || '').toUpperCase()) {
    case 'STOP_DONE':
      return 'Proof accepted';
    case 'STOP_FAILED':
      return 'Proof failed';
    case 'STOP_BLOCKED':
      return 'Needs input';
    case 'STOP_WASTE_GUARD':
      return 'Stopped by limit';
    case 'CONTINUE':
    case 'CONTINUE_SAME_MODEL_PROVIDER':
      return 'Keep working';
    case 'STEP_UP_MODEL':
      return 'Try stronger model';
    case 'STEP_DOWN_MODEL':
      return 'Try cheaper model';
    case 'CHANGE_PROVIDER':
      return 'Try another provider';
    default:
      return decision ? titleCaseType(decision) : 'No decision yet';
  }
}

export function goalDecisionHelp(decision?: string | null): string | null {
  switch ((decision || '').toUpperCase()) {
    case 'STOP_FAILED':
      return 'The system could not prove the requested outcome from available evidence. This is a proof failure, not a claim that every generated file is unusable.';
    case 'STOP_BLOCKED':
      return 'The run needs user input, missing environment access, or explicit approval before it can continue.';
    case 'STOP_WASTE_GUARD':
      return 'The run stopped because a configured spend, token, time, or repetition guard fired.';
    case 'STOP_DONE':
      return 'The available evidence was enough to accept the goal as complete.';
    default:
      return null;
  }
}

function lastUsefulLog(events?: api.RunLogEvent[] | null): string | null {
  const event = [...(events || [])]
    .reverse()
    .find(
      (ev) =>
        !isNoisyLogEvent(ev) &&
        !['RUN_DETAIL', 'RUN_STATUS'].includes((ev.type || '').toUpperCase()),
    );
  return event ? logMessage(event) : null;
}

export function runPhaseDiagnosis({
  status,
  tasks,
  report,
  tests,
  eventLog,
}: {
  status?: string | null;
  tasks?: Pick<api.TaskSummary, 'status' | 'failureReason'>[] | null;
  report?: Pick<
    api.ValidationReportData,
    'reviewerVerdict' | 'reviewFailures' | 'finalBuildPassed' | 'testsTotal' | 'testsPassed'
  > | null;
  tests?: Pick<api.TestReportData, 'totalTests' | 'passed'> | null;
  eventLog?: api.RunLogEvent[] | null;
}): RunPhaseDiagnosis | null {
  const s = (status || '').toUpperCase();
  const reviewLike = ['REVIEWING', 'VALIDATING', 'GENERATING_REPORT', 'REPORTING'].includes(s);
  if (!reviewLike) return null;

  const taskList = tasks || [];
  const completeTasks = taskList.filter(
    (t) => (t.status || '').toUpperCase() === 'COMPLETE',
  ).length;
  const blockedTasks = taskList.filter((t) =>
    ['FAILED', 'PARTIAL', 'BLOCKED'].includes((t.status || '').toUpperCase()),
  ).length;
  const runningTasks = taskList.filter((t) =>
    ['RUNNING', 'WORKING', 'IN_PROGRESS'].includes((t.status || '').toUpperCase()),
  ).length;
  const facts: string[] = [];
  if (taskList.length > 0) facts.push(`${completeTasks}/${taskList.length} steps complete`);
  if (runningTasks > 0)
    facts.push(`${runningTasks} step${runningTasks === 1 ? '' : 's'} still active`);
  if (blockedTasks > 0)
    facts.push(`${blockedTasks} step${blockedTasks === 1 ? '' : 's'} need attention`);

  if (report) {
    facts.push(`reviewer ${report.reviewerVerdict || 'reported'}`);
    if ((report.reviewFailures ?? 0) > 0) facts.push(`${report.reviewFailures} reviewer issue(s)`);
    if (report.finalBuildPassed === true) facts.push('final build passed');
    if (report.finalBuildPassed === false) facts.push('final build failed');
  } else {
    facts.push('validation report not ready');
  }

  const totalTests = tests?.totalTests ?? report?.testsTotal ?? null;
  const passedTests = tests?.passed ?? report?.testsPassed ?? null;
  if (totalTests != null && totalTests > 0) {
    facts.push(`${passedTests ?? 0}/${totalTests} tests passed`);
  }

  const latest = lastUsefulLog(eventLog);
  if (latest) facts.push(`latest: ${latest}`);

  if (s === 'REVIEWING') {
    return {
      title: 'Reviewer is still resolving this run.',
      body:
        blockedTasks > 0
          ? 'At least one worker step already needs attention. Check Steps and Activity Log before assuming the reviewer is simply slow.'
          : 'Worker output is waiting on reviewer or final build/test validation. If this does not move, the Activity Log should show the last reviewer, tool, or validation event.',
      facts: facts.slice(0, 5),
      primaryTab: blockedTasks > 0 ? 'Tasks' : 'Logs',
    };
  }

  return {
    title: 'Validation evidence is still being assembled.',
    body: 'The run has moved past worker execution, but the final report or measured proof is not ready yet. Check Activity Log for the last validation event and Evidence once the report appears.',
    facts: facts.slice(0, 5),
    primaryTab: 'Logs',
  };
}

export function shouldShowModelRanking(
  t: Pick<api.TaskSummary, 'continuationDecision' | 'goalDoneness'>,
): boolean {
  const continuation = t.continuationDecision;
  const policy = continuation?.policy;
  if (!policy?.rankingPolicy || !policy.topEligibleCandidates?.length) return false;

  const decision = (continuation?.decision || '').toUpperCase();
  const goalDecision = (t.goalDoneness?.recommendedDecision || '').toUpperCase();
  if (decision === 'STOP_DONE' || goalDecision === 'STOP_DONE') return false;

  return [
    'STEP_UP_MODEL',
    'STEP_DOWN_MODEL',
    'CHANGE_PROVIDER',
    'STOP_BLOCKED',
    'STOP_FAILED',
    'STOP_WASTE_GUARD',
    'CONTINUE_SAME_MODEL_PROVIDER',
  ].includes(decision);
}

type ContinuationPolicy = NonNullable<
  NonNullable<api.TaskSummary['continuationDecision']>['policy']
>;

type RankingCandidate = NonNullable<ContinuationPolicy['topEligibleCandidates']>[number];

function sourceLabel(source?: string | null): string | null {
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

function providerDisplay(provider?: string | null): string {
  const raw = text(provider);
  if (!raw) return 'Provider';
  switch (raw.toUpperCase()) {
    case 'OPENAI':
      return 'OpenAI';
    case 'OPENROUTER':
      return 'OpenRouter';
    case 'DEEPSEEK':
      return 'DeepSeek';
    case 'FIREWORKS':
      return 'Fireworks';
    case 'GEMINI':
    case 'GOOGLE':
      return 'Gemini';
    case 'ANTHROPIC':
      return 'Anthropic';
    default:
      return raw.replace(/_/g, ' ').replace(/\b\w/g, (m) => m.toUpperCase());
  }
}

export function modelRankingLabel(policy?: string | null): string {
  return policy === 'quality_desc_then_cost_asc_then_latency_asc'
    ? 'Selected by quality first, then cost and latency'
    : policy
      ? titleCaseType(policy)
      : 'Model route comparison';
}

export function modelRankingDetails(policy: ContinuationPolicy): string[] {
  const details: string[] = [];
  const source = sourceLabel(policy.source);
  if (source) details.push(source);
  if (
    typeof policy.rankingQualityTieBand === 'number' &&
    Number.isFinite(policy.rankingQualityTieBand)
  ) {
    details.push(
      `cost and latency break ties within ${policy.rankingQualityTieBand.toFixed(1)} quality points`,
    );
  }
  if (policy.requireBenchmarkPriorForAutomaticContinuation === true) {
    details.push('automatic upgrades need task-relevant benchmark evidence');
  } else if (policy.requireBenchmarkPriorForAutomaticContinuation === false) {
    details.push('benchmark evidence is advisory for this run');
  }
  if (policy.requireConfirmationBeforeExpensiveContinuation === true) {
    details.push('manual approval controls model/provider upgrades');
  }
  if (policy.routePinned) details.push('route pinned by request');
  if (policy.lowComplexityWorkerStartTierEffective) {
    const effective = policy.lowComplexityWorkerStartTierEffective.toLowerCase();
    const configured = policy.lowComplexityWorkerStartTierConfigured?.toLowerCase();
    details.push(
      configured && configured !== effective
        ? `light tasks start on ${effective} (${configured} ignored)`
        : `light tasks start on ${effective}`,
    );
  }
  return details;
}

export function modelRankingCandidateLabel(c: RankingCandidate): string {
  return `${providerDisplay(c.provider)}/${c.model || 'model'}`;
}

export function modelRankingCandidateMetrics(c: RankingCandidate): string {
  const quality =
    c.qualityScore == null ? 'quality unknown' : `quality ${Math.round(c.qualityScore)}`;
  const cost =
    c.costPerMillionTokens == null
      ? 'cost unknown'
      : `$${c.costPerMillionTokens.toFixed(2)} per 1M tokens`;
  const latency = c.latencyP50Ms == null ? 'latency unknown' : `p50 ${fmtDur(c.latencyP50Ms)}`;
  const benchmark =
    c.benchmarkQualityPriorAvailable === true
      ? 'benchmark evidence available'
      : c.benchmarkQualityPriorAvailable === false
        ? 'benchmark evidence missing'
        : 'benchmark evidence unknown';
  return `${quality} · ${cost} · ${latency} · ${benchmark}`;
}

export function logIcon(type?: string): string {
  const t = (type || '').toUpperCase();
  if (t.includes('TOOL')) return '🔧';
  if (t.includes('LLM') || t.includes('COMPLETION')) return '💬';
  if (t.includes('FILE')) return '✏️';
  if (t.includes('BUILD') || t.includes('COMPILE')) return '🔨';
  if (t.includes('TEST')) return '🧪';
  if (t.includes('GATE')) return '⏸️';
  if (t.includes('COST') || t.includes('TOKEN')) return '💰';
  if (t.includes('COMPLETE')) return '✅';
  if (t.includes('FAIL') || t.includes('ERROR')) return '❌';
  if (t.includes('WARN')) return '⚠️';
  if (t.includes('PROVIDER')) return '🔀';
  if (t.includes('WAVE') || t.includes('PARALLEL')) return '≋';
  if (t.includes('GOAL') || t.includes('DECISION')) return '🧭';
  if (t.includes('CONTEXT')) return '🧭';
  if (t.includes('RECOVERY')) return '♻️';
  if (t.includes('STATUS')) return '●';
  return '·';
}

export function isNoisyLogEvent(ev: api.RunLogEvent): boolean {
  return (ev.type || '').toUpperCase().includes('RUN_DETAIL');
}

function eventData(ev: api.RunLogEvent): Record<string, unknown> {
  return (
    ev.data && typeof ev.data === 'object' ? {...ev, ...(ev.data as Record<string, unknown>)} : ev
  ) as Record<string, unknown>;
}

function text(v: unknown): string | null {
  if (v == null) return null;
  const s = String(v).trim();
  return s.length ? s : null;
}

function money(v: unknown): string | null {
  const n = typeof v === 'number' ? v : Number(v);
  return Number.isFinite(n) ? `$${n.toFixed(4)}` : null;
}

function shortId(v: unknown): string | null {
  const s = text(v);
  return s && s.length > 8 ? s.slice(0, 8) : s;
}

function parseJsonObject(v: unknown): Record<string, unknown> | null {
  const s = text(v);
  if (!s || !s.startsWith('{')) return null;
  try {
    const parsed = JSON.parse(s);
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : null;
  } catch {
    return null;
  }
}

function titleCaseType(type?: string): string {
  return (type || 'log')
    .toLowerCase()
    .replace(/_/g, ' ')
    .replace(/\b\w/g, (m) => m.toUpperCase());
}

function titleCaseProvider(value: string | null): string | null {
  if (!value) return null;
  const normalized = value.toUpperCase().replace(/[-\s]+/g, '_');
  if (normalized === 'OPENAI') return 'OpenAI';
  if (normalized === 'OPENROUTER') return 'OpenRouter';
  if (normalized === 'DEEPSEEK') return 'DeepSeek';
  if (normalized === 'FIREWORKS') return 'Fireworks';
  if (normalized === 'ANTHROPIC') return 'Anthropic';
  if (normalized === 'GEMINI') return 'Gemini';
  return value
    .toLowerCase()
    .split(/[_\s-]+/)
    .filter(Boolean)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(' ');
}

function providerSwitchParts(ev: api.RunLogEvent): {
  from: string | null;
  to: string | null;
  reason: string | null;
} {
  const d = eventData(ev);
  const from = text(d.fromProvider ?? d.previousProvider ?? d.sourceProvider);
  const to = text(d.toProvider ?? d.nextProvider ?? d.selectedProvider ?? d.provider);
  const reason = text(d.reason ?? d.message ?? d.error ?? d.payload);
  const legacy = text(d.taskId ?? ev.taskId);
  if ((from || to || reason) && !legacy?.includes('|')) {
    return {from, to, reason: sanitizeProviderSwitchReason(reason)};
  }
  const parts = (legacy || '').split('|');
  if (parts.length >= 3) {
    return {
      from: parts[0] || from,
      to: parts[1] || to,
      reason: sanitizeProviderSwitchReason(parts.slice(2).join('|') || reason),
    };
  }
  return {from, to, reason: sanitizeProviderSwitchReason(reason)};
}

function sanitizeProviderSwitchReason(value: string | null): string | null {
  if (!value) return null;
  const lower = value.toLowerCase();
  if (
    lower.includes('invalid x-api-key') ||
    lower.includes('authentication_error') ||
    lower.includes('401')
  ) {
    return 'provider authentication failed';
  }
  if (lower.includes('not configured') || lower.includes('no user/org key')) {
    return 'provider key is not configured';
  }
  return value.length > 160 ? `${value.slice(0, 160)}…` : value;
}

export function logLabel(type?: string): string {
  const t = (type || '').toUpperCase();
  if (t === 'RUN_STATUS') return 'Run';
  if (t === 'RUN_PROVIDER_SWITCHED') return 'Provider';
  if (t === 'TASK_STATUS') return 'Task';
  if (t === 'GATE_OPENED' || t === 'GATE_RESOLVED') return 'Approval';
  if (t === 'TASK_WAVE_PLAN') return 'Plan';
  if (t === 'TOKEN_USAGE') return 'Usage';
  if (t === 'TOOL_CALL') return 'Tool';
  if (t === 'TOOL_RESULT') return 'Tool result';
  if (t === 'FILE_WRITTEN') return 'File';
  if (t === 'BUILD_RESULT' || t === 'BUILD_FAILED' || t === 'BUILD_COMPLETE') return 'Build';
  if (t === 'TEST_RESULT' || t === 'TESTS_FAILED' || t === 'TESTS_COMPLETE') return 'Tests';
  if (t === 'REVIEWER_RESULT' || t === 'REVIEWER_FAILED' || t === 'REVIEW_STARTED') return 'Review';
  if (t === 'GOAL_DECISION') return 'Goal';
  if (t === 'CONTINUATION_DECISION') return 'Continuation';
  if (t === 'CONTINUATION_ACTION' || t === 'CONTINUATION_ACTION_SKIPPED') return 'Action';
  if (t === 'COMPLETE') return 'Complete';
  if (t === 'COST_UPDATE') return 'Cost';
  if (t === 'LOG') return 'Log';
  return titleCaseType(type);
}

export function logMessage(ev: api.RunLogEvent): string {
  const d = eventData(ev);
  const type = (ev.type || '').toUpperCase();
  const payload = text(d.payload);
  const task = shortId(d.taskId ?? ev.taskId);

  if (type === 'RUN_STATUS') return `Run status: ${payload || text(d.status) || 'updated'}`;
  if (type === 'RUN_PROVIDER_SWITCHED') {
    const parts = providerSwitchParts(ev);
    const route = [titleCaseProvider(parts.from), titleCaseProvider(parts.to)]
      .filter(Boolean)
      .join(' → ');
    return ['Provider switched', route || null, parts.reason].filter(Boolean).join(' · ');
  }
  if (type === 'TASK_STATUS') {
    const status = payload || text(d.status) || 'updated';
    return task ? `Task ${task}: ${status}` : `Task status: ${status}`;
  }
  if (type === 'GATE_OPENED') {
    const parts = (payload || '').split('|');
    const gate = parts[0] || 'approval';
    const credits = parts[1] ? `${parts[1]} credits` : null;
    const estimate = parts[2] ? `est. $${parts[2]}` : null;
    return ['Plan gate opened', gate, credits, estimate].filter(Boolean).join(' · ');
  }
  if (type === 'GATE_RESOLVED') {
    return `Plan gate ${String(payload || 'resolved').toLowerCase()}`;
  }
  if (type === 'TASK_WAVE_PLAN') {
    const mode = text(d.executionMode);
    const waveCount =
      typeof d.waveCount === 'number'
        ? d.waveCount
        : Array.isArray(d.waves)
          ? d.waves.length
          : null;
    const cap = text(d.maxParallelTasks);
    return [
      payload || 'Task execution plan ready',
      mode ? `mode ${mode}` : null,
      waveCount != null ? `${waveCount} wave${waveCount === 1 ? '' : 's'}` : null,
      cap ? `cap ${cap}` : null,
    ]
      .filter(Boolean)
      .join(' · ');
  }
  if (type === 'TOKEN_USAGE') {
    const explicit = text(d.message ?? d.line ?? d.summary);
    if (explicit) return explicit.length > 280 ? `${explicit.slice(0, 280)}…` : explicit;
    const provider = text(d.provider);
    const model = text(d.model);
    const input = text(d.inputTokens);
    const output = text(d.outputTokens);
    const total = text(d.totalTokens);
    const cost = money(d.costUsd);
    return [
      'Token usage',
      [provider, model].filter(Boolean).join(' / ') || null,
      input && output ? `${input} in / ${output} out` : total ? `${total} tokens` : payload,
      cost,
    ]
      .filter(Boolean)
      .join(' · ');
  }
  if (type === 'TOOL_CALL') {
    const frame = parseJsonObject(d.payload);
    const name = text(d.toolName) || text(frame?.name);
    const permission = text(d.permissionKind);
    return ['Tool requested', name, permission].filter(Boolean).join(' · ');
  }
  if (type === 'TOOL_RESULT') {
    const frame = parseJsonObject(d.payload);
    const name = text(d.toolName) || text(frame?.name);
    const ok = text(d.ok ?? d.success ?? frame?.ok ?? frame?.success);
    const result = ok == null ? null : ok === 'true' ? 'OK' : ok === 'false' ? 'ERR' : ok;
    const message = text(d.message ?? d.error ?? frame?.message ?? frame?.error);
    return ['Tool result', name, result, message].filter(Boolean).join(' · ');
  }
  if (type === 'FILE_WRITTEN')
    return `File written: ${payload || text(d.relativePath) || 'workspace'}`;
  if (type === 'BUILD_RESULT' || type === 'BUILD_COMPLETE' || type === 'BUILD_FAILED') {
    const status = text(d.status) || (type === 'BUILD_FAILED' ? 'failed' : null);
    const command = text(d.command);
    const message = text(d.message ?? d.error ?? d.summary ?? d.reason) || payload;
    return ['Build', status, command, message].filter(Boolean).join(' · ');
  }
  if (type === 'TEST_RESULT' || type === 'TESTS_COMPLETE' || type === 'TESTS_FAILED') {
    const status = text(d.status) || (type === 'TESTS_FAILED' ? 'failed' : null);
    const passed = text(d.passedTests ?? d.passed);
    const total = text(d.totalTests ?? d.total);
    const counts = passed && total ? `${passed}/${total} passed` : null;
    const message = text(d.message ?? d.error ?? d.summary ?? d.reason) || payload;
    return ['Tests', status, counts, message].filter(Boolean).join(' · ');
  }
  if (type === 'REVIEWER_RESULT' || type === 'REVIEWER_FAILED' || type === 'REVIEW_STARTED') {
    const status = text(d.status ?? d.verdict) || (type === 'REVIEWER_FAILED' ? 'failed' : null);
    const pass = text(d.pass ?? d.phase ?? d.stage);
    const issueCount = text(d.issueCount ?? d.issues);
    const issueText = issueCount ? `${issueCount} issue${issueCount === '1' ? '' : 's'}` : null;
    const message = text(d.message ?? d.error ?? d.summary ?? d.reason) || payload;
    return ['Reviewer', pass, status, issueText, message].filter(Boolean).join(' · ');
  }
  if (type === 'GOAL_DECISION') return payload || text(d.reason) || 'Goal decision recorded';
  if (type === 'CONTINUATION_DECISION') {
    const decision = text(d.decision);
    const reason = Array.isArray(d.reasons) ? text(d.reasons[0]) : text(d.reason);
    return [payload || 'Continuation decision recorded', decision, reason]
      .filter(Boolean)
      .join(' · ');
  }
  if (type === 'CONTINUATION_ACTION' || type === 'CONTINUATION_ACTION_SKIPPED') {
    return [
      payload || titleCaseType(type),
      text(d.decision),
      text(d.selectedProvider),
      text(d.selectedModel),
      text(d.status),
    ]
      .filter(Boolean)
      .join(' · ');
  }
  if (type === 'COMPLETE') {
    const status = payload || text(d.status) || 'terminal';
    const reason = text(d.reason ?? d.message ?? d.summary);
    return ['Run complete', status, reason].filter(Boolean).join(' · ');
  }
  if (type === 'COST_UPDATE') return `Cost update: ${money(payload) || payload || 'updated'}`;

  const raw =
    d.message ??
    d.line ??
    d.payload ??
    d.summary ??
    d.relativePath ??
    d.status ??
    d.reason ??
    d.prompt;
  const body =
    raw != null && String(raw).length
      ? String(raw)
      : (ev.type || '').toLowerCase().replace(/_/g, ' ');
  return body.length > 280 ? `${body.slice(0, 280)}…` : body;
}

export function logMeta(ev: api.RunLogEvent): string[] {
  const d = eventData(ev);
  const type = (ev.type || '').toUpperCase();
  const out = new Set<string>();
  const add = (v: unknown) => {
    const s = text(v);
    if (s) out.add(s);
  };

  if (type === 'RUN_PROVIDER_SWITCHED') {
    const parts = providerSwitchParts(ev);
    const route = [titleCaseProvider(parts.from), titleCaseProvider(parts.to)]
      .filter(Boolean)
      .join(' → ');
    add(route);
    return [...out].slice(0, 5);
  }
  if (ev.taskId || d.taskId) add(`task ${shortId(d.taskId ?? ev.taskId)}`);
  if (type === 'TOKEN_USAGE') {
    add(text(d.totalTokens) ? `${d.totalTokens} tokens` : null);
    add(text(d.cacheReadTokens) ? `${d.cacheReadTokens} cache read` : null);
    add(money(d.costUsd));
    add(text(d.calls) ? `${d.calls} calls` : null);
  } else if (type === 'TASK_WAVE_PLAN') {
    add(text(d.executionMode));
    add(text(d.maxParallelTasks) ? `max ${d.maxParallelTasks} parallel` : null);
    if (Array.isArray(d.waves)) add(`${d.waves.length} waves`);
  } else if (type === 'TOOL_CALL') {
    add(text(d.callId) ? `call ${shortId(d.callId)}` : null);
    add(text(d.permissionKind));
  } else if (type.includes('CONTINUATION')) {
    add(text(d.decision));
    add(text(d.selectedProvider));
    add(text(d.selectedModel));
  }

  return [...out].slice(0, 5);
}
