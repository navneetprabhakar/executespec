import type * as api from '@/lib/api';
import type {DiffLine} from '@/ide/IdeKit';
import {boundedLineDiff, toLines} from '@/ide/runResultUtils';

const INLINE_DIFF_MAX_CHARS = 120_000;
const INLINE_DIFF_MAX_SOURCE_LINES = 3_000;
const INLINE_DIFF_MAX_LINES = 2_500;

/** Pure bounded diff shaping for the chat preview and contract tests. */
export function boundedInlineDiff(payload: api.FileDiffPayload): {
  lines: DiffLine[];
  truncated: boolean;
  note: string;
} {
  const rawBase = payload.base ?? '';
  const rawHead = payload.head ?? '';
  // RR-1494 — the source is clipped (by characters, then by lines) BEFORE the diff
  // runs, so a change past line 3,000 or past 120,000 chars is excluded from the
  // comparison entirely. Reporting `truncated` only from the post-diff line count
  // silently asserts a complete diff for a long file. Detect every clip bound.
  const charClipped =
    rawBase.length > INLINE_DIFF_MAX_CHARS || rawHead.length > INLINE_DIFF_MAX_CHARS;
  const base = rawBase.slice(0, INLINE_DIFF_MAX_CHARS);
  const head = rawHead.slice(0, INLINE_DIFF_MAX_CHARS);
  const rawBaseLines = toLines(base);
  const rawHeadLines = toLines(head);
  const lineClipped =
    rawBaseLines.length > INLINE_DIFF_MAX_SOURCE_LINES ||
    rawHeadLines.length > INLINE_DIFF_MAX_SOURCE_LINES;
  const baseLines = rawBaseLines.slice(0, INLINE_DIFF_MAX_SOURCE_LINES).join('\n');
  const headLines = rawHeadLines.slice(0, INLINE_DIFF_MAX_SOURCE_LINES).join('\n');
  const computed =
    payload.status === 'ADDED'
      ? {
          lines: toLines(headLines).map((text) => ({sign: '+' as const, text})),
          truncated: false,
          reason: '',
        }
      : boundedLineDiff(baseLines, headLines);
  const lines = computed.lines;
  const outputClipped = lines.length > INLINE_DIFF_MAX_LINES;
  const truncated = charClipped || lineClipped || computed.truncated || outputClipped;
  const note = !truncated
    ? ''
    : lineClipped
      ? `Source clipped at ${INLINE_DIFF_MAX_SOURCE_LINES.toLocaleString()} lines before diffing — changes past that line are not shown. Open Changed files for the full result.`
      : charClipped
        ? `Source clipped at ${INLINE_DIFF_MAX_CHARS.toLocaleString()} characters before diffing — later changes are not shown. Open Changed files for the full result.`
        : computed.truncated
          ? `${computed.reason} Open Changed files for the full result.`
          : `Diff truncated to ${INLINE_DIFF_MAX_LINES.toLocaleString()} lines. Open Changed files for the full result.`;
  return {
    lines: lines.slice(0, INLINE_DIFF_MAX_LINES),
    truncated,
    note,
  };
}
