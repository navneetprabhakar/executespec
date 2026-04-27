/**
 * R-142 — v3-specific API helpers.
 *
 * Anything reachable only under /api/v3 lives here so v2's lib/api.ts
 * stays clean for the cohabitation period. v3 components can also
 * reuse v2 helpers (getProjects, getRun, etc.) — those live in v2's
 * lib/api.ts unchanged.
 */

const BASE = '/api';

function authHeader(): Record<string, string> {
  const token = localStorage.getItem('access_token');
  return token ? { Authorization: `Bearer ${token}` } : {};
}

async function v3Req<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(BASE + path, {
    ...init,
    headers: {
      ...(init?.headers ?? {}),
      ...authHeader(),
      'Content-Type': 'application/json',
    },
  });
  if (!res.ok) {
    let body: unknown = null;
    try {
      body = await res.json();
    } catch {
      body = await res.text().catch(() => null);
    }
    const msg =
      typeof body === 'object' && body && 'message' in body
        ? String((body as { message: unknown }).message)
        : `HTTP ${res.status}`;
    throw new Error(msg);
  }
  if (res.status === 204) return undefined as T;
  return res.json();
}

// ── Entitlements ────────────────────────────────────────────────────────────

export interface V3Entitlements {
  tierKey: string;
  displayName: string;
  isUnlimited: boolean;
  runLimitMonthly: number;
  projectsLimit: number;
  storageLimitMb: number;
  softCostBudgetUsd: number | null;
  hardCostCapUsd: number | null;
  currentPeriodRunCount: number;
  currentPeriodCostUsd: number | null;
  billingCycleAnchor: string | null;
  lifetimeRunCreditBalance: number;
  lifetimeRunCreditCap: number;
}

/** R-142 — single-source entitlements read for the current user. */
export const getV3Entitlements = () =>
  v3Req<V3Entitlements>('/v3/entitlements');
