import { randomUUID } from 'node:crypto';

import type { HttpClient } from './http.js';
import type {
  ApprovalRequest,
  CreatePatRequest,
  CreatePatResponse,
  Me,
  Page,
  PatDto,
  Project,
  RunCost,
  RunDetail,
  RunEvent,
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
  /**
   * Submit a run. Carries a stable `Idempotency-Key` generated once here and reused across http.ts's
   * internal retries, so a transient 5xx returned *after* the server already created the run can't
   * spawn a duplicate. NOTE: this is the client half — the server must dedupe on this header for the
   * guarantee to hold (backend follow-up; harmless until then).
   */
  submitRun(req: RunRequest): Promise<RunSummary> {
    return this.http.post<RunSummary>('/runs', req, {
      headers: { 'Idempotency-Key': randomUUID() },
    });
  }

  getRun(runId: string): Promise<RunDetail> {
    return this.http.get<RunDetail>(`/runs/${encodeURIComponent(runId)}`);
  }

  /**
   * The persisted run-event replay (24h Redis-backed) — same shape as the live SSE stream. Used by
   * `attach`/backfill so a client joining mid-run (or after it) can see history from the start.
   */
  eventLog(runId: string): Promise<RunEvent[]> {
    return this.http.get<RunEvent[]>(`/runs/${encodeURIComponent(runId)}/event-log`);
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

  // ── Projects ────────────────────────────────────────────────────────────────
  listProjects(): Promise<Project[]> {
    return this.http.get<Project[]>('/projects');
  }

  getProject(projectId: string): Promise<Project> {
    return this.http.get<Project>(`/projects/${encodeURIComponent(projectId)}`);
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
