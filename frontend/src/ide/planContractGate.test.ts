import {describe, expect, it} from 'vitest';

import {
  REQUIRED_ACK_TEXT,
  ackPayload,
  approvalConflictMessage,
  canApprove,
  classifyApprovalConflict,
  distinctConsumerCount,
  gateBlocksApproval,
  gateHeadline,
  isAckSatisfied,
  parseGateRows,
  toGateView,
} from './planContractGate';

const ROWS = [
  {
    contractId: 'c-1',
    contractName: 'Orders API',
    type: 'OPENAPI',
    detectedPath: 'api/orders.yaml',
    declaredConsumers: ['acme/billing', 'acme/reporting'],
    matchedPlanTasks: ['t-2'],
  },
  {
    contractId: 'c-2',
    contractName: 'Ledger schema',
    type: 'AVRO',
    detectedPath: 'schema/ledger.avsc',
    declaredConsumers: ['acme/billing'],
    matchedPlanTasks: ['t-3', 't-4'],
  },
];

function gatedReport(overrides: Record<string, unknown> = {}) {
  return {
    runId: 'run-a',
    reportJson: JSON.stringify(ROWS),
    reportDigest: 'DIGEST-A',
    requiresTypedAck: true,
    ...overrides,
  };
}

describe('planContractGate report parsing (RR-819)', () => {
  it('parses the serialized row array the server persists', () => {
    expect(parseGateRows(JSON.stringify(ROWS))).toHaveLength(2);
    expect(parseGateRows(JSON.stringify(ROWS))[0].contractName).toBe('Orders API');
  });

  it('degrades to no rows rather than throwing on an unreadable report body', () => {
    expect(parseGateRows('not json')).toEqual([]);
    expect(parseGateRows('{"not":"an array"}')).toEqual([]);
    expect(parseGateRows(null)).toEqual([]);
    expect(parseGateRows('')).toEqual([]);
  });

  it('still gates on an unreadable body, because requiresTypedAck is a separate server field', () => {
    const gate = toGateView('run-a', gatedReport({reportJson: 'not json'}));
    expect(gate?.rows).toEqual([]);
    expect(gateBlocksApproval(gate)).toBe(true);
  });
});

describe('planContractGate run identity (RR-819 / RR-1459 discipline)', () => {
  it('refuses a report that belongs to a different run than the route', () => {
    expect(toGateView('run-b', gatedReport({runId: 'run-a'}))).toBeNull();
  });

  it('accepts a report for the run it was fetched for', () => {
    const gate = toGateView('run-a', gatedReport());
    expect(gate?.runId).toBe('run-a');
    expect(gate?.digest).toBe('DIGEST-A');
    expect(gate?.rows).toHaveLength(2);
  });

  it('refuses a report with no digest — an unbindable acknowledgement is not an acknowledgement', () => {
    expect(toGateView('run-a', gatedReport({reportDigest: ''}))).toBeNull();
    expect(toGateView('run-a', gatedReport({reportDigest: null}))).toBeNull();
  });

  it('treats an absent report (the 204 case) as no gate at all', () => {
    expect(toGateView('run-a', undefined)).toBeNull();
    expect(toGateView('run-a', null)).toBeNull();
    expect(gateBlocksApproval(null)).toBe(false);
    expect(canApprove(null, '')).toBe(true);
  });

  it('does not gate when the server says no typed acknowledgement is required', () => {
    const gate = toGateView('run-a', gatedReport({requiresTypedAck: false}));
    expect(gateBlocksApproval(gate)).toBe(false);
    expect(canApprove(gate, '')).toBe(true);
    expect(ackPayload(gate, '')).toBeUndefined();
  });
});

describe('planContractGate typed acknowledgement (RR-819)', () => {
  const gate = toGateView('run-a', gatedReport());

  it('requires the exact literal BREAKING', () => {
    expect(REQUIRED_ACK_TEXT).toBe('BREAKING');
    expect(isAckSatisfied('BREAKING')).toBe(true);
    expect(isAckSatisfied('  BREAKING  ')).toBe(true);
    expect(isAckSatisfied('breaking')).toBe(false);
    expect(isAckSatisfied('Breaking')).toBe(false);
    expect(isAckSatisfied('BREAKING!')).toBe(false);
    expect(isAckSatisfied('BREAK')).toBe(false);
    expect(isAckSatisfied('')).toBe(false);
  });

  it('blocks approval until that exact literal is typed', () => {
    expect(canApprove(gate, '')).toBe(false);
    expect(canApprove(gate, 'breaking')).toBe(false);
    expect(canApprove(gate, 'yes')).toBe(false);
    expect(canApprove(gate, 'BREAKING')).toBe(true);
  });

  it('sends the digest of the report that was actually fetched', () => {
    expect(ackPayload(gate, ' BREAKING ')).toEqual({ack: 'BREAKING', reportDigest: 'DIGEST-A'});
  });
});

describe('planContractGate conflict classification (RR-819)', () => {
  const MISSING =
    'This plan changes a contract that other repositories consume. Review the breaking-change' +
    ' report and type BREAKING to acknowledge it before approving.';
  const STALE =
    'The breaking-change report you acknowledged is not the one currently on this run. Reload the' +
    ' plan and review the change again before approving.';
  const PLAN_FINGERPRINT =
    'The plan you reviewed is not the plan currently on this run. Reload the plan and review it' +
    ' again before approving.';

  it('separates the two breaking-change 409s from each other', () => {
    expect(classifyApprovalConflict(MISSING)).toBe('BREAKING_ACK_REQUIRED');
    expect(classifyApprovalConflict(STALE)).toBe('BREAKING_REPORT_STALE');
  });

  it('does not misread the RR-1459 plan-fingerprint 409 as a stale gate report', () => {
    expect(classifyApprovalConflict(PLAN_FINGERPRINT)).toBe('OTHER');
    expect(approvalConflictMessage('OTHER', PLAN_FINGERPRINT)).toBe(PLAN_FINGERPRINT);
  });

  it('gives each breaking-change conflict its own message', () => {
    const ackMessage = approvalConflictMessage('BREAKING_ACK_REQUIRED', MISSING);
    const staleMessage = approvalConflictMessage('BREAKING_REPORT_STALE', STALE);
    expect(ackMessage).toContain('BREAKING');
    expect(staleMessage).toContain('reload');
    expect(ackMessage).not.toBe(staleMessage);
  });

  it('classifies unrelated failures as OTHER and keeps the server text', () => {
    expect(classifyApprovalConflict('Credits mismatch: expected 3 but confirmed 9.')).toBe('OTHER');
    expect(classifyApprovalConflict(null)).toBe('OTHER');
  });
});

describe('planContractGate evidence summary (RR-819)', () => {
  it('counts distinct consumer repositories across contracts', () => {
    expect(distinctConsumerCount(ROWS)).toBe(2);
    expect(distinctConsumerCount([])).toBe(0);
  });

  it('says what is being acknowledged, not just that something is', () => {
    const headline = gateHeadline(ROWS);
    expect(headline).toContain('2 contracts');
    expect(headline).toContain('2 declared consumers');
  });
});
