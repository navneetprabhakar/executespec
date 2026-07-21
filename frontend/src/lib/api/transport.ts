// Typed API client — all backend endpoints

export const BASE = '/api';

/** Returns the stored access token for authenticated requests. */
export function authHeader(): Record<string, string> {
  const token = localStorage.getItem('access_token');
  return token ? {Authorization: `Bearer ${token}`} : {};
}

/** Status code → human-readable fallback messages */
export const STATUS_MESSAGES: Record<number, string> = {
  400: 'Invalid request. Please check your input and try again.',
  401: 'Your session has expired. Please log in again.',
  402: "You've reached your plan limit. Please upgrade or purchase additional runs.",
  403: "You don't have permission to perform this action.",
  404: 'The requested resource was not found.',
  409: 'This action conflicts with the current state. Please refresh and try again.',
  410: 'This feature is not available in the current harness. It may be coming back — reach out if you need it.',
  422: 'The request could not be processed. Please check your input.',
  429: 'Too many requests. Please wait a moment and try again.',
  500: 'Something went wrong on our end. Please try again later.',
  502: 'The server is temporarily unavailable. Please try again shortly.',
  503: 'The service is temporarily unavailable. Please try again shortly.',
};

/**
 * R-311 (Wave 7) — typed API error so consumers can branch on status.
 *
 * The pre-existing pattern threw `new Error(friendlyMessage)` and lost
 * the HTTP status. AuditPage / AccessDenied both need to render a
 * specific "requires admin role" component on 403 instead of the
 * generic toast. This subclass preserves the status while keeping the
 * existing `errMsg(e)` shape for every existing call site.
 */
export class ApiError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

/**
 * Extracts a human-readable error message from a backend response.
 * Tries to parse JSON body for 'message' or 'error' fields,
 * falls back to known status messages, then to the raw body.
 */
export function parseErrorMessage(status: number, raw: string): string {
  // Try to extract message from JSON body (Spring Boot error format)
  if (raw) {
    try {
      const json = JSON.parse(raw);
      // Spring's BillingException / ResponseStatusException include 'message'
      if (json.message && typeof json.message === 'string') return json.message;
      // Some endpoints return { error: "..." }
      if (
        json.error &&
        typeof json.error === 'string' &&
        json.error !== 'Payment Required' &&
        json.error !== 'Forbidden'
      )
        return json.error;
    } catch {
      // Not JSON — use raw text if it's short and readable
      if (raw.length < 200 && !raw.startsWith('<') && !raw.startsWith('{')) return raw;
    }
  }
  // Fall back to status-based friendly message
  return STATUS_MESSAGES[status] ?? `Request failed (${status}). Please try again.`;
}

let refreshInFlight: Promise<boolean> | null = null;

function shouldAttemptRefresh(url: string): boolean {
  return (
    !!localStorage.getItem('access_token') &&
    !url.startsWith('/auth/login') &&
    !url.startsWith('/auth/register') &&
    !url.startsWith('/auth/refresh') &&
    !url.startsWith('/auth/logout')
  );
}

async function refreshAccessTokenOnce(): Promise<boolean> {
  if (!refreshInFlight) {
    refreshInFlight = fetch(BASE + '/auth/refresh', {
      method: 'POST',
      credentials: 'include',
      headers: {'Content-Type': 'application/json'},
    })
      .then(async (res) => {
        if (!res.ok) return false;
        const text = await res.text();
        if (!text.trim()) return false;
        const json = JSON.parse(text) as {accessToken?: string};
        if (!json.accessToken) return false;
        localStorage.setItem('access_token', json.accessToken);
        return true;
      })
      .catch(() => false)
      .finally(() => {
        refreshInFlight = null;
      });
  }
  return refreshInFlight;
}

/**
 * The refresh token is also dead, so this session cannot be recovered.
 *
 * Without this the app soft-bricks: `isAuthenticated` is derived from localStorage, so leaving the
 * stale access_token in place keeps ProtectedRoute satisfied while every request 401s. The user is
 * told "Please log in again" by an error banner but the app refuses to take them anywhere, and only
 * a manual reload recovers.
 *
 * Clear the dead token and bounce to /login, preserving the current path in `?redirect=` so the
 * login page returns the user to where they were (it already honours that parameter).
 */
function endDeadSession(): void {
  if (typeof window === 'undefined') return;
  if (!localStorage.getItem('access_token')) return; // already handled by a concurrent request
  localStorage.removeItem('access_token');
  const here = window.location.pathname + window.location.search + window.location.hash;
  const onAuthPage = window.location.pathname.startsWith('/login');
  window.location.replace(onAuthPage ? '/login' : `/login?redirect=${encodeURIComponent(here)}`);
}

async function fetchJson(url: string, opts?: RequestInit): Promise<Response> {
  // Audit r5 (P0-2, 2026-05-06): a backend OOM crash during the new-user
  // walkthrough surfaced as `fetch()` rejecting with `TypeError: Failed
  // to fetch`. The catch site (RegisterPage / LoginPage / etc.) printed
  // a generic "Registration failed" instead of the actual root cause —
  // the user couldn't tell whether the product was broken or if they'd
  // mistyped. Wrap the fetch call so network errors surface as a real
  // ApiError with `status: 0` (a sentinel callers can branch on for
  // retry UI). Genuine 4xx/5xx responses still reach the original status
  // path.
  let res: Response;
  try {
    const headers = {'Content-Type': 'application/json', ...authHeader(), ...opts?.headers};
    res = await fetch(BASE + url, {
      ...opts,
      headers,
      credentials: 'include', // send refresh_token cookie
    });
  } catch (e) {
    const reason = e instanceof Error ? e.message : 'unknown';
    throw new ApiError(
      0,
      `Can't reach the server (${reason}). Check your connection and try again.`,
    );
  }
  return res;
}

async function fetchText(url: string): Promise<Response> {
  try {
    return await fetch(BASE + url, {
      headers: {...authHeader()},
      credentials: 'include',
    });
  } catch (e) {
    const reason = e instanceof Error ? e.message : 'unknown';
    throw new ApiError(
      0,
      `Can't reach the server (${reason}). Check your connection and try again.`,
    );
  }
}

export async function req<T>(url: string, opts?: RequestInit): Promise<T> {
  let res = await fetchJson(url, opts);
  if (res.status === 401 && shouldAttemptRefresh(url)) {
    if (await refreshAccessTokenOnce()) {
      res = await fetchJson(url, opts);
    } else {
      endDeadSession();
    }
  }
  if (!res.ok) {
    const raw = await res.text().catch(() => '');
    throw new ApiError(res.status, parseErrorMessage(res.status, raw));
  }
  if (res.status === 204) return undefined as unknown as T;
  // Handle Spring returning 200 OK with an empty body (e.g. approve/merge endpoints)
  const text = await res.text();
  if (!text.trim()) return undefined as unknown as T;
  return JSON.parse(text);
}

/**
 * Retryable HTTP statuses — callers can use this to decide whether to
 * surface a "Retry" affordance instead of asking the user to reload the
 * page. Audit r5 (P0-2).
 */
export function isRetryableError(e: unknown): boolean {
  if (!(e instanceof ApiError)) return false;
  // 0 = network error (backend unreachable). 5xx = transient server error.
  return e.status === 0 || e.status >= 500;
}

/** Fetch a plain-text endpoint (e.g. file content served as text/plain). */
export async function reqText(url: string): Promise<string> {
  let res = await fetchText(url);
  if (res.status === 401 && shouldAttemptRefresh(url)) {
    if (await refreshAccessTokenOnce()) {
      res = await fetchText(url);
    } else {
      endDeadSession();
    }
  }
  if (!res.ok) {
    const raw = await res.text().catch(() => '');
    throw new ApiError(res.status, parseErrorMessage(res.status, raw));
  }
  return res.text();
}
