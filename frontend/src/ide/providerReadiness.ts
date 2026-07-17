import type {ProviderReadiness, ProviderReadinessState} from '@/lib/api';

const AVAILABLE_STATES = new Set<ProviderReadinessState>([
  'AVAILABLE_MANAGED',
  'AVAILABLE_USER_KEY',
  'AVAILABLE_ORG_KEY',
]);

export function readinessByProvider(
  rows: ProviderReadiness[],
): ReadonlyMap<string, ProviderReadiness> {
  return new Map(rows.map((row) => [row.provider.toUpperCase(), row]));
}

export function providerIsDispatchable(row?: ProviderReadiness | null): boolean {
  return Boolean(row?.dispatchable && AVAILABLE_STATES.has(row.state));
}

export function providerReadinessLabel(row?: ProviderReadiness | null): string {
  if (!row) return 'Readiness has not been verified.';
  switch (row.state) {
    case 'AVAILABLE_MANAGED':
      return 'Ready · managed provider';
    case 'AVAILABLE_USER_KEY':
      return 'Ready · your provider key';
    case 'AVAILABLE_ORG_KEY':
      return 'Ready · organization provider key';
    case 'POLICY_BLOCKED':
      return 'Blocked by the effective organization provider policy.';
    case 'CIRCUIT_OPEN':
      return 'Temporarily unavailable because the provider circuit breaker is open.';
    case 'UNAVAILABLE':
      return 'Unavailable because no permitted provider credential is configured.';
    default:
      return 'Unavailable because provider readiness is unknown.';
  }
}

export function providerOptionLabel(label: string, row?: ProviderReadiness | null): string {
  if (!row) return `${label} · checking`;
  if (providerIsDispatchable(row)) return label;
  switch (row.state) {
    case 'POLICY_BLOCKED':
      return `${label} · policy blocked`;
    case 'CIRCUIT_OPEN':
      return `${label} · temporarily unavailable`;
    default:
      return `${label} · unavailable`;
  }
}
