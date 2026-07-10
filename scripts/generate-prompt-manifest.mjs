#!/usr/bin/env node

import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const promptRoot = path.join(root, 'src/main/resources/prompts');
const output = path.join(root, 'infra/prompts/prompt-manifest.json');

function walk(dir) {
  if (!fs.existsSync(dir)) return [];
  return fs.readdirSync(dir, {withFileTypes: true}).flatMap((entry) => {
    const file = path.join(dir, entry.name);
    return entry.isDirectory() ? walk(file) : [file];
  });
}

const files = walk(promptRoot).filter((file) => /\.(?:md|txt|json)$/.test(file)).sort();
const prompts = files.map((file) => {
  const relativePath = path.relative(root, file).split(path.sep).join('/');
  const content = fs.readFileSync(file);
  const text = content.toString('utf8');
  return {
    id: relativePath.replace(/^src\/main\/resources\/prompts\//, '').replace(/\.[^.]+$/, ''),
    path: relativePath,
    sha256: crypto.createHash('sha256').update(content).digest('hex'),
    bytes: content.length,
    active: !/(?:archive|rollback|legacy|deprecated)/i.test(relativePath),
    fragments: [...text.matchAll(/\{\{include:([^}]+)\}\}/g)].map((match) => match[1]).sort(),
    variables: [...text.matchAll(/\{\{([A-Z][A-Z0-9_]*)\}\}/g)].map((match) => match[1]).filter((value, index, values) => values.indexOf(value) === index).sort(),
  };
});

const manifest = {
  schemaVersion: 1,
  source: 'src/main/resources/prompts',
  policy: 'Prompt hashes are evidence identities; prompt tuning must record provider/model, tool-schema, harness, task slice, flags, and budgets separately.',
  prompts,
};
fs.mkdirSync(path.dirname(output), {recursive: true});
fs.writeFileSync(output, `${JSON.stringify(manifest, null, 2)}\n`);
process.stdout.write(`${output}\n`);
