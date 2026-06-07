/**
 * Per-request timeout (robust-CLI W5.1 #9b) — a hung/half-open connection must NOT block forever; it
 * aborts at the deadline and surfaces a clean error (the retry loop can't save it — a stalled fetch
 * never rejects on its own). Dependency-free, against `dist/`.
 *
 *   node --test            (from clients/core, after `npm run build`)
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';

import { HttpClient, MemoryTokenStore, resolveConfig } from '../dist/index.js';

test('a hung request aborts at the timeout instead of hanging forever', async () => {
  const cfg = resolveConfig({ host: 'http://127.0.0.1:0' });
  const http = new HttpClient(cfg, new MemoryTokenStore('es_pat_test'));
  const orig = globalThis.fetch;
  // A real fetch keeps the event loop alive via its socket until the (unref'd) AbortSignal.timeout
  // fires; this mock does no I/O, so hold the loop open ourselves — otherwise node can drain the
  // loop before the 30ms timer fires and the promise never settles ("event loop has already
  // resolved", seen on the CI node-20 runner). Cleared in finally once the request rejects.
  const keepAlive = setTimeout(() => {}, 5000);
  // Never resolves on its own; only settles when the timeout aborts the request signal.
  globalThis.fetch = (_url, init) =>
    new Promise((_resolve, reject) => {
      init.signal?.addEventListener('abort', () =>
        reject(init.signal.reason ?? new Error('aborted')),
      );
    });
  try {
    const started = Date.now();
    await assert.rejects(
      http.get('/whatever', { timeoutMs: 30, maxRetries: 0 }),
      /reach the server/i,
      'should fail fast with a clean "can\'t reach the server" error',
    );
    assert.ok(Date.now() - started < 2000, 'should abort promptly, not hang');
  } finally {
    clearTimeout(keepAlive);
    globalThis.fetch = orig;
  }
});
