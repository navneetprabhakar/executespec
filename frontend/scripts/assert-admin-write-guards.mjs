import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

const scriptDir = dirname(fileURLToPath(import.meta.url));
const frontendRoot = join(scriptDir, '..');

const files = [
  join(frontendRoot, 'src/signal/pages/MiscPages.tsx'),
  join(frontendRoot, 'src/ide/IdeSettings.tsx'),
];

for (const file of files) {
  const source = readFileSync(file, 'utf8');
  assertContains(source, 'adminWriteConfirmation', `${file} must use admin write confirmation`);
  assertNotContains(source, 'onChange={(v) => setTier(', `${file} direct tier write`);
  assertNotContains(source, 'onChange={(v) => setRole(', `${file} direct role write`);
  assertNotContains(source, 'onChange={(v) => setStatus(', `${file} direct status write`);
  assertNotContains(source, 'setSuspendFor', `${file} legacy suspend state`);
}

console.log('Admin write guardrail passed.');

function assertContains(value, expected, label) {
  if (!value.includes(expected)) {
    throw new Error(`${label}: expected to find "${expected}"`);
  }
}

function assertNotContains(value, unexpected, label) {
  if (value.includes(unexpected)) {
    throw new Error(`${label}: found "${unexpected}"`);
  }
}
