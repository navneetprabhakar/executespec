import type * as api from '@/lib/api';
import {pct} from '@/ide/agentState';
import {hasUnlimitedAiCredits} from '@/ide/planUtils';
import {selectIsSuperAdmin, useAuthStore} from '@/stores/useAuthStore';

export function AgentManagedAiBalance({balance}: {balance: api.ManagedAiBalance}) {
  const isSuperAdmin = useAuthStore(selectIsSuperAdmin);
  const unlimited = hasUnlimitedAiCredits(balance);
  const totalCredits = balance.includedAiCredits + (balance.purchasedAiCredits ?? 0);
  const configured = unlimited || totalCredits > 0 || balance.rawCogsCapUsd > 0;
  return (
    <div className='agent-pop-balance'>
      <span>Managed AI balance</span>
      {unlimited ? (
        <>
          <strong>Unlimited AI credits</strong>
          <small>This tier is not blocked by managed-AI credit limits.</small>
        </>
      ) : configured ? (
        <>
          <strong>
            {balance.aiCreditsRemaining} of {totalCredits} credits left
          </strong>
          <div className='agent-meter'>
            <span style={{width: `${pct(balance.aiCreditsUsed, totalCredits)}%`}} />
          </div>
          {isSuperAdmin && (
            <small>
              Raw spend ${balance.rawCogsUsedUsd.toFixed(4)} of ${balance.rawCogsCapUsd.toFixed(4)}{' '}
              monthly cap.
            </small>
          )}
        </>
      ) : (
        <>
          <strong>Managed credits not configured</strong>
          <small>Runs use the configured provider keys and still require plan approval.</small>
        </>
      )}
    </div>
  );
}
