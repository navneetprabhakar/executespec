import type * as api from '@/lib/api';
import {RunsIcon} from '@/ide/IdeIcons';
import {TrustBoundaryPanel} from '@/ide/agentRuntimeContract';

/**
 * Approval choreography.
 *
 * State lives in the card's LEADING EDGE, not in a repaint: a pending decision
 * is a 3px warn stripe, the body keeps the ordinary card surface. Attention is a
 * calm in-place pulse on that stripe — nothing moves, nothing blocks, and it
 * settles under `prefers-reduced-motion`. The controls stay quiet ghosts because
 * an approval prompt is routine workflow, not an alarm; Reject reddens only when
 * it is actually aimed at.
 */

export function gateAge(gate: api.AgentGateDecisionCard): string {
  return relTime(gate.submittedAt);
}

export function relTime(iso?: string | null): string {
  if (!iso) return '';
  const then = Date.parse(iso);
  if (Number.isNaN(then)) return '';
  const mins = Math.floor((Date.now() - then) / 60000);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.floor(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  const days = Math.floor(hrs / 24);
  return `${days}d ago`;
}

// RR-1489 — the gate authorizes provider spend, so the card must show the USD
// estimate and the hard cap that bounds the run, not credits alone.
export function gateCostLabel(gate: api.AgentGateDecisionCard): string {
  const parts: string[] = [];
  if (gate.estimatedCostUsd != null) parts.push(`~$${gate.estimatedCostUsd.toFixed(2)}`);
  if (gate.creditsRequired != null) parts.push(`${gate.creditsRequired} credits`);
  if (gate.hardCapUsd != null) parts.push(`cap $${gate.hardCapUsd.toFixed(2)}`);
  return parts.length ? parts.join(' / ') : 'cost pending';
}

export function gateQueueMeta(
  gate: api.AgentGateDecisionCard,
  remoteSandboxEnabled: boolean,
): string {
  const project =
    gate.projectName || `Standalone ${remoteSandboxEnabled ? 'sandbox' : 'workspace'}`;
  const route = gate.provider || gate.model || 'default route';
  const age = gateAge(gate);
  return `${project} · ${route}${age ? ` · ${age}` : ''}`;
}

export function GateCard({
  gate,
  onApprove,
  onReject,
  onOpen,
  busy,
  remoteSandboxEnabled,
}: {
  gate: api.AgentGateDecisionCard;
  onApprove: (gate: api.AgentGateDecisionCard) => void;
  onReject: (gate: api.AgentGateDecisionCard) => void;
  onOpen: (runId: string) => void;
  busy: boolean;
  remoteSandboxEnabled: boolean;
}) {
  return (
    <div className='agent-card agent-gate-card is-pending'>
      <div className='agent-card-head'>
        <RunsIcon size={16} />
        <strong>
          {remoteSandboxEnabled ? 'Sandbox' : 'Remote workspace'} plan awaiting approval
        </strong>
        {gate.risk && <span className='agent-card-meta'>risk {gate.risk.toLowerCase()}</span>}
      </div>
      <button className='agent-card-title' onClick={() => onOpen(gate.runId)}>
        {gate.title || gate.summary || gate.runId}
      </button>
      <div className='agent-card-consequence-row'>
        <span>{gate.projectName || 'standalone run'}</span>
        {gate.tasks.length > 0 && <span>{gate.tasks.length} tasks</span>}
        {/* RR-1489 — render the USD estimate and the hard cap that bound the spend. */}
        {gate.estimatedCostUsd != null && <span>~${gate.estimatedCostUsd.toFixed(2)}</span>}
        {gate.creditsRequired != null && <span>~{gate.creditsRequired} credits</span>}
        {gate.hardCapUsd != null && <span>cap ${gate.hardCapUsd.toFixed(2)}</span>}
        {gate.provider && <span>{gate.provider}</span>}
        {gateAge(gate) && <span>{gateAge(gate)}</span>}
        <span>PR only after publication gates pass</span>
      </div>
      {gate.creditsRequired == null && (
        <div className='agent-card-sub' role='status'>
          Cost estimate pending — approval unavailable until the run credit count is known.
        </div>
      )}
      {gate.summary && <div className='agent-card-sub'>{gate.summary}</div>}
      {gate.tasks.length > 0 && (
        <ul className='agent-card-tasks'>
          {gate.tasks.slice(0, 4).map((task) => (
            <li key={task}>{task}</li>
          ))}
        </ul>
      )}
      <TrustBoundaryPanel compact />
      <div className='agent-card-actions'>
        <button
          className='ide-btn ghost'
          onClick={() => onApprove(gate)}
          // RR-1489 — cost must be known before spend is authorized.
          disabled={busy || gate.creditsRequired == null}
        >
          Review &amp; approve
        </button>
        <button className='ide-btn danger' onClick={() => onReject(gate)} disabled={busy}>
          Reject
        </button>
        <button className='ide-btn ghost' onClick={() => onOpen(gate.runId)} disabled={busy}>
          Open full plan
        </button>
      </div>
    </div>
  );
}

/**
 * The drawer's approval queue row. Same card anatomy and same quiet controls as
 * the in-transcript gate — an approval must not look like two different objects
 * depending on where the operator meets it.
 */
export function GateQueueRow({
  gate,
  remoteSandboxEnabled,
  onApprove,
  onReject,
  onOpen,
}: {
  gate: api.AgentGateDecisionCard;
  remoteSandboxEnabled: boolean;
  onApprove: (gate: api.AgentGateDecisionCard) => void;
  onReject: (gate: api.AgentGateDecisionCard) => void;
  onOpen: (runId: string) => void;
}) {
  return (
    <div className='agent-drawer-gate is-pending'>
      <span className='agent-drawer-title'>{gate.title || gate.summary || gate.runId}</span>
      <span className='agent-drawer-meta'>{gateQueueMeta(gate, remoteSandboxEnabled)}</span>
      <span className='agent-drawer-riskline'>
        <b>{(gate.risk || 'LOW').toLowerCase()} risk</b>
        <span>{gate.tasks.length || 'n/a'} tasks</span>
        <span>{gateCostLabel(gate)}</span>
      </span>
      <span className='agent-drawer-actions'>
        <button
          className='ide-btn ghost'
          onClick={() => onApprove(gate)}
          // RR-1489 — no one-click approval while cost is unknown; a null
          // credit value also disables the server's stale-approval guard.
          disabled={gate.creditsRequired == null}
        >
          Approve &amp; run
        </button>
        <button className='ide-btn danger' onClick={() => onReject(gate)}>
          Reject
        </button>
        <button className='ide-btn ghost' onClick={() => onOpen(gate.runId)}>
          Full plan
        </button>
      </span>
    </div>
  );
}
