import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import test from 'node:test';
import {fileURLToPath} from 'node:url';
import path from 'node:path';

const root = path.dirname(path.dirname(fileURLToPath(import.meta.url)));

test('long-file target remains beyond line 150 with EOF sentinel', async () => {
  const source = await readFile(path.join(root, 'src', 'catalog.js'), 'utf8');
  const lines = source.split(/\r?\n/);
  const targetLine = lines.findIndex((line) => line.includes('export function filterCatalog')) + 1;
  assert.ok(lines.length > 180, `expected >180 lines, found ${lines.length}`);
  assert.ok(targetLine > 150, `filterCatalog must start after line 150, found ${targetLine}`);
  assert.match(source, /\/\/ LITMUS_EOF_SENTINEL\s*$/);
});
