import type * as api from '@/lib/api';
import {RunsIcon} from '@/ide/IdeIcons';
import {cloudRunBlockingCopy} from '@/ide/agentActionPolicy';

/**
 * Rendered instead of a sandbox proposal card when the server (or the missing
 * project scope) says the proposal is not confirmable.
 *
 * Phase 4 (item 26/27): a non-executable proposal used to render the normal
 * confirm card, so the operator could click a button that would be dropped.
 * Showing the blocking reason and no confirm affordance is the honest state.
 */
export function AgentBlockedActionCard({
  action,
  onDismiss,
  busy,
}: {
  action: api.AgentTypedAction;
  onDismiss: () => void;
  busy: boolean;
}) {
  return (
    <div className='agent-card'>
      <div className='agent-card-head'>
        <RunsIcon size={16} />
        <strong>Sandbox run not ready</strong>
      </div>
      <div className='agent-card-sub'>{cloudRunBlockingCopy(action)}</div>
      <div className='agent-card-actions'>
        <button className='ide-btn ghost' onClick={onDismiss} disabled={busy}>
          Dismiss
        </button>
      </div>
    </div>
  );
}
