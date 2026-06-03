/**
 * Parity check (design §9) — keeps the CLI and the VS Code extension honest against the one
 * capability registry (`core/src/capabilities.ts`). Dependency-free: Node's built-in test runner
 * + assert, run against the *built* `dist/capabilities.js` and the real extension manifest.
 *
 *   node --test            (from clients/core, after `npm run build`)
 *
 * Three invariants, all green at gen-1 and tightening as features ship:
 *   1. Parity contract — every capability declares a non-empty handle on BOTH surfaces (no "—").
 *   2. Shipped→wired — every `shipped` VS Code affordance exists in the extension manifest
 *      (commands ∪ configuration keys ∪ view ids). A capability marked shipped that isn't actually
 *      contributed fails here.
 *   3. No orphan command — every command the manifest contributes is claimed by exactly one
 *      registry capability. A command added to the extension without a registry entry fails here
 *      (drift the other direction).
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

import { CAPABILITIES, parityContractViolations, isVscodeCommandHandle } from '../dist/capabilities.js';

const manifest = JSON.parse(
  readFileSync(new URL('../../vscode/package.json', import.meta.url), 'utf8'),
);
const contributes = manifest.contributes ?? {};

const commandIds = new Set((contributes.commands ?? []).map((c) => c.command));
const configKeys = new Set(Object.keys(contributes.configuration?.properties ?? {}));
const viewIds = new Set(
  Object.values(contributes.views ?? {}).flatMap((arr) => arr.map((v) => v.id)),
);
const allManifestHandles = new Set([...commandIds, ...configKeys, ...viewIds]);

test('1. parity contract — every capability declares both surfaces', () => {
  assert.deepEqual(
    parityContractViolations(),
    [],
    'every capability must declare a non-empty CLI and VS Code handle (no surface-exclusive "—")',
  );
});

test('2. shipped VS Code affordances are wired in the manifest', () => {
  const missing = CAPABILITIES.filter(
    (c) => c.vscode.status === 'shipped' && !allManifestHandles.has(c.vscode.handle),
  ).map((c) => `${c.id} → ${c.vscode.handle}`);
  assert.deepEqual(
    missing,
    [],
    'these capabilities are marked vscode:shipped but their handle is not in contributes ' +
      '(command/config/view) — wire it or flip the status to planned',
  );
});

test('3. every contributed command is claimed by the registry', () => {
  const claimed = new Set(
    CAPABILITIES.map((c) => c.vscode.handle).filter(isVscodeCommandHandle),
  );
  const orphans = [...commandIds].filter((id) => !claimed.has(id));
  assert.deepEqual(
    orphans,
    [],
    'these manifest commands have no capability in core/src/capabilities.ts — add a registry entry',
  );
});

test('registry sanity — ids are Fn and unique', () => {
  for (const c of CAPABILITIES) {
    assert.match(c.id, /^F\d+$/, `bad capability id: ${c.id}`);
  }
  assert.equal(new Set(CAPABILITIES.map((c) => c.id)).size, CAPABILITIES.length, 'duplicate ids');
});
