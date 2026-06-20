import {describe, expect, it} from 'vitest';
import {
  formatManagedAiPlanMessage,
  formatPlanUsd,
  isPlanExecutingStatus,
  isPlanTerminalStatus,
  managedAiBadgeKind,
  planStatusPhrase,
} from './planUtils';

describe('planUtils', () => {
  it('formats optional USD amounts for plan cards', () => {
    expect(formatPlanUsd(null)).toBe('—');
    expect(formatPlanUsd(undefined)).toBe('—');
    expect(formatPlanUsd(1.234)).toBe('$1.23');
  });

  it('classifies statuses for auto-routing and human status copy', () => {
    expect(isPlanExecutingStatus('running')).toBe(true);
    expect(isPlanExecutingStatus('awaiting_approval')).toBe(false);
    expect(isPlanTerminalStatus('complete')).toBe(true);
    expect(isPlanTerminalStatus('planning')).toBe(false);

    expect(planStatusPhrase('COMPLETE')).toBe('finished — see the result page');
    expect(planStatusPhrase('REVIEWING')).toBe('in review');
    expect(planStatusPhrase('EXECUTING')).toBe('executing');
    expect(planStatusPhrase('PLANNING')).toBe('being planned');
    expect(planStatusPhrase('PROVIDER_SWITCHING')).toBe('in progress');
  });

  it('maps managed-AI routing state to badge severity', () => {
    expect(managedAiBadgeKind('FULL')).toBe('ok');
    expect(managedAiBadgeKind('VALUE_MODE')).toBe('warn');
    expect(managedAiBadgeKind('FRONTIER_LOCKED')).toBe('warn');
    expect(managedAiBadgeKind('CHEAP_ONLY')).toBe('warn');
    expect(managedAiBadgeKind('EXHAUSTED')).toBe('bad');
  });

  it('builds the managed-AI plan card copy from balance fields', () => {
    expect(
      formatManagedAiPlanMessage({
        aiCreditsRemaining: 1200,
        frontierRawCapUsd: 4,
        cheapOnlyAfterRawUsd: 12,
      }),
    ).toBe(
      '1200 ES AI credits left this cycle. Approval charges run credits now; managed-AI credits debit per successful model call while work executes. Frontier calls can step down automatically after $4.00 raw frontier spend or $12.00 total raw spend.',
    );
  });
});
