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

export function logMessage(ev: api.RunLogEvent): string {
  const d = eventData(ev);
  const type = (ev.type || '').toUpperCase();
  const payload = text(d.payload);
  const task = shortId(d.taskId ?? ev.taskId);

  if (type === 'RUN_STATUS') return `Run status: ${payload || text(d.status) || 'updated'}`;
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
  if (type === 'FILE_WRITTEN')
    return `File written: ${payload || text(d.relativePath) || 'workspace'}`;
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
  if (type === 'COMPLETE') return `Run complete: ${payload || text(d.status) || 'terminal'}`;
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
