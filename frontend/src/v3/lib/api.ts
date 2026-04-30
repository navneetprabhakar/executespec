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

// ── Referrals (R-162) ───────────────────────────────────────────────────────

export interface V3ReferralRecord {
  referrerUserId: string;
  refereeEmail: string;
  createdAt: string;
  status: 'PENDING' | 'ALREADY_EXISTS';
}

export interface V3InviteState {
  referrerUserId: string;
  pendingCount: number;
}

export const sendV3Referral = (email: string) =>
  v3Req<V3ReferralRecord>('/v3/referrals/invite', {
    method: 'POST',
    body: JSON.stringify({ email }),
  });

export const getV3InviteState = () => v3Req<V3InviteState>('/v3/referrals/state');

// ── Anonymous trial (R-111) ─────────────────────────────────────────────────

export interface AnonymousRunResponse {
  sessionToken: string | null;
  runId: string | null;
  status: string | null;
  message: string;
  pollUrl: string | null;
  error: string | null;
}

/** Submit an anonymous trial run from the v3 landing page. No JWT required.
 *  When the v3.anonymous-trial.real-dispatch flag is OFF (Wave 1 default),
 *  returns a stub — the UI flow is still testable end-to-end. */
export const submitAnonymousRun = async (spec: string): Promise<AnonymousRunResponse> => {
  const res = await fetch(BASE + '/v3/anonymous-runs', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ spec }),
  });
  // 202 + 429 + 4xx all return the same shape; treat 5xx as a hard error.
  if (res.status >= 500) throw new Error(`HTTP ${res.status}`);
  return res.json();
};

export const getAnonymousRunStatus = (sessionToken: string) =>
  fetch(BASE + '/v3/anonymous-runs/' + encodeURIComponent(sessionToken)).then(
    (r) => (r.status >= 500 ? Promise.reject(new Error(`HTTP ${r.status}`)) : r.json())
  ) as Promise<AnonymousRunResponse>;

// ── Selling-point landing endpoints (anonymized, public, no auth) ───────────
// Drive the public marketing surface: hero counter, sample-run gallery, and
// the public /exec-demo share page.

export interface V3LandingStats {
  specsThisWeek: number;
  verifiedScorePercent: number | null;
  totalRunsAllTime: number;
}

export const getV3LandingStats = () =>
  fetch(BASE + '//stats').then((r) => {
    if (!r.ok) throw new Error(`HTTP ${r.status}`);
    return r.json() as Promise<V3LandingStats>;
  });

export interface V3SampleRun {
  spec: string;
  verdict: 'READY' | 'NEEDS_REVIEW' | 'FAILED';
  verifiedScore: number | null;
  filesGenerated: number | null;
  durationMs: number | null;
  prOpened: boolean;
}

export const getV3SampleRuns = () =>
  fetch(BASE + '//sample-runs').then((r) => {
    if (!r.ok) throw new Error(`HTTP ${r.status}`);
    return r.json() as Promise<V3SampleRun[]>;
  });

export interface V3ExecSnapshot {
  windowDays: number;
  totalRuns: number;
  successRatePercent: number | null;
  totalSpendUsd: number | null;
  activeProjects: number;
  needsAttentionCount: number;
  spendByProject: { anonymousName: string; runs: number; spendUsd: number | null }[];
  topWins: { spec: string; verifiedScore: number | null; costUsd: number | null; when: string }[];
}

export const getV3ExecSnapshot = () =>
  fetch(BASE + '/v3/public/exec-snapshot').then((r) => {
    if (!r.ok) throw new Error(`HTTP ${r.status}`);
    return r.json() as Promise<V3ExecSnapshot>;
  });
