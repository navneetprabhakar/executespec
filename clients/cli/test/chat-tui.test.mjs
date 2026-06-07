/**
 * Ink chat TUI smoke (the rich terminal UI). Renders the compiled <ChatApp> with a fake ChatSession
 * that yields a scripted turn, simulates typing + Enter, and asserts the transcript + status line.
 * Renders to a string via ink-testing-library — no real TTY needed.
 *
 *   node --test            (from clients/cli, after `npm run build`)
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createElement as h } from 'react';
import { render } from 'ink-testing-library';

import { ChatApp } from '../dist/tui/chat.js';

const delay = (ms) => new Promise((r) => setTimeout(r, ms));
async function waitFor(fn, pred, timeout = 1500) {
  const start = Date.now();
  for (;;) {
    const v = fn();
    if (pred(v)) return v;
    if (Date.now() - start > timeout) return v;
    await delay(15);
  }
}

function fakeSession() {
  return {
    provider: undefined,
    setProvider(p) {
      this.provider = p;
    },
    clear() {},
    // eslint-disable-next-line require-yield
    async *send() {
      yield { type: 'ack' };
      yield { type: 'token', delta: 'Hello there' };
      yield { type: 'tool_call', tool: 'submit_run', args: '{"spec":"x"}', id: '1' };
      yield { type: 'tool_result', id: '1', result: '{"ok":true,"runId":"run-42"}' };
      yield { type: 'done', turnCount: 1, costUsd: 0.0012 };
    },
  };
}

test('renders the status line + input prompt on mount', () => {
  const ui = render(
    h(ChatApp, { client: { http: { host: 'http://localhost:8080' } }, session: fakeSession(), host: 'http://localhost:8080' }),
  );
  try {
    const f = ui.lastFrame();
    assert.match(f, /ExecuteSpec/);
    assert.match(f, /localhost:8080/);
    assert.match(f, /describe what to build/); // input placeholder
  } finally {
    ui.unmount();
  }
});

test('streams a turn: user line, assistant text, tool result, cost', async () => {
  const ui = render(
    h(ChatApp, { client: { http: { host: 'http://localhost:8080' } }, session: fakeSession(), host: 'http://localhost:8080' }),
  );
  try {
    await delay(30); // let Ink mount + the controlled TextInput take focus
    ui.stdin.write('build a thing'); // type into the input box (one chunk → one onChange)
    await delay(40); // let React flush onChange→setState→re-render so the value prop is current
    ui.stdin.write('\r'); // Enter → submit (now sees the typed value, not '')
    const f = await waitFor(
      () => ui.lastFrame(),
      (frame) => /Hello there/.test(frame) && /run-42/.test(frame),
    );
    assert.match(f, /build a thing/); // the user message landed in the transcript
    assert.match(f, /Hello there/); // streamed assistant text
    assert.match(f, /run-42/); // tool_result envelope summarized (runId)
    assert.match(f, /\$0\.0012/); // done → cost in the status line
  } finally {
    ui.unmount();
  }
});
