// RR-819 — the breaking-change acknowledgement surface on the plan gate.
//
// This is deliberate friction, so it has to earn it: the approver is being asked to confirm they
// understand their change breaks declared downstream consumers, which means they must be able to
// see WHAT breaks — contract name, the path the plan touches, which repositories declared
// themselves consumers, and which plan steps are responsible. A typed acknowledgement over a bare
// confirmation box would be theatre.
import type {PlanContractGateRow} from '@/lib/api';
import {IdeBadge} from '@/ide/IdeKit';
import {
  REQUIRED_ACK_TEXT,
  gateHeadline,
  isAckSatisfied,
  type BrownfieldHttpRouteRemoval,
} from '@/ide/planContractGate';

function Row({row}: {row: PlanContractGateRow}) {
  const consumers = (row.declaredConsumers ?? []).filter(Boolean);
  const tasks = (row.matchedPlanTasks ?? []).filter(Boolean);
  return (
    <div
      style={{
        borderTop: '1px solid var(--ide-border)',
        paddingTop: 10,
        marginTop: 10,
        fontSize: 'var(--ide-t-caption)',
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
  brownfieldHttpRouteRemovals,
  typed,
  onTypedChange,
  disabled,
}: {
  rows: PlanContractGateRow[];
  brownfieldHttpRouteRemovals: BrownfieldHttpRouteRemoval[];
  typed: string;
  onTypedChange: (value: string) => void;
  disabled?: boolean;
}) {
  const satisfied = isAckSatisfied(typed);
  const mistyped = typed.trim().length > 0 && !satisfied;
  return (
    <div
      className='ide-card rl-note is-failed'
      style={{marginTop: 12}}
      data-testid='plan-contract-gate'
    >
      {/* State is the 3px leading edge plus the red heading and the bad badge, not a full red
          outline around the box. The friction is unmissable without repainting the card. */}
      <div
        style={{display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap', marginBottom: 6}}
      >
        <strong style={{color: 'var(--ide-red)'}}>Breaking change</strong>
        <IdeBadge kind='bad'>Acknowledgement required</IdeBadge>
      </div>
      <div
        style={{fontSize: 'var(--ide-t-caption)', lineHeight: 1.6, color: 'var(--ide-fg-muted)'}}
      >
        {rows.length > 0 && `${gateHeadline(rows)} `}
        {brownfieldHttpRouteRemovals.length > 0 &&
          `This plan explicitly removes ${brownfieldHttpRouteRemovals.length} protected HTTP ${
            brownfieldHttpRouteRemovals.length === 1 ? 'route' : 'routes'
          }. `}
        Approving starts work that can break existing callers — review the exact change below.
      </div>

      {rows.map((row, i) => (
        <Row key={row.contractId || row.detectedPath || i} row={row} />
      ))}

      {brownfieldHttpRouteRemovals.map((route) => (
        <div
          key={`${route.method}:${route.path}`}
          className='ide-mono'
          style={{
            borderTop: '1px solid var(--ide-border)',
            paddingTop: 10,
            marginTop: 10,
            color: 'var(--ide-red)',
            fontSize: 'var(--ide-t-caption)',
          }}
        >
          Remove {route.method} {route.path}
        </div>
      ))}

      <label
        style={{
          display: 'block',
          marginTop: 14,
          fontSize: 'var(--ide-t-caption)',
          color: 'var(--ide-fg)',
        }}
      >
        Type <strong className='ide-mono'>{REQUIRED_ACK_TEXT}</strong> to confirm you understand
        this may break existing callers:
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
        <div style={{color: 'var(--ide-red)', fontSize: 'var(--ide-t-caption)', marginTop: 6}}>
          Type {REQUIRED_ACK_TEXT} exactly, in capitals.
        </div>
      )}
    </div>
  );
}

export default PlanContractGateCard;
