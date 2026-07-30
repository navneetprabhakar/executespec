// RR-819 — client half of the plan-approval breaking-change gate.
//
// The server composes a durable gate report while a run parks at AWAITING_APPROVAL, and refuses
// approval with 409 unless the approver echoes back the literal text `BREAKING` bound to that
// exact report's digest. Nothing sent it, so a PRODUCTION project run whose plan touches a
// registered contract with declared consumers could not be approved from the web UI at all.
//
// These helpers are deliberately pure so the rules that matter — what is being acknowledged, which
// run it belongs to, and when approval is allowed — can be tested without a DOM.
import type {PlanContractGateReport, PlanContractGateRow} from '@/lib/api';

/** The exact text an approver must type. Mirrors `PlanContractGateService.REQUIRED_ACK_TEXT`. */
export const REQUIRED_ACK_TEXT = 'BREAKING';

/** A gate report normalised for rendering, with the rows already parsed out of `reportJson`. */
export interface PlanContractGateView {
  /** The run this report was fetched for. Never trust the report against a different run. */
  runId: string;
  digest: string;
  requiresTypedAck: boolean;
  rows: PlanContractGateRow[];
  brownfieldHttpRouteRemovals: BrownfieldHttpRouteRemoval[];
}

export interface BrownfieldHttpRouteRemoval {
  method: string;
  path: string;
}

interface ParsedGateReport {
  rows: PlanContractGateRow[];
  brownfieldHttpRouteRemovals: BrownfieldHttpRouteRemoval[];
}

function parseGateReport(reportJson?: string | null): ParsedGateReport {
  const empty: ParsedGateReport = {rows: [], brownfieldHttpRouteRemovals: []};
  if (!reportJson || !reportJson.trim()) return empty;
  try {
    const parsed: unknown = JSON.parse(reportJson);
    if (Array.isArray(parsed)) {
      return {
        rows: parsed.filter((row): row is PlanContractGateRow => !!row && typeof row === 'object'),
        brownfieldHttpRouteRemovals: [],
      };
    }
    if (!parsed || typeof parsed !== 'object') return empty;
    const envelope = parsed as {
      schemaVersion?: unknown;
      rows?: unknown;
      brownfieldHttpRouteRemovals?: unknown;
    };
    if (envelope.schemaVersion !== 1 || !Array.isArray(envelope.rows)) return empty;
    const rows = envelope.rows.filter(
      (row): row is PlanContractGateRow => !!row && typeof row === 'object',
    );
    const removals = Array.isArray(envelope.brownfieldHttpRouteRemovals)
      ? envelope.brownfieldHttpRouteRemovals.filter(
          (entry): entry is BrownfieldHttpRouteRemoval =>
            !!entry &&
            typeof entry === 'object' &&
            'method' in entry &&
            typeof entry.method === 'string' &&
            'path' in entry &&
            typeof entry.path === 'string',
        )
      : [];
    return {rows, brownfieldHttpRouteRemovals: removals};
  } catch {
    return empty;
  }
}

/**
 * Parse the server's `reportJson`. Historical reports are a serialized
 * `PlanContractGateRow[]`; schema v1 reports retain those rows under `rows` and separately bind
 * exact brownfield HTTP removals.
 *
 * Fails to an empty list rather than throwing: a report we cannot read must not crash the approval
 * page. It still gates — `requiresTypedAck` is a separate server-supplied field — so an unreadable
 * body degrades to "acknowledge with less detail", never to "approve with no friction".
 */
export function parseGateRows(reportJson?: string | null): PlanContractGateRow[] {
  return parseGateReport(reportJson).rows;
}

/**
 * Build the view model for a report, but ONLY when the report actually belongs to `runId`.
 *
 * This is the same identity discipline RR-1459 applied to the plan itself: the plan page is reused
 * across `/plan/:runId`, so a report fetched for run A must never be able to gate — or un-gate —
 * run B. A report with no digest is also refused: without the digest we cannot bind an
 * acknowledgement to it, and an unbindable acknowledgement would be refused by the server anyway.
 */
export function toGateView(
  runId: string,
  report: PlanContractGateReport | null | undefined,
): PlanContractGateView | null {
  if (!runId || !report) return null;
  const digest = (report.reportDigest || '').trim();
  if (!digest) return null;
  // The server echoes the run id on the row; when present it must match the route exactly.
  if (report.runId && report.runId !== runId) return null;
  const parsed = parseGateReport(report.reportJson);
  return {
    runId,
    digest,
    requiresTypedAck: report.requiresTypedAck === true,
    rows: parsed.rows,
    brownfieldHttpRouteRemovals: parsed.brownfieldHttpRouteRemovals,
  };
}

/** True when this run has a gate that demands a typed acknowledgement before approval. */
export function gateBlocksApproval(gate: PlanContractGateView | null): boolean {
  return !!gate && gate.requiresTypedAck;
}

/** True when the typed text is exactly the required literal (surrounding whitespace ignored). */
export function isAckSatisfied(typed: string): boolean {
  return typed.trim() === REQUIRED_ACK_TEXT;
}

/**
 * Whether the approve button may be enabled for this run.
 *
 * A run with no gate (the 204 case — the overwhelming majority) is unaffected: `gate` is null,
 * nothing is required, and approval carries no extra friction.
 */
export function canApprove(gate: PlanContractGateView | null, typed: string): boolean {
  return !gateBlocksApproval(gate) || isAckSatisfied(typed);
}

/**
 * The acknowledgement payload to send with an approval, or `undefined` when this run needs none.
 * The digest is always the one from the report that was actually fetched and displayed.
 */
export function ackPayload(
  gate: PlanContractGateView | null,
  typed: string,
): {ack: string; reportDigest: string} | undefined {
  if (!gateBlocksApproval(gate) || !gate) return undefined;
  return {ack: typed.trim(), reportDigest: gate.digest};
}

/** The two distinct breaking-change 409s, separated from every other approval conflict. */
export type ApprovalConflict = 'BREAKING_ACK_REQUIRED' | 'BREAKING_REPORT_STALE' | 'OTHER';

/**
 * Classify an approval failure so the two breaking-change 409s get different recoveries.
 *
 * They are genuinely different problems: one means "you have not acknowledged yet", the other means
 * "what you acknowledged is not what is on this run any more" — the second is only fixable by
 * reloading and re-reading the change.
 *
 * Order matters. Both server messages contain the phrase "breaking-change report", so the
 * missing-acknowledgement case must be recognised FIRST by its own unique instruction ("type
 * BREAKING"); only then does "report you acknowledged" identify the stale one. Neither
 * discriminator appears in the RR-1459 plan-fingerprint 409, which also says something is "not the
 * one currently on this run" but never mentions a report or the typed literal.
 */
export function classifyApprovalConflict(message: string | null | undefined): ApprovalConflict {
  const text = (message || '').toLowerCase();
  if (text.includes('type breaking')) return 'BREAKING_ACK_REQUIRED';
  if (text.includes('report you acknowledged')) return 'BREAKING_REPORT_STALE';
  return 'OTHER';
}

/** Message shown for each conflict class; the server's own text is kept for anything else. */
export function approvalConflictMessage(conflict: ApprovalConflict, serverMessage: string): string {
  switch (conflict) {
    case 'BREAKING_ACK_REQUIRED':
      return `This plan changes a contract other repositories consume. Type ${REQUIRED_ACK_TEXT} to acknowledge it, then approve.`;
    case 'BREAKING_REPORT_STALE':
      return 'The report you acknowledged is not the one on this run — reload the plan and review the change again before approving.';
    default:
      return serverMessage;
  }
}

/** Count of distinct repositories declared as consumers across every gated contract. */
export function distinctConsumerCount(rows: PlanContractGateRow[]): number {
  const seen = new Set<string>();
  for (const row of rows) {
    for (const consumer of row.declaredConsumers ?? []) {
      if (consumer) seen.add(consumer);
    }
  }
  return seen.size;
}

/** One-line summary of what the approver is being asked to acknowledge. */
export function gateHeadline(rows: PlanContractGateRow[]): string {
  const contracts = rows.length;
  const consumers = distinctConsumerCount(rows);
  const contractText = `${contracts} contract${contracts === 1 ? '' : 's'}`;
  const consumerText = `${consumers} declared consumer${consumers === 1 ? '' : 's'}`;
  return `This plan changes ${contractText} that ${consumers === 1 ? 'a repository has' : 'other repositories have'} declared themselves consumers of — ${consumerText}.`;
}
