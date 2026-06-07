/**
 * Local-exec permission gate (robust-CLI W5.1 security) — the `run local` ToolRuntime must be
 * fail-closed: side-effecting commands (shell/buildProject/runTests/gitCommit) are DENIED unless an
 * explicit policy approves them, so a misconfigured local-hands run can never execute arbitrary
 * shell. Path-confined file tools stay open. Dependency-free, against the built `dist/`.
 *
 *   node --test            (from clients/core, after `npm run build`)
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, rm, access } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';

import { ToolRuntime, createInteractivePermissions, allowAllPermissions } from '../dist/index.js';

async function tmpRoot() {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'es-rt-'));
  await writeFile(path.join(dir, 'a.txt'), 'hello world\n');
  return dir;
}

const frame = (name, args) => ({ callId: '1', runId: 'r', name, args });

test('side-effecting commands are DENIED by default (fail-closed)', async () => {
  const root = await tmpRoot();
  try {
    const rt = new ToolRuntime({ workspaceRoot: root });
    for (const f of [
      frame('shell', { command: 'echo PWNED > pwned.txt' }),
      frame('buildProject', { command: 'echo build' }),
      frame('runTests', { command: 'echo test' }),
      frame('gitCommit', { message: 'x' }),
    ]) {
      const res = await rt.execute(f);
      assert.equal(res.ok, false, `${f.name} must be denied`);
      assert.equal(res.kind, 'FORBIDDEN');
    }
    // The denied shell redirect must NOT have run.
    await assert.rejects(access(path.join(root, 'pwned.txt')), 'denied command must not execute');
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test('path-confined file tools work without any policy', async () => {
  const root = await tmpRoot();
  try {
    const rt = new ToolRuntime({ workspaceRoot: root });
    const read = await rt.execute(frame('readFile', { path: 'a.txt' }));
    assert.equal(read.ok, true);
    assert.match(read.content, /hello world/);
    const write = await rt.execute(frame('writeFile', { path: 'b.txt', content: 'x' }));
    assert.equal(write.ok, true);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test('allowAllPermissions permits shell (trusted opt-in)', async () => {
  const root = await tmpRoot();
  try {
    const rt = new ToolRuntime({ workspaceRoot: root, permissions: allowAllPermissions() });
    const res = await rt.execute(frame('shell', { command: 'echo hi' }));
    assert.equal(res.ok, true);
    assert.match(res.content, /hi/);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test('interactive policy: deny blocks; allow runs + is remembered; always allows the rest', async () => {
  const root = await tmpRoot();
  try {
    const asked = [];
    let answer = 'deny';
    const perms = createInteractivePermissions(async (tool, command) => {
      asked.push(`${tool}:${command}`);
      return answer;
    });
    const rt = new ToolRuntime({ workspaceRoot: root, permissions: perms });

    // deny → blocked
    let res = await rt.execute(frame('shell', { command: 'echo a' }));
    assert.equal(res.ok, false);
    assert.equal(res.kind, 'FORBIDDEN');

    // allow → runs, and the same command is remembered (not re-asked)
    answer = 'allow';
    res = await rt.execute(frame('shell', { command: 'echo b' }));
    assert.equal(res.ok, true);
    const asksAfterAllow = asked.length;
    res = await rt.execute(frame('shell', { command: 'echo b' }));
    assert.equal(res.ok, true);
    assert.equal(asked.length, asksAfterAllow, 'identical command must not be re-asked');

    // always → every later command allowed without asking
    answer = 'always';
    res = await rt.execute(frame('shell', { command: 'echo c' }));
    assert.equal(res.ok, true);
    const asksAfterAlways = asked.length;
    res = await rt.execute(frame('shell', { command: 'echo d' }));
    assert.equal(res.ok, true);
    assert.equal(asked.length, asksAfterAlways, 'after "always" there must be no more prompts');
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
