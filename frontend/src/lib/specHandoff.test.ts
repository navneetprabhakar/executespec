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
