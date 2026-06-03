/**
 * `@executespec/core` — shared client core for the ExecuteSpec CLI and VS Code extension.
 *
 * Thin by design: a typed REST/SSE client, PAT auth/token storage, and the local tool runtime for
 * the local-hands/remote-brain execution model. No agent loop, prompts, model routing, or scoring
 * live here — those stay on the server.
 */
export { resolveConfig, type ClientConfig } from './config.js';
export { ApiError, parseErrorBody, type ParsedError } from './errors.js';
export * from './types.js';
export * from './capabilities.js';
export { type TokenStore, MemoryTokenStore, FileTokenStore } from './auth.js';
export { HttpClient, type RequestOptions } from './http.js';
export { ExecuteSpecApi } from './api.js';
export { streamRunEvents, type StreamOptions } from './sse.js';
export { loopbackLogin, type LoopbackLoginOptions } from './login.js';
export { safeResolve, ToolError } from './tools/pathSafety.js';
export {
  ToolRuntime,
  type ToolPermissions,
  type ToolRuntimeOptions,
} from './tools/runtime.js';
export { LocalExecutionSession, type LocalSessionOptions } from './session.js';

import { resolveConfig } from './config.js';
import { type TokenStore, FileTokenStore } from './auth.js';
import { HttpClient } from './http.js';
import { ExecuteSpecApi } from './api.js';

export interface ClientBundle {
  api: ExecuteSpecApi;
  http: HttpClient;
  tokens: TokenStore;
}

/**
 * Convenience wiring for a ready-to-use client. The CLI calls this with a {@link FileTokenStore}
 * (default); the VS Code extension passes its own `SecretStorage`-backed {@link TokenStore}.
 */
export function createClient(opts?: { host?: string; tokens?: TokenStore }): ClientBundle {
  const cfg = resolveConfig({ host: opts?.host });
  const tokens = opts?.tokens ?? new FileTokenStore();
  const http = new HttpClient(cfg, tokens);
  return { api: new ExecuteSpecApi(http), http, tokens };
}
