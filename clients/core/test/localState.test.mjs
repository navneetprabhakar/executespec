/**
 * Tests the local-state layer (`localState.ts`): the per-directory project link, the global
 * defaults, and the resolve-precedence used by `run`/`stats`. Dependency-free (node:test + assert)
 * against the built `dist/localState.js`, using throwaway temp dirs.
 */
import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import test from 'node:test';

import { FileStateBackend, resolveProjectId } from '../dist/localState.js';

const tmp = () => mkdtemp(path.join(tmpdir(), 'es-state-'));

test('writeLink/readLink round-trips a link at <dir>/.executespec/project.json', async () => {
  const cfg = await tmp();
  const dir = await tmp();
  try {
    const be = new FileStateBackend(cfg);
    assert.equal(await be.readLink(dir), null);

    await be.writeLink(dir, { projectId: 'p1', linkedAt: '2026-06-03T00:00:00Z' });
    assert.equal((await be.readLink(dir)).projectId, 'p1');

    const raw = JSON.parse(await readFile(path.join(dir, '.executespec', 'project.json'), 'utf8'));
    assert.equal(raw.projectId, 'p1');

    assert.equal(await be.removeLink(dir), true);
    assert.equal(await be.readLink(dir), null);
    assert.equal(await be.removeLink(dir), false); // already gone
  } finally {
    await rm(cfg, { recursive: true, force: true });
    await rm(dir, { recursive: true, force: true });
  }
});

test('writeState shallow-merges + persists global defaults', async () => {
  const cfg = await tmp();
  try {
    const be = new FileStateBackend(cfg);
    assert.deepEqual(await be.readState(), {});
    await be.writeState({ defaultProjectId: 'p1' });
    await be.writeState({ defaultProvider: 'anthropic' });
    const s = await be.readState();
    assert.equal(s.defaultProjectId, 'p1');
    assert.equal(s.defaultProvider, 'anthropic');
  } finally {
    await rm(cfg, { recursive: true, force: true });
  }
});

test('resolveProjectId precedence: explicit > link > global default > undefined', async () => {
  const cfg = await tmp();
  const dir = await tmp();
  try {
    const be = new FileStateBackend(cfg);
    assert.equal(await resolveProjectId(be, dir), undefined);

    await be.writeState({ defaultProjectId: 'global' });
    assert.equal(await resolveProjectId(be, dir), 'global');

    await be.writeLink(dir, { projectId: 'linked', linkedAt: 'x' });
    assert.equal(await resolveProjectId(be, dir), 'linked'); // link beats global

    assert.equal(await resolveProjectId(be, dir, 'explicit'), 'explicit'); // explicit beats link
    assert.equal(await resolveProjectId(be, dir, '  '), 'linked'); // blank explicit ignored
  } finally {
    await rm(cfg, { recursive: true, force: true });
    await rm(dir, { recursive: true, force: true });
  }
});
