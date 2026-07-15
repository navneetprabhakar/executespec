import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {dirname, resolve} from 'node:path';

const dir = dirname(fileURLToPath(import.meta.url));

test('reuse mode publishes a labelled Phase-B-only artifact and does not overwrite hero.webm', async () => {
  const source = await readFile(resolve(dir, 'record-hero.mjs'), 'utf8');
  const docs = await readFile(resolve(dir, 'README.md'), 'utf8');
  assert.match(source, /PHASE_B_ONLY_FILE\s*=\s*resolve\(OUT_DIR, 'hero-phase-b-only\.webm'\)/);
  assert.match(source, /copyFileSync\(RAW_B, PHASE_B_ONLY_FILE\)/);
  assert.doesNotMatch(source, /if \(REUSE_RUN_ID && SKIP_PHASE_A && !existsSync\(RAW_A\)\) \{\s*copyFileSync\(RAW_B, OUT_FILE\)/s);
  assert.match(docs, /hero-phase-b-only\.webm/);
});
