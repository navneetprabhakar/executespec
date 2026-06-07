/**
 * Idempotency-Key on submitRun (robust-CLI W5.1 / money-safety) — a transient 5xx returned *after*
 * the server already created a run must NOT spawn a duplicate. The client guarantees its half: one
 * key per logical submit, reused verbatim across http.ts's internal retries. (Server-side dedupe on
 * the header is the backend counterpart.) Dependency-free, runs against the built `dist/`.
 *
 *   node --test            (from clients/core, after `npm run build`)
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';

import { HttpClient, ExecuteSpecApi, MemoryTokenStore, resolveConfig } from '../dist/index.js';

function mkApi() {
  const cfg = resolveConfig({ host: 'http://127.0.0.1:0' });
  const http = new HttpClient(cfg, new MemoryTokenStore('es_pat_test'));
  return new ExecuteSpecApi(http);
}

test('submitRun reuses ONE Idempotency-Key across a retry (no duplicate run)', async () => {
  const api = mkApi();
  const keys = [];
  const orig = globalThis.fetch;
  let n = 0;
  globalThis.fetch = async (_url, init) => {
    keys.push(init.headers['Idempotency-Key']);
    n += 1;
    if (n === 1) {
      // transient 5xx; retryAfterSeconds keeps the retry fast (10ms, not the 2s default backoff)
      return new Response(JSON.stringify({ message: 'busy', retryAfterSeconds: 0.01 }), {
        status: 503,
        headers: { 'content-type': 'application/json' },
      });
    }
    return new Response(JSON.stringify({ runId: 'r1', status: 'AWAITING_APPROVAL' }), {
      status: 200,
      headers: { 'content-type': 'application/json' },
    });
  };
  try {
    const res = await api.submitRun({ userRequest: 'x', source: 'REST' });
    assert.equal(res.runId, 'r1');
    assert.equal(keys.length, 2, 'should have retried exactly once');
    assert.ok(keys[0], 'Idempotency-Key header present');
    assert.equal(keys[0], keys[1], 'SAME key across the retry → server can dedupe');
    assert.match(keys[0], /^[0-9a-f-]{36}$/, 'key looks like a uuid');
  } finally {
    globalThis.fetch = orig;
  }
});

test('each logical submitRun uses a DISTINCT Idempotency-Key', async () => {
  const api = mkApi();
  const keys = [];
  const orig = globalThis.fetch;
  globalThis.fetch = async (_url, init) => {
    keys.push(init.headers['Idempotency-Key']);
    return new Response(JSON.stringify({ runId: 'r', status: 'AWAITING_APPROVAL' }), {
      status: 200,
      headers: { 'content-type': 'application/json' },
    });
  };
  try {
    await api.submitRun({ userRequest: 'a', source: 'REST' });
    await api.submitRun({ userRequest: 'b', source: 'REST' });
    assert.equal(keys.length, 2);
    assert.notEqual(keys[0], keys[1], 'distinct submits → distinct keys');
  } finally {
    globalThis.fetch = orig;
  }
});
