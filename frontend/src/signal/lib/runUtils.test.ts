import {describe, expect, it} from 'vitest';
import {humanStatusReason, repoDisplayName} from './runUtils';

describe('humanStatusReason', () => {
  it('does not present LOCAL execution as server-verified', () => {
    expect(humanStatusReason('LOCAL_EXECUTION')).toBe(
      'Changes ready locally — not server-verified',
    );
  });
});

describe('repoDisplayName', () => {
  it('prefers the GitHub slug for SCM rows', () => {
    expect(repoDisplayName({githubRepoFullName: 'acme/api'})).toBe('acme/api');
  });

  it('falls back to sourceDisplayName for V278 LOCAL rows (top-level shape)', () => {
    expect(
      repoDisplayName({
        githubRepoFullName: null,
        sourceType: 'LOCAL',
        sourceDisplayName: 'incident-service',
      }),
    ).toBe('incident-service');
  });

  it('falls back to sourceDisplayName for LOCAL rows nested in a link DTO', () => {
    expect(
      repoDisplayName({
        repo: {githubRepoFullName: null, sourceDisplayName: 'incident-service'},
      }),
    ).toBe('incident-service');
  });

  it('still shortens the repo uuid when no name exists at all', () => {
    expect(repoDisplayName({id: '12345678-aaaa-bbbb-cccc-dddddddddddd'})).toBe('12345678…');
  });
});
