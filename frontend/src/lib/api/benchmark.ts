// In-app benchmark tracking (RL-2026-06) — read-only client for the common,
// suite-agnostic tracking layer. ExecuteSpec ingests events an external verifier
// publishes; this surfaces them in the operator `/benchmark` dashboard.
// Operator-only: every endpoint is SUPER_ADMIN + @Profile({local,dev,benchmark}).
import {req} from './core';

/** A tracked benchmark run (mirrors `benchmark/BenchmarkRun`). */
export interface BenchmarkRun {
  id: string;
  suite: string; // TERMINAL_BENCH | SWE_BENCH | PROGRAM_BENCH
  dataset: string;
  provider: string;
  model: string;
  k: number;
  totalTasks: number;
  resolvedTasks: number;
  status: string; // PENDING | RUNNING | DONE | FAILED | CANCELLED
  costUsd: number | string;
  inputTokens?: number;
  outputTokens?: number;
  harborJobName?: string | null;
  submissionStatus?: string | null; // SUBMITTING | SUBMITTED | FAILED
  leaderboardUrl?: string | null;
  createdByUserId?: string | null;
  createdAt: string;
  startedAt?: string | null;
  finishedAt?: string | null;
  error?: string | null;
}

/** One scored attempt of one task (mirrors `benchmark/BenchmarkTaskResult`). */
export interface BenchmarkTaskResult {
  id: string;
  benchmarkRunId: string;
  taskName: string;
  attempt: number;
  reward: number; // 1 = passed the verifier, 0 = failed
  esRunId?: string | null;
  costUsd: number | string;
  inputTokens?: number;
  outputTokens?: number;
  createdAt: string;
}

/** One event in a run's stream (mirrors `benchmark/BenchmarkEvent`). */
export interface BenchmarkEvent {
  id: string;
  benchmarkRunId: string;
  seq: number;
  type: string; // RUN_STARTED | TASK_SCORED | RUN_PROGRESS | RUN_DONE | SUBMISSION_* | …
  message?: string | null;
  payload?: string | null;
  createdAt: string;
}

/** `GET /{id}` detail — run + per-task scores + full event stream. */
export interface BenchmarkDetail {
  run: BenchmarkRun;
  tasks: BenchmarkTaskResult[];
  events: BenchmarkEvent[];
}

const BASE_PATH = '/v2/test/benchmark';

/** Most-recent 50 tracked runs (newest first). */
export const listBenchmarks = () => req<BenchmarkRun[]>(BASE_PATH);

/** One run + its task results + full event stream. */
export const getBenchmark = (id: string) => req<BenchmarkDetail>(`${BASE_PATH}/${id}`);
