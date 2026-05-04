/**
 * Wave 2.1 — client-side analytics wrapper around posthog-js.
 *
 * Public API:
 *   - `initAnalytics()` — call once on app boot (before any other hooks).
 *   - `identify(userId, traits)` — call on login/restore success.
 *   - `reset()` — call on logout.
 *   - `page()` — call on route change.
 *   - `capture(event, props)` — ad-hoc event (most events fire server-side
 *     via EventTrackingService; use this only for pure-frontend events like
 *     "clicked upgrade" where the backend doesn't observe the action).
 *
 * Safe-by-default: when `VITE_POSTHOG_API_KEY` is unset (local dev), every
 * function is a no-op — no network calls, no errors.
 *
 * Plan: docs/plans/activation-instrumentation-plan.md §6.4.
 */

import posthog from 'posthog-js';

type AnalyticsTraits = Record<string, string | number | boolean | null | undefined>;
// posthog-js's narrower loaded-callback type is internal — use the
// top-level module shape which has the API we consume.
type PostHogClient = typeof posthog;

let initialized = false;
let client: PostHogClient | null = null;

// X-09 (UX audit, 2026-05-03) — user-facing analytics opt-out flag.
// Persisted to localStorage so the choice survives logout. When the flag is
// 'true' (the default for new users), every function in this module is a
// no-op even if the build-time API key is set, and no PostHog network
// calls happen. When the user later flips the toggle, callers re-init via
// `applyAnalyticsConsent()` to pick up the change without a page reload.
const CONSENT_KEY = 'es:analyticsOptOut';

function isOptedOut(): boolean {
  try {
    return localStorage.getItem(CONSENT_KEY) === 'true';
  } catch {
    return false;
  }
}

/** Read the persisted opt-out flag. Returns true when the user has explicitly opted out. */
export function isAnalyticsOptedOut(): boolean {
  return isOptedOut();
}

/**
 * Persist the opt-out flag and react immediately. When the user opts out,
 * we proactively call `posthog.opt_out_capturing()` if the client was
 * already initialised (drops the resident cookie/ID); when they opt back
 * in, the next initAnalytics() picks them up.
 */
export function setAnalyticsOptedOut(optedOut: boolean): void {
  try {
    localStorage.setItem(CONSENT_KEY, optedOut ? 'true' : 'false');
  } catch {
    /* no-op */
  }
  if (optedOut && client) {
    try {
      // posthog-js exposes opt_out_capturing on the global posthog instance.
      (client as any).opt_out_capturing?.();
    } catch {
      /* defensive — no-op if signature differs in a future SDK */
    }
  } else if (!optedOut && client) {
    try {
      (client as any).opt_in_capturing?.();
    } catch {
      /* no-op */
    }
  }
}

function apiKey(): string | undefined {
  // Set via VITE_POSTHOG_API_KEY in dev (.env) or injected at build time.
  const key = (import.meta as any).env?.VITE_POSTHOG_API_KEY;
  return key && typeof key === 'string' && key.length > 0 ? key : undefined;
}

function apiHost(): string {
  return (import.meta as any).env?.VITE_POSTHOG_HOST || 'https://us.i.posthog.com';
}

/** One-time initialization. Safe to call multiple times. */
export function initAnalytics(): void {
  if (initialized) return;
  initialized = true;
  const key = apiKey();
  if (!key) return;
  // X-09 — respect persisted opt-out before any network call.
  if (isOptedOut()) return;
  posthog.init(key, {
    api_host: apiHost(),
    // Server-side funnel events are authoritative — client-side capture is
    // supplementary (pageviews + ad-hoc UI events).
    capture_pageview: false,
    // Keep bundle lean; disable features we don't use.
    disable_session_recording: true,
    autocapture: false,
    persistence: 'localStorage',
    loaded: () => {
      /* client already assigned below */
    },
  });
  client = posthog;
}

/** Attach the authenticated user's id + traits to subsequent events. */
export function identify(userId: string, traits?: AnalyticsTraits): void {
  if (!client || !userId) return;
  client.identify(userId, traits || {});
}

/** Clear the identified user (call on logout). */
export function reset(): void {
  if (!client) return;
  client.reset();
}

/** Record a pageview. Called by the app's route-change hook. */
export function page(path?: string): void {
  if (!client) return;
  client.capture('$pageview', {$current_url: path || window.location.pathname});
}

/**
 * Fire a custom UI event. Only use for pure-client events (upgrade click,
 * tab switch). Funnel events (signup/project/spec/plan/run/pr) are fired
 * server-side by EventTrackingService — do not duplicate them here.
 */
export function capture(event: string, props?: AnalyticsTraits): void {
  if (!client) return;
  client.capture(event, props || {});
}

/**
 * Wave 2.1 — read utm_source / utm_campaign / ref from the current URL.
 * Used by RegisterPage to forward attribution to the backend signup DTO.
 * Returns undefined values rather than null so they serialize cleanly in
 * the RegisterRequest record.
 */
export function readSignupAttribution(): {
  signupSource?: string;
  utmSource?: string;
  utmCampaign?: string;
} {
  try {
    const params = new URLSearchParams(window.location.search);
    const out: Record<string, string> = {};
    const ref = params.get('ref') || params.get('referrer');
    const utm = params.get('utm_source');
    const utmCampaign = params.get('utm_campaign');
    if (ref) out.signupSource = ref.slice(0, 64);
    if (utm) out.utmSource = utm.slice(0, 64);
    if (utmCampaign) out.utmCampaign = utmCampaign.slice(0, 128);
    return out;
  } catch {
    return {};
  }
}
