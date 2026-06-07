/**
 * Output-format model + result envelope (robust-CLI W5.1 #5/#6). Dependency-free, against `dist/`.
 *
 *   node --test            (from clients/cli, after `npm run build`)
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';

import { resolveFormat, resultEnvelope } from '../dist/commands/run.js';

test('resolveFormat — --format wins, --json is an alias, default is text', () => {
  assert.equal(resolveFormat({ format: 'stream-json' }), 'stream-json');
  assert.equal(resolveFormat({ format: 'json' }), 'json');
  assert.equal(resolveFormat({ format: 'text' }), 'text');
  assert.equal(resolveFormat({ json: true }), 'json');
  assert.equal(resolveFormat({}), 'text');
  // dispatch validates --format; resolveFormat itself is lenient → text for anything unknown
  assert.equal(resolveFormat({ format: 'bogus' }), 'text');
});

test('resultEnvelope — clean COMPLETE is a non-error, exit 0', () => {
  const e = resultEnvelope({ runId: 'r1', status: 'COMPLETE', verifiedScore: 88 });
  assert.equal(e.type, 'result');
  assert.equal(e.runId, 'r1');
  assert.equal(e.isError, false);
  assert.equal(e.exitCode, 0);
  assert.equal(e.verifiedScore, 88);
  assert.equal(e.prUrl, null);
});

test('resultEnvelope — degraded PARTIAL is an error, exit 3, reason carried', () => {
  const e = resultEnvelope({ runId: 'r2', status: 'PARTIAL', statusReason: 'TESTS_FAILED:1_OF_3' });
  assert.equal(e.isError, true);
  assert.equal(e.exitCode, 3);
  assert.equal(e.statusReason, 'TESTS_FAILED:1_OF_3');
});

test('resultEnvelope — prefers scmPrUrl, falls back to githubPrUrl', () => {
  assert.equal(resultEnvelope({ runId: 'r', status: 'COMPLETE', githubPrUrl: 'gh' }).prUrl, 'gh');
  assert.equal(
    resultEnvelope({ runId: 'r', status: 'COMPLETE', scmPrUrl: 's', githubPrUrl: 'gh' }).prUrl,
    's',
  );
});
