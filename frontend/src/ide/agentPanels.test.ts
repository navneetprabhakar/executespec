import {describe, expect, it, vi} from 'vitest';
import {boundedInlineDiff} from './agentDiff';
// agentPanels imports the auth store, whose module init reads localStorage (not
// provided in this environment). Mock it so importing the pure gateCostLabel works.
vi.mock('@/stores/useAuthStore', () => ({
  useAuthStore: () => false,
  selectIsSuperAdmin: () => false,
}));
import {gateCostLabel} from './agentPanels';
import type {AgentGateDecisionCard} from '@/lib/api/agent';

function gate(over: Partial<AgentGateDecisionCard>): AgentGateDecisionCard {
  return {
    runId: 'r1',
    projectId: null,
    projectName: null,
    title: null,
    status: 'AWAITING_APPROVAL',
    submittedAt: null,
    provider: null,
    model: null,
    estimatedCostUsd: null,
    hardCapUsd: null,
    creditsRequired: null,
    risk: null,
    summary: null,
    recommendedAction: null,
    tasks: [],
    ...over,
  };
}

describe('gateCostLabel (RR-1489)', () => {
  it('renders the USD estimate and the hard cap alongside credits', () => {
    const label = gateCostLabel(gate({estimatedCostUsd: 1.5, creditsRequired: 2, hardCapUsd: 8}));
    expect(label).toBe('~$1.50 / 2 credits / cap $8.00');
  });

  it('falls back to "cost pending" when nothing is known', () => {
    expect(gateCostLabel(gate({}))).toBe('cost pending');
  });
});

describe('boundedInlineDiff', () => {
  it('renders added files as additions without mutation affordances', () => {
    expect(
      boundedInlineDiff({path: 'src/a.ts', status: 'ADDED', base: '', head: 'one\ntwo'}),
    ).toEqual({
      lines: [
        {sign: '+', text: 'one'},
        {sign: '+', text: 'two'},
      ],
      truncated: false,
      note: '',
    });
  });

  it('bounds a large diff before it reaches the chat viewer', () => {
    const head = Array.from({length: 3_100}, (_, index) => `line-${index}`).join('\n');
    const result = boundedInlineDiff({path: 'large.ts', status: 'ADDED', base: '', head});
    expect(result.lines).toHaveLength(2_500);
    expect(result.truncated).toBe(true);
  });

  // RR-1494 — a change past the 3,000-line source clip is excluded from the diff,
  // so truncation must be reported from the source bound even when the visible diff
  // is well under the 2,500-line output cap.
  it('reports truncation when the source is line-clipped before diffing', () => {
    const base = Array.from({length: 5_000}, (_, index) => `line-${index}`).join('\n');
    // Change at line 4,000, past the 3,000-line source clip: the visible diff is tiny.
    const headArr = Array.from({length: 5_000}, (_, index) => `line-${index}`);
    headArr[4_000] = 'changed-4000';
    const result = boundedInlineDiff({
      path: 'huge.ts',
      status: 'MODIFIED',
      base,
      head: headArr.join('\n'),
    });
    // The change at line 4,000 is past the 3,000-line source clip, so it never
    // reaches the diff — truncation must be reported from the source bound.
    expect(result.lines.some((l) => l.text === 'changed-4000')).toBe(false);
    expect(result.truncated).toBe(true);
    expect(result.note).toContain('3,000 lines');
  });

  // RR-1494 — the character cap can trip before the line cap.
  it('reports truncation when the source is char-clipped before diffing', () => {
    const head = 'x'.repeat(130_000);
    const result = boundedInlineDiff({path: 'big.ts', status: 'MODIFIED', base: '', head});
    expect(result.truncated).toBe(true);
    expect(result.note).toContain('characters');
  });
});
