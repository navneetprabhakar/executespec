/**
 * Resolves the backend host the client talks to. Single-origin: the REST API is at
 * `${host}/api` and SSE at `${host}/api/runs/{id}/events`.
 *
 * Precedence: explicit option → `EXECUTESPEC_HOST` env → the public default.
 */
export interface ClientConfig {
  /** Origin, no trailing slash, e.g. `https://executespec.ai`. */
  readonly host: string;
  /** `${host}/api`. */
  readonly apiBase: string;
}

const DEFAULT_HOST = 'https://executespec.ai';

export function resolveConfig(opts?: { host?: string }): ClientConfig {
  const raw = opts?.host ?? process.env.EXECUTESPEC_HOST ?? DEFAULT_HOST;
  const host = raw.replace(/\/+$/, '');
  return { host, apiBase: `${host}/api` };
}
