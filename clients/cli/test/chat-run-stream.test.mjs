/**
 * Phase 2 — inline run streaming. When a chat turn returns a tool result carrying a `runId`, the TUI
 * subscribes to that run's event stream and renders progress inline, then commits a summary on
 * terminal. This test mocks `globalThis.fetch` (the SSE transport) + a fake `client.api`, drives one
 * chat turn that "submits" run-77, and asserts the inline run summary lands in the transcript.
 *
 *   node --test            (from clients/cli, after `npm run build`)
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createElement as h } from 'react';
import { render } from 'ink-testing-library';

import { ChatApp } from '../dist/tui/chat.js';

const delay = (ms) => new Promise((r) => setTimeout(r, ms));
async function waitFor(fn, pred, timeout = 6000) {
  const start = Date.now();
  for (;;) {
    const v = fn();
    if (pred(v)) return v;
    if (Date.now() - start > timeout) return v;
    await delay(20);
  }
}

function sseBody(blocks) {
  const enc = new TextEncoder();
  return new ReadableStream({
    start(c) {
      for (const b of blocks) c.enqueue(enc.encode(b));
      c.close();
    },
  });
}

function fakeSession() {
  return {
    provider: undefined,
    setProvider() {},
    clear() {},
    // eslint-disable-next-line require-yield
    async *send() {
      yield { type: 'ack' };
      yield { type: 'tool_call', tool: 'submit_run', args: '{"spec":"x"}', id: '1' };
      yield { type: 'tool_result', id: '1', result: '{"ok":true,"runId":"run-77"}' };
      yield { type: 'done', turnCount: 1, costUsd: 0.002 };
    },
  };
}

test('inline run streaming: chat submits a run → stream → terminal summary', async () => {
  const realFetch = globalThis.fetch;
  // SSE stream: status → file written → complete. The consumer breaks on COMPLETE (no reconnect).
  const blocks = [
    'data: {"type":"RUN_STATUS","payload":"CODING"}\n\n',
    'data: {"type":"FILE_WRITTEN","payload":"src/temperature.py"}\n\n',
    'data: {"type":"COMPLETE","payload":"COMPLETE"}\n\n',
  ];
  globalThis.fetch = async () =>
    new Response(sseBody(blocks), { status: 200, headers: { 'content-type': 'text/event-stream' } });

  const api = {
    http: { apiBase: 'http://localhost:8080/api' },
    sseToken: async () => ({ token: 'tkn' }),
    approve: async () => {},
    getRun: async () => ({
      runId: 'run-77',
      status: 'COMPLETE',
      statusReason: null,
      verifiedScore: 90,
      testsPassed: 3,
      testsTotal: 3,
      githubPrUrl: null,
      scmPrUrl: null,
    }),
  };
  const client = { http: { host: 'http://localhost:8080' }, api, tokens: { get: async () => 'tok' } };

  const ui = render(
    h(ChatApp, { client, session: fakeSession(), host: 'http://localhost:8080', me: { email: 'a@b.c' } }),
  );
  try {
    await delay(60);
    ui.stdin.write('build a temperature module');
    await delay(150);
    ui.stdin.write('\r');
    const f = await waitFor(
      () => ui.lastFrame(),
      (frame) => /COMPLETE/.test(frame) && /score 90/.test(frame),
      6000,
    );
    assert.match(f, /run-77/); // run id in the summary
    assert.match(f, /COMPLETE/); // terminal status
    assert.match(f, /score 90/); // verifiedScore
    assert.match(f, /tests 3\/3/); // test counts
  } finally {
    ui.unmount();
    globalThis.fetch = realFetch;
  }
});
