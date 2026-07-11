import test from 'node:test';
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {validateLiteralPromptCallers, validatePromptManifest} from './validate-prompt-manifest.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

test('prompt manifest is generated and hashes all prompt resources', () => {
  execFileSync('node', ['scripts/generate-prompt-manifest.mjs'], {cwd: root, stdio: 'ignore'});
  execFileSync('node', ['scripts/validate-prompt-manifest.mjs'], {cwd: root, stdio: 'pipe'});
  const manifest = JSON.parse(fs.readFileSync(path.join(root, 'infra/prompts/prompt-manifest.json'), 'utf8'));
  assert.ok(manifest.prompts.length > 0);
  assert.equal(new Set(manifest.prompts.map((prompt) => prompt.id)).size, manifest.prompts.length);
  assert.ok(
    manifest.prompts.some((prompt) => prompt.fragments.includes('security-rules:worker')),
    'manifest must preserve the role-qualified fragment edge used by worker prompts',
  );
  assert.ok(manifest.runtimeCoverage.length >= 3);
  assert.deepEqual(
    manifest.runtimeCoverage.map((entry) => entry.id),
    ['worker-build', 'patch-worker', 'terminal-worker', 'readymade-scaffold-reference'],
  );
  for (const entry of manifest.runtimeCoverage) {
    assert.ok(entry.caller && entry.role && entry.mode && entry.profile);
    assert.ok(entry.tools.length > 0);
  }
});

test('prompt manifest rejects a deleted base or role fragment', () => {
  const errors = validatePromptManifest(
    {
      prompts: [
        {
          id: 'fixture',
          path: 'src/main/resources/prompts/classifier.md',
          sha256: '00'.repeat(32),
          fragments: ['missing-fragment:worker'],
        },
      ],
    },
    root,
  );
  assert.ok(errors.includes('stale hash: src/main/resources/prompts/classifier.md'));
  assert.ok(errors.includes('missing prompt fragment: src/main/resources/prompts/classifier.md -> missing-fragment.md'));
  assert.ok(errors.includes('missing prompt role fragment: src/main/resources/prompts/classifier.md -> missing-fragment-worker.md'));
});

test('literal Java prompt callers resolve to packaged resources', () => {
  assert.deepEqual(validateLiteralPromptCallers(root), []);
});

test('runtime coverage rejects a missing dynamic provider fragment, scaffold, or caller token', () => {
  const base = {
    id: 'fixture',
    caller: 'fixture.caller',
    sourceFile: 'src/main/java/com/navneet/executespec/engine/CodeGenEngineImpl.java',
    sourceTokens: ['definitely-not-a-real-loader'],
    role: 'WORKER',
    mode: 'BUILD',
    profile: 'fixture',
    tools: ['readFile'],
    providers: ['missing-provider'],
    providerModes: ['build'],
    fragments: ['missing-fragment'],
    scaffolds: ['missing-scaffold'],
  };
  const errors = validatePromptManifest({prompts: [], runtimeCoverage: [base]}, root);
  assert.ok(errors.some((error) => error.includes('missing runtime coverage source token')));
  assert.ok(errors.some((error) => error.includes('missing runtime provider fragment')));
  assert.ok(errors.some((error) => error.includes('missing runtime project fragment')));
  assert.ok(errors.some((error) => error.includes('missing runtime scaffold')));
});
