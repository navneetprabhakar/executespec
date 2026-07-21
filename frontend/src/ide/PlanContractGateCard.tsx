// RR-819 — the breaking-change acknowledgement surface on the plan gate.
//
// This is deliberate friction, so it has to earn it: the approver is being asked to confirm they
// understand their change breaks declared downstream consumers, which means they must be able to
// see WHAT breaks — contract name, the path the plan touches, which repositories declared
// themselves consumers, and which plan steps are responsible. A typed acknowledgement over a bare
// confirmation box would be theatre.
import type {PlanContractGateRow} from '@/lib/api';
import {IdeBadge} from '@/ide/IdeKit';
import {REQUIRED_ACK_TEXT, gateHeadline, isAckSatisfied} from '@/ide/planContractGate';

function Row({row}: {row: PlanContractGateRow}) {
  const consumers = (row.declaredConsumers ?? []).filter(Boolean);
  const tasks = (row.matchedPlanTasks ?? []).filter(Boolean);
  return (
    <div
      style={{
        borderTop: '1px solid var(--ide-border)',
        paddingTop: 10,
        marginTop: 10,
        fontSize: 12.5,
        lineHeight: 1.6,
      }}
    >
      <div style={{display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap'}}>
        <strong style={{color: 'var(--ide-fg-bright)'}}>{row.contractName || 'Contract'}</strong>
        {row.type && <IdeBadge kind='warn'>{row.type}</IdeBadge>}
      </div>
      {row.detectedPath && (
        <div className='ide-mono' style={{color: 'var(--ide-fg-muted)', wordBreak: 'break-all'}}>
          {row.detectedPath}
        </div>
      )}
      <div style={{marginTop: 6}}>
        <span style={{color: 'var(--ide-fg-muted)'}}>Declared consumers: </span>
        {consumers.length > 0 ? (
          <span className='ide-mono'>{consumers.join(', ')}</span>
        ) : (
          <span style={{color: 'var(--ide-fg-muted)'}}>none declared</span>
        )}
      </div>
      {tasks.length > 0 && (
        <div>
          <span style={{color: 'var(--ide-fg-muted)'}}>Changed by step: </span>
          <span className='ide-mono'>{tasks.join(', ')}</span>
        </div>
      )}
    </div>
  );
}

/**
 * Renders the gated contracts and collects the typed acknowledgement.
 *
 * Rendering is unconditional once a gate exists for this run: the caller only mounts this when the
 * server said a typed acknowledgement is required, and the input is the only way to enable approve.
 */
export function PlanContractGateCard({
  rows,
  typed,
  onTypedChange,
  disabled,
}: {
  rows: PlanContractGateRow[];
  typed: string;
  onTypedChange: (value: string) => void;
  disabled?: boolean;
}) {
  const satisfied = isAckSatisfied(typed);
  const mistyped = typed.trim().length > 0 && !satisfied;
  return (
    <div
      className='ide-card'
      style={{borderColor: 'var(--ide-red)', marginTop: 12}}
      data-testid='plan-contract-gate'
    >
      <div style={{display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6}}>
        <strong style={{color: 'var(--ide-red)'}}>Breaking change</strong>
        <IdeBadge kind='bad'>Acknowledgement required</IdeBadge>
      </div>
      <div style={{fontSize: 12.5, lineHeight: 1.6, color: 'var(--ide-fg-muted)'}}>
        {gateHeadline(rows)} Approving starts work that will change these contracts. Review what
        breaks before you acknowledge.
      </div>

      {rows.map((row, i) => (
        <Row key={row.contractId || row.detectedPath || i} row={row} />
      ))}

      <label
        style={{
          display: 'block',
          marginTop: 14,
          fontSize: 12.5,
          color: 'var(--ide-fg)',
        }}
      >
        Type <strong className='ide-mono'>{REQUIRED_ACK_TEXT}</strong> to confirm you understand
        these consumers will break:
        <input
          className='ide-input ide-mono'
          style={{marginTop: 6, width: '100%'}}
          value={typed}
          disabled={disabled}
          spellCheck={false}
          autoComplete='off'
          aria-label={`Type ${REQUIRED_ACK_TEXT} to acknowledge the breaking change`}
          data-testid='breaking-ack-input'
          onChange={(e) => onTypedChange(e.target.value)}
        />
      </label>
      {mistyped && (
        <div style={{color: 'var(--ide-red)', fontSize: 12, marginTop: 6}}>
          Type {REQUIRED_ACK_TEXT} exactly, in capitals.
        </div>
      )}
    </div>
  );
}

export default PlanContractGateCard;
