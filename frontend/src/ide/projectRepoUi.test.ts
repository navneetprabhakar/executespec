import {describe, expect, it} from 'vitest';
import {
  codeIndexRefreshNotice,
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

  it('does not claim semantic work was queued when the backend says it is unavailable', () => {
    const notice = codeIndexRefreshNotice({
      repoId: 'repo-1',
      status: 'UNAVAILABLE',
      fallback: 'RIPGREP',
      relationshipGraphAvailable: false,
      reason: 'EMBEDDING_PROVIDER_NOT_AVAILABLE_FOR_PROJECT_ROUTE',
    });

    expect(notice).toContain('Semantic indexing unavailable');
    expect(notice).toContain('EMBEDDING_PROVIDER_NOT_AVAILABLE_FOR_PROJECT_ROUTE');
    expect(notice.toLowerCase()).not.toContain('queued');
    expect(notice).toContain('Ripgrep search remains available');
  });

  it.each([
    ['QUEUED', 'Semantic indexing queued'],
    ['PARTIAL', 'Semantic index remains partial'],
    ['READY', 'Semantic index is already ready'],
  ] as const)('reports the %s refresh admission status honestly', (status, expected) => {
    expect(
      codeIndexRefreshNotice({
        repoId: 'repo-1',
        status,
        fallback: 'RIPGREP',
        relationshipGraphAvailable: false,
        reason: null,
      }),
    ).toContain(expected);
  });
});
