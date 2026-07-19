/**
 * Versioned structured spec handoff (Phase 4, item 29).
 *
 * A discovery surface (onboarding samples, the signup spec picker, project chat
 * compile) stashes a spec for the composer to pick up after navigation. That
 * handoff used to be a bare string in `sessionStorage`, so every piece of
 * structured identity — which project, which repo, which surface it came from —
 * was dropped on the floor, and there was no way to tell a stale handoff from a
 * fresh one.
 *
 * This module keeps the same storage key (so an in-flight legacy handoff written
 * by an older bundle is still honoured) but writes a versioned envelope.
 */
export const SPEC_HANDOFF_KEY = 'signal:pending-spec';

export const SPEC_HANDOFF_SCHEMA_VERSION = 1;

export type SpecHandoffSource =
  | 'onboarding-sample'
  | 'signup-picker'
  | 'project-chat-compile'
  | 'legacy';

export type SpecHandoff = {
  schemaVersion: typeof SPEC_HANDOFF_SCHEMA_VERSION;
  /** Stable identity for this handoff so a consumer can dedupe a replayed read. */
  handoffId: string;
  spec: string;
  projectId: string | null;
  repoId: string | null;
  /** Where the spec should be composed, when the producer knows. */
  scope: 'GLOBAL' | 'PROJECT';
  source: SpecHandoffSource;
  createdAt: string;
};

export type SpecHandoffInput = {
  spec: string;
  source: SpecHandoffSource;
  projectId?: string | null;
  repoId?: string | null;
  handoffId?: string;
};

function mintId(): string {
  const c = globalThis.crypto;
  if (c && typeof c.randomUUID === 'function') return c.randomUUID();
  return `spec-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;
}

export function buildSpecHandoff(input: SpecHandoffInput): SpecHandoff {
  const projectId = input.projectId?.trim() || null;
  return {
    schemaVersion: SPEC_HANDOFF_SCHEMA_VERSION,
    handoffId: input.handoffId || mintId(),
    spec: input.spec,
    projectId,
    repoId: input.repoId?.trim() || null,
    scope: projectId ? 'PROJECT' : 'GLOBAL',
    source: input.source,
    createdAt: new Date().toISOString(),
  };
}

function storage(): Storage | null {
  try {
    return globalThis.sessionStorage ?? null;
  } catch {
    // Private-mode / disabled storage must not break the producing surface.
    return null;
  }
}

export function writeSpecHandoff(input: SpecHandoffInput): SpecHandoff {
  const handoff = buildSpecHandoff(input);
  try {
    storage()?.setItem(SPEC_HANDOFF_KEY, JSON.stringify(handoff));
  } catch {
    // Non-fatal: the composer simply starts empty.
  }
  return handoff;
}

/**
 * Migrate the legacy bare-string form. Anything that is not a well-formed
 * versioned envelope is treated as a raw spec so an in-flight handoff written by
 * a previous bundle is not lost.
 */
export function parseSpecHandoff(raw: string | null): SpecHandoff | null {
  if (raw === null) return null;
  const trimmed = raw.trim();
  if (!trimmed) return null;
  let parsed: unknown;
  try {
    parsed = JSON.parse(trimmed);
  } catch {
    return buildSpecHandoff({spec: raw, source: 'legacy'});
  }
  if (
    !parsed ||
    typeof parsed !== 'object' ||
    Array.isArray(parsed) ||
    (parsed as {schemaVersion?: unknown}).schemaVersion !== SPEC_HANDOFF_SCHEMA_VERSION ||
    typeof (parsed as {spec?: unknown}).spec !== 'string'
  ) {
    // Valid JSON that is not our envelope (for example a spec that happens to be
    // a JSON snippet) is still a legacy raw spec.
    return buildSpecHandoff({spec: raw, source: 'legacy'});
  }
  const envelope = parsed as Partial<SpecHandoff> & {spec: string};
  return {
    schemaVersion: SPEC_HANDOFF_SCHEMA_VERSION,
    handoffId: envelope.handoffId || mintId(),
    spec: envelope.spec,
    projectId: envelope.projectId ?? null,
    repoId: envelope.repoId ?? null,
    scope: envelope.scope === 'PROJECT' ? 'PROJECT' : 'GLOBAL',
    source: envelope.source ?? 'legacy',
    createdAt: envelope.createdAt || new Date().toISOString(),
  };
}

export function readSpecHandoff(): SpecHandoff | null {
  try {
    return parseSpecHandoff(storage()?.getItem(SPEC_HANDOFF_KEY) ?? null);
  } catch {
    return null;
  }
}

export function clearSpecHandoff(): void {
  try {
    storage()?.removeItem(SPEC_HANDOFF_KEY);
  } catch {
    // Nothing to do.
  }
}

/** Read once and clear — the composer's normal consumption path. */
export function consumeSpecHandoff(): SpecHandoff | null {
  const handoff = readSpecHandoff();
  if (handoff) clearSpecHandoff();
  return handoff;
}
