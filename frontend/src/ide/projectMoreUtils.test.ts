import {describe, expect, it} from 'vitest';

import {
  availableOrgMembers,
  canManageProjectSharing,
  contractDetectorSummary,
  docStamp,
  driftSummaryMessage,
  filterProjectFiles,
  invariantViolationCounts,
  isExternalProjectMember,
  looksLikeArtifact,
  memberDisplayName,
  parseArchDoc,
  summarizeDriftResults,
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

// RR-1658 — `hasDrift` is false for BOTH NO_DRIFT and UNAVAILABLE (retained that way for
// wire compatibility), so counting it alone reported an unperformable check as clean.
describe('contract drift rollup', () => {
  it('separates an unavailable check from a clean one', () => {
    const summary = summarizeDriftResults([
      {contractId: 'a', hasDrift: false, summary: 'no drift', status: 'NO_DRIFT'},
      {contractId: 'b', hasDrift: true, summary: 'diff', status: 'DRIFT'},
      {contractId: 'c', hasDrift: false, summary: 'source unreadable', status: 'UNAVAILABLE'},
    ]);
    expect(summary).toEqual({drifted: 1, clean: 1, unavailable: 1, total: 3});
  });

  it('never reports an unavailable-only batch as clean', () => {
    const summary = summarizeDriftResults([
      {contractId: 'c', hasDrift: false, summary: 'source unreadable', status: 'UNAVAILABLE'},
    ]);
    expect(summary.clean).toBe(0);
    expect(driftSummaryMessage(summary)).toBe('1 contract could not be checked.');
  });

  it('falls back to hasDrift for rows from a server predating the tri-state', () => {
    expect(
      summarizeDriftResults([
        {contractId: 'a', hasDrift: false, summary: 'no drift'},
        {contractId: 'b', hasDrift: true, summary: 'diff'},
      ]),
    ).toEqual({drifted: 1, clean: 1, unavailable: 0, total: 2});
  });

  it('reports drift and unavailable together rather than only the drift count', () => {
    expect(
      driftSummaryMessage({
        drifted: 2,
        unavailable: 3,
        total: 6,
      }),
    ).toBe('2 drift results detected; 3 contracts could not be checked.');
  });

  it('still says no drift when every check succeeded and matched', () => {
    expect(driftSummaryMessage({drifted: 0, unavailable: 0, total: 4})).toBe('No drift detected.');
    expect(driftSummaryMessage({drifted: 0, unavailable: 0, total: 0})).toBe(
      'No contracts to check.',
    );
  });
});
