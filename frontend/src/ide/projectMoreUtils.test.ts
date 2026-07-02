import {describe, expect, it} from 'vitest';

import {
  availableOrgMembers,
  canManageProjectSharing,
  contractDetectorSummary,
  docStamp,
  filterProjectFiles,
  invariantViolationCounts,
  isExternalProjectMember,
  looksLikeArtifact,
  memberDisplayName,
  parseArchDoc,
  techDebtCategories,
} from './projectMoreUtils';
import type * as api from '@/lib/api';

describe('projectMoreUtils', () => {
  it('detects assistant artifacts without classifying short normal answers', () => {
    expect(looksLikeArtifact('Short answer.')).toBe(false);
    expect(looksLikeArtifact('```ts\nconst x = 1;\n```')).toBe(true);
    expect(looksLikeArtifact('x'.repeat(1201))).toBe(true);
    expect(looksLikeArtifact('## Generated plan\n\nBody')).toBe(true);
  });

  it('builds generated-doc stamps from version and generated timestamp', () => {
    expect(docStamp(null)).toBe('');
    expect(
      docStamp({
        version: 3,
        generatedAt: '2026-06-20T05:50:00Z',
      } as api.GeneratedDocRow),
    ).toBe('3·2026-06-20T05:50:00Z');
  });

  it('sorts tech-debt categories by descending count', () => {
    expect(
      techDebtCategories({
        issuesByCategory: {security: 2, reliability: 5, style: 1},
      } as unknown as api.TechDebtReport),
    ).toEqual([
      {name: 'reliability', count: 5},
      {name: 'security', count: 2},
      {name: 'style', count: 1},
    ]);
  });

  it('filters project files case-insensitively and preserves the unfiltered list', () => {
    const files = ['src/App.tsx', 'README.md', 'src/lib/api.ts'];
    expect(filterProjectFiles(files, '')).toBe(files);
    expect(filterProjectFiles(files, 'SRC/')).toEqual(['src/App.tsx', 'src/lib/api.ts']);
    expect(filterProjectFiles(null, 'src')).toEqual([]);
  });

  it('counts invariant violations by invariant id and ignores unlinked rows', () => {
    const counts = invariantViolationCounts([
      {invariantUuid: 'inv-a'},
      {invariantUuid: 'inv-a'},
      {invariantUuid: 'inv-b'},
      {invariantUuid: null},
    ] as api.InvariantViolation[]);

    expect(counts.get('inv-a')).toBe(2);
    expect(counts.get('inv-b')).toBe(1);
    expect(counts.has('')).toBe(false);
  });

  it('summarises contract detectors and dedupes drift candidate ids', () => {
    expect(
      contractDetectorSummary([
        {status: 'SCANNED', contractIds: ['c-1', 'c-2']},
        {status: 'FAILED', contractIds: ['c-2']},
        {status: 'SKIPPED', contractIds: []},
      ] as api.DetectorSummary[]),
    ).toEqual({
      scanned: 1,
      failed: 1,
      totalContracts: 3,
      driftCandidateIds: ['c-1', 'c-2'],
    });
  });

  it('formats member display names and project membership availability', () => {
    const orgMembers = [
      {userId: 'u1', firstName: 'Ada', lastName: 'Lovelace', email: 'ada@example.com'},
      {userId: 'u2', firstName: '', lastName: '', email: 'grace@example.com'},
    ] as api.OrgMemberDto[];
    const projectMembers = [{userId: 'u1'}] as api.ProjectMemberDto[];

    expect(memberDisplayName(orgMembers[0])).toBe('Ada Lovelace');
    expect(memberDisplayName(orgMembers[1])).toBe('grace@example.com');
    expect(availableOrgMembers(orgMembers, projectMembers)).toEqual([orgMembers[1]]);
    expect(
      isExternalProjectMember({userId: 'u3', external: true} as api.ProjectMemberDto & {
        external: boolean;
      }),
    ).toBe(true);
  });

  it('lets explicit project role override personal org-admin status for share management', () => {
    expect(
      canManageProjectSharing({
        globalRole: 'USER',
        orgRole: 'ORG_ADMIN',
        projectRole: 'PROJECT_VIEWER',
        hasProjectMembership: true,
      }),
    ).toBe(false);
    expect(
      canManageProjectSharing({
        globalRole: 'USER',
        orgRole: 'DEVELOPER',
        projectRole: 'PROJECT_DEVELOPER',
        hasProjectMembership: true,
      }),
    ).toBe(true);
    expect(
      canManageProjectSharing({
        globalRole: 'USER',
        orgRole: 'ORG_ADMIN',
        hasProjectMembership: false,
      }),
    ).toBe(true);
    expect(canManageProjectSharing({globalRole: 'SUPER_ADMIN'})).toBe(true);
  });

  it('splits architecture markdown into preamble and top-level sections', () => {
    expect(
      parseArchDoc(
        [
          'Intro line',
          '',
          '## Runtime',
          'Runtime body',
          '### Detail',
          'kept in body',
          '## Storage',
          '| table |',
        ].join('\n'),
      ),
    ).toEqual({
      preamble: 'Intro line\n',
      sections: [
        {heading: 'Runtime', body: 'Runtime body\n### Detail\nkept in body'},
        {heading: 'Storage', body: '| table |'},
      ],
    });
  });
});
