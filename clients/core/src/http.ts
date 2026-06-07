import type { ClientConfig } from './config.js';
import { ApiError, parseErrorBody } from './errors.js';
import type { TokenStore } from './auth.js';

export interface RequestOptions {
  /** Query params; undefined/null values are dropped. */
  query?: Record<string, string | number | boolean | undefined | null>;
  signal?: AbortSignal;
  /** Max retries on 429 / transient network error (default 4). */
  maxRetries?: number;
  /**
   * Extra request headers (e.g. an `Idempotency-Key`). Carried verbatim through the internal retry
   * loop, so a value generated once by the caller stays stable across every retry of this request.
   * Cannot override `Authorization` (the PAT bearer is always applied last).
   */
  headers?: Record<string, string>;
  /**
   * Per-attempt deadline in ms (default 60s; `0` disables). A half-open socket on a unary call would
   * otherwise hang forever — the retry loop never fires because a stalled `fetch` never rejects.
   * Not used by {@link HttpClient.stream} (a long-lived stream manages its own lifetime).
   */
  timeoutMs?: number;
}

const TRANSIENT_STATUS = new Set([502, 503, 504]);
const DEFAULT_TIMEOUT_MS = 60_000;

/**
 * Thin fetch wrapper: attaches the PAT as `Authorization: Bearer`, parses the server's error
 * envelope, and honors `429 Retry-After` (and transient 5xx / network blips) with bounded
 * exponential backoff. No cookies, no refresh dance — the PAT is the whole credential.
 */
export class HttpClient {
  constructor(
    private readonly cfg: ClientConfig,
    private readonly tokens: TokenStore,
  ) {}

  get apiBase(): string {
    return this.cfg.apiBase;
  }

  /** Web origin (no `/api`), e.g. `https://executespec.ai` — used to build the browser login URL. */
  get host(): string {
    return this.cfg.host;
  }

  async get<T>(pathname: string, opts?: RequestOptions): Promise<T> {
    return this.request<T>('GET', pathname, undefined, opts);
  }

  async post<T>(pathname: string, body?: unknown, opts?: RequestOptions): Promise<T> {
    return this.request<T>('POST', pathname, body, opts);
  }

  async delete<T>(pathname: string, opts?: RequestOptions): Promise<T> {
    return this.request<T>('DELETE', pathname, undefined, opts);
  }

  /**
   * Authenticated streaming request — attaches the PAT bearer and returns the raw {@link Response}
   * so the caller can read `res.body` (SSE). Unlike {@link request} it neither parses the body nor
   * retries: a long-lived stream is the caller's to manage (a retry would re-POST the turn). Throws
   * only on an outright network failure; HTTP error statuses come back as a non-`ok` Response for
   * the caller to translate.
   */
  async stream(
    method: string,
    pathname: string,
    body?: unknown,
    opts?: { signal?: AbortSignal; accept?: string },
  ): Promise<Response> {
    const url = this.buildUrl(pathname);
    return fetch(url, {
      method,
      headers: {
        'Content-Type': 'application/json',
        Accept: opts?.accept ?? 'text/event-stream',
        ...(await this.authHeader()),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: opts?.signal,
    });
  }

  async request<T>(
    method: string,
    pathname: string,
    body?: unknown,
    opts?: RequestOptions,
  ): Promise<T> {
    const url = this.buildUrl(pathname, opts?.query);
    const maxRetries = opts?.maxRetries ?? 4;
    const timeoutMs = opts?.timeoutMs ?? DEFAULT_TIMEOUT_MS;

    for (let attempt = 0; ; attempt++) {
      let res: Response;
      try {
        res = await fetch(url, {
          method,
          headers: {
            'Content-Type': 'application/json',
            Accept: 'application/json',
            ...opts?.headers,
            ...(await this.authHeader()),
          },
          body: body === undefined ? undefined : JSON.stringify(body),
          signal: withTimeout(opts?.signal, timeoutMs),
        });
      } catch (e) {
        // Caller-initiated abort → propagate; never retry a cancellation.
        if (opts?.signal?.aborted) throw e;
        // Timeout or network/DNS blip (status 0). Retry a few times, then give up.
        if (attempt < maxRetries) {
          await sleep(backoffMs(attempt));
          continue;
        }
        const reason = e instanceof Error ? e.message : 'unknown';
        throw new ApiError(0, `Can't reach the server (${reason}).`);
      }

      if (res.ok) {
        return (await this.parseBody<T>(res)) as T;
      }

      const raw = await res.text().catch(() => '');
      const parsed = parseErrorBody(res.status, raw);

      const retriable = res.status === 429 || TRANSIENT_STATUS.has(res.status);
      if (retriable && attempt < maxRetries) {
        const waitMs = parsed.retryAfterSeconds
          ? parsed.retryAfterSeconds * 1000
          : backoffMs(attempt);
        await sleep(waitMs);
        continue;
      }
      throw new ApiError(res.status, parsed.message, parsed.retryAfterSeconds);
    }
  }

  private async parseBody<T>(res: Response): Promise<T | undefined> {
    if (res.status === 204) return undefined;
    const text = await res.text();
    if (!text.trim()) return undefined;
    return JSON.parse(text) as T;
  }

  private async authHeader(): Promise<Record<string, string>> {
    const token = await this.tokens.get();
    return token ? { Authorization: `Bearer ${token}` } : {};
  }

  private buildUrl(pathname: string, query?: RequestOptions['query']): string {
    const base = this.cfg.apiBase + (pathname.startsWith('/') ? pathname : `/${pathname}`);
    if (!query) return base;
    const params = new URLSearchParams();
    for (const [k, v] of Object.entries(query)) {
      if (v !== undefined && v !== null) params.set(k, String(v));
    }
    const qs = params.toString();
    return qs ? `${base}?${qs}` : base;
  }
}

/** Compose the caller's signal (if any) with a fresh per-attempt timeout. */
function withTimeout(caller: AbortSignal | undefined, timeoutMs: number): AbortSignal | undefined {
  const signals: AbortSignal[] = [];
  if (caller) signals.push(caller);
  if (timeoutMs > 0) signals.push(AbortSignal.timeout(timeoutMs));
  if (signals.length === 0) return undefined;
  return signals.length === 1 ? signals[0] : AbortSignal.any(signals);
}

function backoffMs(attempt: number): number {
  // 2s, 4s, 8s, 16s — matches the platform's own retry ethos, with light jitter.
  return Math.min(16000, 2000 * 2 ** attempt) + Math.floor(Math.random() * 250);
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
