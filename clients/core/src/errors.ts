/**
 * Error raised for any non-2xx API response. Mirrors the backend's canonical 4xx/5xx envelope
 * (`{ timestamp, status, error, message, retryAfterSeconds }`) so callers get the server's own
 * human-readable message instead of a generic string. `status === 0` means the request never
 * reached the server (network/DNS failure).
 */
export class ApiError extends Error {
  readonly status: number;
  readonly retryAfterSeconds: number | undefined;

  constructor(status: number, message: string, retryAfterSeconds?: number) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.retryAfterSeconds = retryAfterSeconds;
  }

  get isRateLimited(): boolean {
    return this.status === 429;
  }

  get isAuthError(): boolean {
    return this.status === 401 || this.status === 403;
  }
}

export interface ParsedError {
  message: string;
  retryAfterSeconds: number | undefined;
}

/** Best-effort parse of an error body into a human-readable message + optional retry hint. */
export function parseErrorBody(status: number, raw: string): ParsedError {
  if (raw && raw.trim()) {
    try {
      const j = JSON.parse(raw) as { message?: unknown; retryAfterSeconds?: unknown };
      const message = typeof j.message === 'string' && j.message.trim() ? j.message : `HTTP ${status}`;
      const retryAfterSeconds =
        typeof j.retryAfterSeconds === 'number' ? j.retryAfterSeconds : undefined;
      return { message, retryAfterSeconds };
    } catch {
      // not JSON — fall through to the raw text
      return { message: raw.trim(), retryAfterSeconds: undefined };
    }
  }
  return { message: `HTTP ${status}`, retryAfterSeconds: undefined };
}
