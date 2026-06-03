/**
 * Tests the REPL's shell-command guard — the fix for the footgun where a bare `auth login` typed at
 * the chat prompt got shipped to the model and submitted as a run. Dependency-free (node:test +
 * assert) against the built `dist/repl.js`.
 *
 *   node --test            (from clients/cli, after `npm run build`)
 */
import assert from 'node:assert/strict';
import test from 'node:test';

import { looksLikeShellCommand } from '../dist/repl.js';

test('detects a bare CLI verb typed into chat (with or without the binary prefix)', () => {
  assert.equal(looksLikeShellCommand('auth login'), 'auth login');
  assert.equal(looksLikeShellCommand('auth status'), 'auth status');
  assert.equal(looksLikeShellCommand('run list'), 'run list');
  assert.equal(looksLikeShellCommand('run view abc-123'), 'run view abc-123');
  assert.equal(looksLikeShellCommand('  run   cancel   xyz '), 'run cancel xyz');
  assert.equal(looksLikeShellCommand('executespec auth login'), 'auth login');
  assert.equal(looksLikeShellCommand('es run list --json'), 'run list --json');
});

test('leaves natural-language chat alone (no over-triggering)', () => {
  assert.equal(looksLikeShellCommand('run a test for me'), null); // "run a" — "a" is not a subcommand
  assert.equal(looksLikeShellCommand('add input validation to the login form'), null);
  assert.equal(looksLikeShellCommand('how many runs do I have'), null);
  assert.equal(looksLikeShellCommand('authenticate me'), null);
  assert.equal(looksLikeShellCommand('run'), null); // bare group, no subcommand
  assert.equal(looksLikeShellCommand('list my runs'), null); // "list" is not a top-level group
  assert.equal(looksLikeShellCommand(''), null);
});
