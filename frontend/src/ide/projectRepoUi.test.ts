import {describe, expect, it} from 'vitest';
import {
  explicitLinkedRepoSelection,
  normalizeGitHubRepoQuery,
  normalizeScmRepoQuery,
  repoSupports,
  sourceReadinessBadge,
  syncBadge,
} from './projectRepoUi';

describe('brownfield repository UI contracts', () => {
  it('turns linked repositories into an explicit submitted selection', () => {
    expect(
      explicitLinkedRepoSelection([
        {repoId: 'repo-1', repo: {id: 'wrong'}},
        {repo: {id: 'repo-2'}},
      ]),
    ).toEqual(['repo-1', 'repo-2']);
  });

  it('accepts a GitHub URL without hiding its owner/repo identity', () => {
    expect(normalizeGitHubRepoQuery(' https://github.com/django/django.git ')).toBe(
      'django/django',
    );
  });

  it('normalizes Bitbucket Cloud URLs without treating them as GitHub', () => {
    expect(normalizeScmRepoQuery('https://bitbucket.org/acme/platform.git', 'BITBUCKET')).toBe(
      'acme/platform',
    );
    expect(normalizeScmRepoQuery('https://github.com/acme/platform', 'BITBUCKET')).toBe(
      'https://github.com/acme/platform',
    );
  });

  it('fails closed for GitHub-only capabilities on Bitbucket and preserves legacy GitHub rows', () => {
    expect(
      repoSupports(
        {scmProvider: 'BITBUCKET', sourceCapabilities: ['SYNC', 'SOURCE_BROWSE']},
        'ARCHITECTURE',
      ),
    ).toBe(false);
    expect(repoSupports({scmProvider: 'BITBUCKET'}, 'WEBHOOK')).toBe(false);
    expect(repoSupports({scmProvider: 'GITHUB'}, 'WEBHOOK')).toBe(true);
  });

  it('never presents missing SHA evidence as in sync', () => {
    expect(syncBadge(undefined)).toEqual({kind: 'warn', label: 'sync unknown'});
    expect(syncBadge('IN_SYNC')).toEqual({kind: 'ok', label: 'in sync'});
  });

  it('keeps link/readiness failure distinct and actionable', () => {
    expect(sourceReadinessBadge('BLOCKED', 'GITHUB_TREE_HTTP_ERROR', null)).toEqual({
      kind: 'bad',
      label: 'source blocked · GITHUB_TREE_HTTP_ERROR',
    });
    expect(sourceReadinessBadge('READY', null, 42).label).toBe('source ready · 42 files');
  });
});
