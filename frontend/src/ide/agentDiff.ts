import type * as api from '@/lib/api';
import type {DiffLine} from '@/ide/IdeKit';
import {lineDiff, toLines} from '@/ide/runResultUtils';

const INLINE_DIFF_MAX_CHARS = 120_000;
const INLINE_DIFF_MAX_SOURCE_LINES = 3_000;
const INLINE_DIFF_MAX_LINES = 2_500;

/** Pure bounded diff shaping for the chat preview and contract tests. */
export function boundedInlineDiff(payload: api.FileDiffPayload): {
  lines: DiffLine[];
  truncated: boolean;
} {
  const base = (payload.base ?? '').slice(0, INLINE_DIFF_MAX_CHARS);
  const head = (payload.head ?? '').slice(0, INLINE_DIFF_MAX_CHARS);
  const baseLines = toLines(base).slice(0, INLINE_DIFF_MAX_SOURCE_LINES).join('\n');
  const headLines = toLines(head).slice(0, INLINE_DIFF_MAX_SOURCE_LINES).join('\n');
  const lines =
    payload.status === 'ADDED'
      ? toLines(headLines).map((text) => ({sign: '+' as const, text}))
      : lineDiff(baseLines, headLines);
  return {
    lines: lines.slice(0, INLINE_DIFF_MAX_LINES),
    truncated: lines.length > INLINE_DIFF_MAX_LINES,
  };
}
