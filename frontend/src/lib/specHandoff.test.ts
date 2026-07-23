import {beforeEach, describe, expect, it} from 'vitest';
import {
  SPEC_HANDOFF_KEY,
  consumeSpecHandoff,
  parseSpecHandoff,
  readSpecHandoff,
  writeSpecHandoff,
} from '@/lib/specHandoff';

describe('specHandoff', () => {
  beforeEach(() => {
    sessionStorage.clear();
  });

  it('round-trips structured project identity', () => {
    writeSpecHandoff({spec: 'add audit logs', source: 'project-chat-compile', projectId: 'p1'});
    const read = readSpecHandoff();
    expect(read?.spec).toBe('add audit logs');
    expect(read?.projectId).toBe('p1');
    expect(read?.scope).toBe('PROJECT');
    expect(read?.source).toBe('project-chat-compile');
    expect(read?.schemaVersion).toBe(1);
    expect(read?.handoffId).toBeTruthy();
  });

  it('defaults to global scope when no project is supplied', () => {
    writeSpecHandoff({spec: 'build a CLI', source: 'signup-picker'});
    expect(readSpecHandoff()?.scope).toBe('GLOBAL');
    expect(readSpecHandoff()?.projectId).toBeNull();
  });

  it('migrates a legacy bare-string handoff so it is not lost', () => {
    sessionStorage.setItem(SPEC_HANDOFF_KEY, 'Build a generic DataTable component');
    const read = readSpecHandoff();
    expect(read?.spec).toBe('Build a generic DataTable component');
    expect(read?.source).toBe('legacy');
    expect(read?.scope).toBe('GLOBAL');
    expect(read?.schemaVersion).toBe(1);
  });

  it('treats a spec that happens to be JSON as a legacy raw spec', () => {
    const raw = '{"columns": ["a", "b"]}';
    sessionStorage.setItem(SPEC_HANDOFF_KEY, raw);
    expect(readSpecHandoff()?.spec).toBe(raw);
    expect(readSpecHandoff()?.source).toBe('legacy');
  });

  it('returns null for absent or blank storage', () => {
    expect(readSpecHandoff()).toBeNull();
    sessionStorage.setItem(SPEC_HANDOFF_KEY, '   ');
    expect(readSpecHandoff()).toBeNull();
    expect(parseSpecHandoff(null)).toBeNull();
  });

  it('consume clears the key so a reload does not replay the handoff', () => {
    writeSpecHandoff({spec: 'one shot', source: 'onboarding-sample'});
    expect(consumeSpecHandoff()?.spec).toBe('one shot');
    expect(sessionStorage.getItem(SPEC_HANDOFF_KEY)).toBeNull();
    expect(consumeSpecHandoff()).toBeNull();
  });
});

// RR-1537 — the parser validated 2 of 7 envelope fields and cast the rest through, so a
// malformed envelope in sessionStorage produced a value whose runtime shape contradicted
// its declared type — and a non-string projectId reached run submission.
describe('parseSpecHandoff malformed envelopes', () => {
  const base = {
    schemaVersion: 1,
    handoffId: 'h1',
    spec: 'build a thing',
    projectId: 'p1',
    repoId: 'r1',
    scope: 'PROJECT',
    source: 'onboarding-sample',
    createdAt: '2026-07-20T00:00:00.000Z',
  };

  it('normalizes a non-string projectId instead of passing it to run submission', () => {
    for (const bad of [{}, 42, [], true]) {
      const parsed = parseSpecHandoff(JSON.stringify({...base, projectId: bad}));
      expect(parsed?.projectId, JSON.stringify(bad)).toBeNull();
      // A PROJECT scope is not honourable without a project, so it degrades to GLOBAL.
      expect(parsed?.scope, JSON.stringify(bad)).toBe('GLOBAL');
    }
  });

  it('rejects a source outside the declared union', () => {
    expect(parseSpecHandoff(JSON.stringify({...base, source: 'attacker'}))?.source).toBe('legacy');
    expect(parseSpecHandoff(JSON.stringify({...base, source: 7}))?.source).toBe('legacy');
  });

  it('replaces an unparseable createdAt rather than keeping it', () => {
    const parsed = parseSpecHandoff(JSON.stringify({...base, createdAt: 'not-a-date'}));
    expect(Number.isNaN(Date.parse(parsed?.createdAt ?? ''))).toBe(false);
  });

  it('mints an id when handoffId is absent, blank or the wrong type', () => {
    for (const bad of [undefined, '', '   ', 5, {}]) {
      const parsed = parseSpecHandoff(JSON.stringify({...base, handoffId: bad}));
      expect(typeof parsed?.handoffId, JSON.stringify(bad ?? null)).toBe('string');
      expect(parsed?.handoffId, JSON.stringify(bad ?? null)).not.toBe('');
    }
  });

  it('normalizes a non-string repoId to null', () => {
    expect(parseSpecHandoff(JSON.stringify({...base, repoId: {}}))?.repoId).toBeNull();
  });

  it('keeps a well-formed envelope untouched', () => {
    expect(parseSpecHandoff(JSON.stringify(base))).toEqual(base);
  });
});
