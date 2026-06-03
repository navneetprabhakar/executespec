/**
 * Chat SSE parser tests — the riskiest pure logic in the chat client. Dependency-free (node:test +
 * assert) against the built `dist/chat.js`. The chat stream differs from the run stream: the SSE
 * `event:` name is the discriminator (ack/token/tool_call/tool_result/done), not a field in the
 * payload.
 *
 *   node --test            (from clients/core, after `npm run build`)
 */
import assert from 'node:assert/strict';
import test from 'node:test';

import { parseChatBlock, parseChatSse } from '../dist/chat.js';

function streamOf(text) {
  const bytes = new TextEncoder().encode(text);
  return new ReadableStream({
    start(controller) {
      controller.enqueue(bytes);
      controller.close();
    },
  });
}

async function collect(stream) {
  const out = [];
  for await (const ev of stream) out.push(ev);
  return out;
}

test('parseChatBlock — decodes token / tool_call / tool_result by the event name', () => {
  assert.deepEqual(parseChatBlock('event: token\ndata: {"delta":"hi"}'), {
    type: 'token',
    delta: 'hi',
  });
  assert.deepEqual(parseChatBlock('event: tool_call\ndata: {"tool":"submit_run","args":"{}","id":"c1"}'), {
    type: 'tool_call',
    tool: 'submit_run',
    args: '{}',
    id: 'c1',
  });
  assert.deepEqual(
    parseChatBlock('event: tool_result\ndata: {"id":"c1","result":"{\\"ok\\":true}"}'),
    { type: 'tool_result', id: 'c1', result: '{"ok":true}' },
  );
});

test('parseChatBlock — done carries turn/cost and the flag-OFF note', () => {
  const done = parseChatBlock('event: done\ndata: {"turn_count":2,"cost_usd":0.01}');
  assert.equal(done.type, 'done');
  assert.equal(done.turnCount, 2);
  assert.equal(done.costUsd, 0.01);

  const off = parseChatBlock('event: done\ndata: {"turn_count":1,"note":"spec.chat.enabled is OFF"}');
  assert.equal(off.note, 'spec.chat.enabled is OFF');
});

test('parseChatBlock — heartbeat comment / blank block yields null', () => {
  assert.equal(parseChatBlock(': keep-alive'), null);
  assert.equal(parseChatBlock(''), null);
});

test('parseChatSse — a full turn streams ack/token/tool_call/tool_result/done in order', async () => {
  const sse =
    'event: ack\ndata: {"audit_id":"a1"}\n\n' +
    'event: token\ndata: {"delta":"Listing your runs."}\n\n' +
    'event: tool_call\ndata: {"tool":"list_recent_runs","args":"{\\"limit\\":5}","id":"c1"}\n\n' +
    'event: tool_result\ndata: {"id":"c1","result":"{\\"ok\\":true,\\"count\\":5}"}\n\n' +
    'event: token\ndata: {"delta":"You have 5 runs."}\n\n' +
    'event: done\ndata: {"turn_count":2,"cost_usd":0.02}\n\n';

  const evs = await collect(parseChatSse(streamOf(sse)));

  assert.deepEqual(
    evs.map((e) => e.type),
    ['ack', 'token', 'tool_call', 'tool_result', 'token', 'done'],
  );
  assert.equal(evs[2].tool, 'list_recent_runs');
  assert.equal(evs[3].result, '{"ok":true,"count":5}');
  assert.equal(evs[5].turnCount, 2);
});

test('parseChatSse — tolerates a frame split across chunks', async () => {
  // Emit the same logical events but split a frame mid-data to exercise the buffer join.
  const parts = ['event: token\nda', 'ta: {"delta":"split"}\n\n', 'event: done\ndata: {"turn_count":1}\n\n'];
  const stream = new ReadableStream({
    start(controller) {
      const enc = new TextEncoder();
      for (const p of parts) controller.enqueue(enc.encode(p));
      controller.close();
    },
  });
  const evs = await collect(parseChatSse(stream));
  assert.deepEqual(
    evs.map((e) => e.type),
    ['token', 'done'],
  );
  assert.equal(evs[0].delta, 'split');
});
