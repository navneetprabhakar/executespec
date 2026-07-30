import {describe, expect, it, vi} from 'vitest';

import {
  actorLabel,
  architectureFreshnessBadge,
  availableOrgMembers,
  canManageMembership,
  canManageProjectSharing,
  canManageShareLinks,
  confirmUncertainGeneratedDocRetry,
  contractDetectorSummary,
  docStamp,
  driftSummaryMessage,
  filterProjectFiles,
  invariantViolationCounts,
  isExternalProjectMember,
  looksLikeArtifact,
  memberDisplayName,
  parseArchDoc,
  restoreGeneratedDocJobs,
  summarizeDriftResults,
  techDebtCategories,
} from './projectMoreUtils';
import type * as api from '@/lib/api';

// RR-1469 — the activity actor column read fields the served ActivityEntry never carries, leaving
// every row blank. actorLabel resolves the real actorType/actorUserId.
describe('RR-1469 — activity actor label', () => {
  it('labels system-authored events as System', () => {
    expect(actorLabel({actorType: 'SYSTEM', actorUserId: null})).toBe('System');
  });

  it('shows a legible user identity from actorUserId, never blank', () => {
    expect(actorLabel({actorType: 'USER', actorUserId: 'abcdef01-2345-6789'})).toBe(
      'user abcdef01',
    );
    expect(actorLabel({actorType: 'USER', actorUserId: 'short'})).toBe('user short');
  });

  it('falls back to a role word or dash when no id is present, not an empty string', () => {
    expect(actorLabel({actorType: 'USER'})).toBe('User');
    expect(actorLabel({})).toBe('—');
  });
});

describe('projectMoreUtils', () => {
  it('only calls architecture current when staleness is verified', () => {
    expect(
      architectureFreshnessBadge({
        stale: false,
        staleCount: 0,
        totalCount: 2,
        unknownCount: 1,
        notMeasurableCount: 1,
        verified: false,
        repos: [],
      }),
    ).toEqual({kind: 'warn', label: 'Unverified · 1 unknown, 1 not measurable'});

    expect(
      architectureFreshnessBadge({
        stale: false,
        staleCount: 0,
        totalCount: 1,
        unknownCount: 0,
        notMeasurableCount: 0,
        verified: true,
        repos: [],
      }),
    ).toEqual({kind: 'ok', label: 'Up to date'});
  });

  it('keeps stale and missing architecture measurements out of the green state', () => {
    expect(architectureFreshnessBadge(null)).toEqual({
      kind: 'warn',
      label: 'Freshness unknown',
    });
    expect(
      architectureFreshnessBadge({
        stale: true,
        staleCount: 1,
        totalCount: 1,
        unknownCount: 0,
        notMeasurableCount: 0,
        verified: true,
        repos: [],
      }),
    ).toEqual({kind: 'warn', label: 'Stale'});
  });

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

  it('restores both independently running document jobs after reload', () => {
    const explanation: api.GeneratedDocJob = {
      id: 'job-explanation',
      projectId: 'project-1',
      docType: 'EXPLANATION',
      status: 'RUNNING',
      terminal: false,
      provider: 'ANTHROPIC',
      model: 'claude-sonnet-4-6',
      resultDocId: null,
      resultDocVersion: null,
      errorCode: null,
      errorMessage: null,
      attemptCount: 1,
      deadlineAt: '2026-07-30T10:30:00Z',
      providerStartedAt: '2026-07-30T10:00:02Z',
      createdAt: '2026-07-30T10:00:00Z',
      startedAt: '2026-07-30T10:00:01Z',
      completedAt: null,
    };
    const setup: api.GeneratedDocJob = {
      id: 'job-setup',
      projectId: 'project-1',
      docType: 'SETUP',
      status: 'QUEUED',
      terminal: false,
      provider: 'OPENAI',
      model: 'gpt-5.4',
      resultDocId: null,
      resultDocVersion: null,
      errorCode: null,
      errorMessage: null,
      attemptCount: 0,
      deadlineAt: '2026-07-30T10:31:00Z',
      providerStartedAt: null,
      createdAt: '2026-07-30T10:01:00Z',
      startedAt: null,
      completedAt: null,
    };

    expect(restoreGeneratedDocJobs([explanation, setup])).toEqual({
      pending: {
        EXPLANATION: {
          type: 'EXPLANATION',
          jobId: 'job-explanation',
          provider: 'ANTHROPIC',
          model: 'claude-sonnet-4-6',
          status: 'RUNNING',
          attemptCount: 1,
          deadlineAt: '2026-07-30T10:30:00Z',
        },
        SETUP: {
          type: 'SETUP',
          jobId: 'job-setup',
          provider: 'OPENAI',
          model: 'gpt-5.4',
          status: 'QUEUED',
          attemptCount: 0,
          deadlineAt: '2026-07-30T10:31:00Z',
        },
      },
      failure: null,
      needsConfirmation: {},
    });
  });

  it('restores the latest durable document failure for actionable reload diagnostics', () => {
    const older: api.GeneratedDocJob = {
      id: 'old',
      projectId: 'project-1',
      docType: 'EXPLANATION',
      status: 'FAILED',
      terminal: true,
      provider: 'ANTHROPIC',
      model: 'claude-sonnet-4-6',
      resultDocId: null,
      resultDocVersion: null,
      errorCode: 'INVALID_GENERATED_DOCUMENT',
      errorMessage: 'Generated document was incomplete.',
      attemptCount: 1,
      deadlineAt: '2026-07-30T09:30:00Z',
      providerStartedAt: '2026-07-30T09:00:02Z',
      createdAt: '2026-07-30T09:00:00Z',
      startedAt: '2026-07-30T09:00:01Z',
      completedAt: '2026-07-30T09:01:00Z',
    };
    const newer: api.GeneratedDocJob = {
      id: 'new',
      projectId: 'project-1',
      docType: 'SETUP',
      status: 'FAILED',
      terminal: true,
      provider: 'OPENAI',
      model: 'gpt-5.4',
      resultDocId: null,
      resultDocVersion: null,
      errorCode: 'DEADLINE_EXCEEDED',
      errorMessage: 'Document generation exceeded its durable deadline.',
      attemptCount: 1,
      deadlineAt: '2026-07-30T10:30:00Z',
      providerStartedAt: null,
      createdAt: '2026-07-30T10:00:00Z',
      startedAt: '2026-07-30T10:00:01Z',
      completedAt: '2026-07-30T10:30:00Z',
    };

    expect(restoreGeneratedDocJobs([older, newer]).failure).toBe(newer);
  });

  it('restores uncertain provider outcomes as an explicit confirmation barrier', () => {
    const uncertain: api.GeneratedDocJob = {
      id: 'uncertain',
      projectId: 'project-1',
      docType: 'EXPLANATION',
      status: 'NEEDS_CONFIRMATION',
      terminal: true,
      provider: 'OPENAI',
      model: 'gpt-5.4',
      resultDocId: null,
      resultDocVersion: null,
      errorCode: 'OUTCOME_UNCERTAIN',
      errorMessage: 'Provider execution may have started.',
      attemptCount: 1,
      deadlineAt: '2026-07-30T10:30:00Z',
      providerStartedAt: '2026-07-30T10:00:02Z',
      createdAt: '2026-07-30T10:00:00Z',
      startedAt: '2026-07-30T10:00:01Z',
      completedAt: '2026-07-30T10:05:00Z',
    };

    expect(restoreGeneratedDocJobs([uncertain])).toEqual({
      pending: {},
      failure: null,
      needsConfirmation: {EXPLANATION: uncertain},
    });
  });

  it('replays an uncertain document only after explicit confirmation using the same identity', async () => {
    const uncertain: api.GeneratedDocJob = {
      id: 'uncertain',
      projectId: 'project-1',
      docType: 'SETUP',
      status: 'NEEDS_CONFIRMATION',
      terminal: true,
      provider: 'ANTHROPIC',
      model: 'claude-sonnet-4-6',
      resultDocId: null,
      resultDocVersion: null,
      errorCode: 'OUTCOME_UNCERTAIN',
      errorMessage: 'Retry requires confirmation.',
      attemptCount: 1,
      deadlineAt: '2026-07-30T10:30:00Z',
      providerStartedAt: '2026-07-30T10:00:02Z',
      createdAt: '2026-07-30T10:00:00Z',
      startedAt: '2026-07-30T10:00:01Z',
      completedAt: '2026-07-30T10:05:00Z',
    };
    const retry = vi.fn(async () => ({...uncertain, status: 'QUEUED' as const, terminal: false}));

    await expect(confirmUncertainGeneratedDocRetry(uncertain, () => false, retry)).resolves.toBe(
      null,
    );
    expect(retry).not.toHaveBeenCalled();

    await expect(
      confirmUncertainGeneratedDocRetry(uncertain, () => true, retry),
    ).resolves.toMatchObject({id: 'uncertain', status: 'QUEUED'});
    expect(retry).toHaveBeenCalledWith('project-1', 'SETUP', 'uncertain');
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

  it('RR-1507 — membership and share-link authority track their two server guards', () => {
    // A PROJECT_DEVELOPER may manage share links (isProjectWriter) but NOT
    // members (requireProjectAdmin). One boolean conflated the two.
    const dev = {
      globalRole: 'USER',
      orgRole: 'DEVELOPER',
      projectRole: 'PROJECT_DEVELOPER',
      hasProjectMembership: true,
    };
    expect(canManageMembership(dev)).toBe(false);
    expect(canManageShareLinks(dev)).toBe(true);

    // PROJECT_ADMIN gets both.
    const admin = {...dev, projectRole: 'PROJECT_ADMIN'};
    expect(canManageMembership(admin)).toBe(true);
    expect(canManageShareLinks(admin)).toBe(true);

    // A host-org ORG_ADMIN who is not a project member manages both.
    const orgAdmin = {globalRole: 'USER', orgRole: 'ORG_ADMIN', hasProjectMembership: false};
    expect(canManageMembership(orgAdmin)).toBe(true);
    expect(canManageShareLinks(orgAdmin)).toBe(true);

    // A PROJECT_VIEWER gets neither.
    const viewer = {...dev, projectRole: 'PROJECT_VIEWER'};
    expect(canManageMembership(viewer)).toBe(false);
    expect(canManageShareLinks(viewer)).toBe(false);

    // SUPER_ADMIN always gets both.
    expect(canManageMembership({globalRole: 'SUPER_ADMIN'})).toBe(true);
    expect(canManageShareLinks({globalRole: 'SUPER_ADMIN'})).toBe(true);

    // The deprecated alias keeps the wider share-link semantics.
    expect(canManageProjectSharing(dev)).toBe(true);
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
