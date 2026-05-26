import type { HttpClient } from './http.js';
import type {
  ApprovalRequest,
  CreatePatRequest,
  CreatePatResponse,
  Me,
  Page,
  PatDto,
  RunCost,
  RunDetail,
  RunListQuery,
  RunRequest,
  RunSummary,
  ToolResultFrame,
} from './types.js';

/**
 * Typed surface over the subset of the ExecuteSpec REST API the clients need. Every method is a
 * direct projection of an existing endpoint — no orchestration, no derived logic.
 */
export class ExecuteSpecApi {
  constructor(readonly http: HttpClient) {}

  // ── Auth / PAT ──────────────────────────────────────────────────────────
  me(): Promise<Me> {
    return this.http.get<Me>('/auth/me');
  }

  createToken(req: CreatePatRequest): Promise<CreatePatResponse> {
    return this.http.post<CreatePatResponse>('/auth/me/tokens', req);
  }

  listTokens(): Promise<PatDto[]> {
    return this.http.get<PatDto[]>('/auth/me/tokens');
  }

  revokeToken(tokenId: string): Promise<void> {
    return this.http.delete<void>(`/auth/me/tokens/${encodeURIComponent(tokenId)}`);
  }

  // ── Runs ──────────────────────────────────────────────────────────────────
  submitRun(req: RunRequest): Promise<RunSummary> {
    return this.http.post<RunSummary>('/runs', req);
  }

  getRun(runId: string): Promise<RunDetail> {
    return this.http.get<RunDetail>(`/runs/${encodeURIComponent(runId)}`);
  }

  listRuns(query?: RunListQuery): Promise<Page<RunSummary>> {
    return this.http.get<Page<RunSummary>>('/runs', { query: { ...query } });
  }

  approve(runId: string, req: ApprovalRequest): Promise<void> {
    return this.http.post<void>(`/runs/${encodeURIComponent(runId)}/approve`, req);
  }

  cancel(runId: string): Promise<void> {
    return this.http.delete<void>(`/runs/${encodeURIComponent(runId)}`);
  }

  getReport(runId: string): Promise<Record<string, unknown>> {
    return this.http.get<Record<string, unknown>>(`/runs/${encodeURIComponent(runId)}/report`);
  }

  getCost(runId: string): Promise<RunCost> {
    return this.http.get<RunCost>(`/runs/${encodeURIComponent(runId)}/cost`);
  }

  retryTask(runId: string, taskId: string): Promise<Record<string, unknown>> {
    return this.http.post<Record<string, unknown>>(
      `/runs/${encodeURIComponent(runId)}/tasks/${encodeURIComponent(taskId)}/retry`,
    );
  }

  // ── SSE + local-execution tool proxy ───────────────────────────────────────
  /** Mint a 5-min scoped SSE token (EventSource/stream can't send Authorization headers). */
  sseToken(runId: string): Promise<{ token: string }> {
    return this.http.get<{ token: string }>(`/runs/${encodeURIComponent(runId)}/sse-token`);
  }

  /**
   * Return a local tool-execution result to the server-side agent loop. The endpoint
   * (`POST /api/runs/{id}/tool-results`) is part of the additive local-hands backend surface
   * (RL-2026-05-043) and is a no-op for non-local runs.
   */
  postToolResult(runId: string, frame: ToolResultFrame): Promise<void> {
    return this.http.post<void>(`/runs/${encodeURIComponent(runId)}/tool-results`, frame);
  }
}
