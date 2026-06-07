/**
 * Exit-code contract (robust-CLI W5.1) — pins `exitForStatus` so CI/automation can branch on a run's
 * terminal outcome. Dependency-free: Node's built-in test runner against the built `dist/`.
 *
 *   node --test            (from clients/cli, after `npm run build`)
 *
 * Contract:
 *   0  COMPLETE, or a PARTIAL that finished cleanly (or with an unknown/empty reason)
 *   1  FAILED / CANCELLED
 *   3  a *degraded* PARTIAL (build/tests/reviewer/tasks failed) — produced output but not clean
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';

import { exitForStatus } from '../dist/commands/run.js';

test('COMPLETE → 0', () => {
  assert.equal(exitForStatus('COMPLETE'), 0);
});

test('FAILED / CANCELLED → 1', () => {
  assert.equal(exitForStatus('FAILED'), 1);
  assert.equal(exitForStatus('CANCELLED'), 1);
});

test('clean / unknown PARTIAL → 0 (no false CI failures)', () => {
  assert.equal(exitForStatus('PARTIAL', 'CLEAN_COMPLETION'), 0);
  assert.equal(exitForStatus('PARTIAL', 'CLEAN_WITH_INTERMEDIATE_WARNINGS'), 0);
  assert.equal(exitForStatus('PARTIAL', 'LEGACY_UNKNOWN'), 0);
  assert.equal(exitForStatus('PARTIAL', ''), 0);
  assert.equal(exitForStatus('PARTIAL', null), 0);
  assert.equal(exitForStatus('PARTIAL', undefined), 0);
});

test('degraded PARTIAL → 3 (CI can branch on a non-clean result)', () => {
  assert.equal(exitForStatus('PARTIAL', 'FINAL_BUILD_FAILED'), 3);
  assert.equal(exitForStatus('PARTIAL', 'TESTS_FAILED:2_OF_5'), 3);
  assert.equal(exitForStatus('PARTIAL', 'REVIEWER_UNRESOLVED'), 3);
  assert.equal(exitForStatus('PARTIAL', 'REVIEWER_FAILED'), 3);
  assert.equal(exitForStatus('PARTIAL', 'ALL_TASKS_FAILED'), 3);
});

test('tolerates a pipe-suffixed status payload', () => {
  assert.equal(exitForStatus('FAILED|whatever'), 1);
  assert.equal(exitForStatus('COMPLETE|x'), 0);
});
