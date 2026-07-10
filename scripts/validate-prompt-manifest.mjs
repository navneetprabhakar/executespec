#!/usr/bin/env node

import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const manifestPath = path.join(root, 'infra/prompts/prompt-manifest.json');
const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
const errors = [];
const ids = new Set();
for (const prompt of manifest.prompts ?? []) {
  if (ids.has(prompt.id)) errors.push(`duplicate prompt id: ${prompt.id}`);
  ids.add(prompt.id);
  const file = path.join(root, prompt.path);
  if (!fs.existsSync(file)) {
    errors.push(`missing prompt: ${prompt.path}`);
    continue;
  }
  const actual = crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
  if (actual !== prompt.sha256) errors.push(`stale hash: ${prompt.path}`);
}
if (errors.length) {
  console.error(errors.join('\n'));
  process.exit(1);
}
console.log(`prompt manifest valid: ${manifest.prompts.length} prompts`);
