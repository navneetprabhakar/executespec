#!/usr/bin/env node

import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const manifestPath = path.join(root, 'infra/prompts/prompt-manifest.json');

/**
 * Validate the prompt inventory and the include graph against a repository root.
 *
 * Hashes alone are not enough: a prompt can remain byte-identical while a fragment it
 * references is deleted or renamed.  Keeping this pure makes the graph contract usable
 * by offline tests without invoking a provider or a build.
 */
export function validatePromptManifest(manifest, repositoryRoot = root) {
  const errors = [];
  const ids = new Set();
  for (const prompt of manifest.prompts ?? []) {
    if (ids.has(prompt.id)) errors.push(`duplicate prompt id: ${prompt.id}`);
    ids.add(prompt.id);
    const file = path.join(repositoryRoot, prompt.path);
    if (!fs.existsSync(file)) {
      errors.push(`missing prompt: ${prompt.path}`);
      continue;
    }
    const actual = crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
    if (actual !== prompt.sha256) errors.push(`stale hash: ${prompt.path}`);

    for (const include of prompt.fragments ?? []) {
      const [name, role] = String(include).split(':', 2);
      const base = path.join(
        repositoryRoot,
        'src/main/resources/prompts/fragments',
        `${name}.md`,
      );
      if (!fs.existsSync(base)) {
        errors.push(`missing prompt fragment: ${prompt.path} -> ${name}.md`);
      }
      if (role) {
        const rolePath = path.join(
          repositoryRoot,
          'src/main/resources/prompts/fragments',
          `${name}-${role}.md`,
        );
        if (!fs.existsSync(rolePath)) {
          errors.push(`missing prompt role fragment: ${prompt.path} -> ${name}-${role}.md`);
        }
      }
    }
  }
  return errors;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
  const errors = validatePromptManifest(manifest);
  if (errors.length) {
    console.error(errors.join('\n'));
    process.exit(1);
  }
  console.log(`prompt manifest valid: ${manifest.prompts.length} prompts`);
}
