import test from 'node:test';
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

test('prompt manifest is generated and hashes all prompt resources', () => {
  execFileSync('node', ['scripts/generate-prompt-manifest.mjs'], {cwd: root, stdio: 'ignore'});
  execFileSync('node', ['scripts/validate-prompt-manifest.mjs'], {cwd: root, stdio: 'pipe'});
  const manifest = JSON.parse(fs.readFileSync(path.join(root, 'infra/prompts/prompt-manifest.json'), 'utf8'));
  assert.ok(manifest.prompts.length > 0);
  assert.equal(new Set(manifest.prompts.map((prompt) => prompt.id)).size, manifest.prompts.length);
});
