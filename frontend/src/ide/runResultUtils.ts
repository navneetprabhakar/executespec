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
  if (t.includes('CONTEXT')) return '🧭';
  if (t.includes('RECOVERY')) return '♻️';
  if (t.includes('STATUS')) return '●';
  return '·';
}

export function isNoisyLogEvent(ev: api.RunLogEvent): boolean {
  return (ev.type || '').toUpperCase().includes('RUN_DETAIL');
}

export function logMessage(ev: api.RunLogEvent): string {
  const d = (ev.data && typeof ev.data === 'object' ? ev.data : ev) as Record<string, unknown>;
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
