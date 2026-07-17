import {describe, expect, it} from 'vitest';
import {
  providerIsDispatchable,
  providerIsProjectSelectable,
  providerOptionLabel,
  providerReadinessLabel,
  readinessByProvider,
} from './providerReadiness';
import type {ProviderReadiness} from '@/lib/api';

function row(state: ProviderReadiness['state'], dispatchable: boolean): ProviderReadiness {
  return {
    provider: 'deepseek',
    state,
    dispatchable,
    keySourceMode: 'HYBRID',
    reason: 'secret-free server reason',
  };
}

describe('provider readiness presentation', () => {
  it.each(['AVAILABLE_MANAGED', 'AVAILABLE_USER_KEY', 'AVAILABLE_ORG_KEY'] as const)(
    'enables exact available state %s',
    (state) => expect(providerIsDispatchable(row(state, true))).toBe(true),
  );

  it.each(['POLICY_BLOCKED', 'CIRCUIT_OPEN', 'UNAVAILABLE'] as const)(
    'disables exact unavailable state %s',
    (state) => expect(providerIsDispatchable(row(state, false))).toBe(false),
  );

  it('fails closed on a contradictory dispatchable response', () => {
    expect(providerIsDispatchable(row('POLICY_BLOCKED', true))).toBe(false);
  });

  it('keeps a credentialed circuit-open provider selectable as durable project policy', () => {
    expect(providerIsProjectSelectable(row('CIRCUIT_OPEN', false))).toBe(true);
    expect(providerIsDispatchable(row('CIRCUIT_OPEN', false))).toBe(false);
    expect(providerIsProjectSelectable(row('UNAVAILABLE', false))).toBe(false);
  });

  it('normalizes provider identifiers and gives actionable, secret-free labels', () => {
    const blocked = row('POLICY_BLOCKED', false);
    expect(readinessByProvider([blocked]).get('DEEPSEEK')).toBe(blocked);
    expect(providerOptionLabel('DeepSeek', blocked)).toBe('DeepSeek · policy blocked');
    expect(providerReadinessLabel(blocked)).toContain('organization provider policy');
    expect(providerReadinessLabel(row('CIRCUIT_OPEN', false))).toContain('circuit breaker');
    expect(providerReadinessLabel(row('UNAVAILABLE', false))).toContain('credential');
  });
});
