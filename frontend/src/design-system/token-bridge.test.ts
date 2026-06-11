import {describe, expect, it} from 'vitest';
import {readFileSync} from 'node:fs';
import {join} from 'node:path';
import {readdirSync} from 'node:fs';

/**
 * T2 (UI/UX review 2026-06-11) — the `--signal-*` → `--ide-*` bridge in
 * ide.css is maintained by hand. A Signal token consumed by a shared
 * component (signal/components/* are reused inside the IDE chrome) but
 * missing from the bridge silently renders with its dark-only Signal value
 * inside the IDE — the exact theme-desync class the 2026-05-22 fix closed.
 *
 * This test fails when a `--signal-*` token is BOTH (a) referenced by any
 * shared component and (b) absent from ide.css — catching the drift at PR
 * time instead of in a founder screenshot.
 */
const SRC = join(__dirname, '..');

function read(p: string): string {
  return readFileSync(join(SRC, p), 'utf8');
}

function tokensReferenced(text: string): Set<string> {
  const out = new Set<string>();
  for (const m of text.matchAll(/var\((--signal-[a-z0-9-]+)/g)) out.add(m[1]);
  return out;
}

describe('signal→ide token bridge (ide.css)', () => {
  const ideCss = read('ide/ide.css');
  const componentsDir = join(SRC, 'signal/components');
  const componentFiles = readdirSync(componentsDir).filter((f) => /\.(tsx|ts|css)$/.test(f));

  const consumed = new Set<string>();
  for (const f of componentFiles) {
    for (const t of tokensReferenced(read(`signal/components/${f}`))) consumed.add(t);
  }

  it('finds shared components consuming signal tokens (sanity)', () => {
    expect(consumed.size).toBeGreaterThan(3);
  });

  it('bridges every signal token consumed by shared components', () => {
    // Defined-in-bridge = the token name appears as a DEFINITION (`--signal-x:`)
    // anywhere in ide.css (the .ide-root bridge block).
    const missing = [...consumed].filter((t) => !new RegExp(`${t}\\s*:`).test(ideCss));
    expect(
      missing,
      `Signal tokens consumed by shared components but not bridged in ide.css — ` +
        `add them to the .ide-root bridge block (ide.css ~line 89): ${missing.join(', ')}`,
    ).toEqual([]);
  });
});
