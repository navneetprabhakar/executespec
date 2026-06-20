import {describe, expect, it} from 'vitest';

import {
  activeProjectName,
  activeProjects,
  applicabilityBadge,
  applicabilityLabel,
  categoryTotal,
  creditsLabel,
  dryRunCostLabel,
  dryRunFilesLabel,
  durationLabel,
  estimatedFilesLabel,
  insightCards,
  libraryCategories,
  librarySubtitle,
  migrationCardMeta,
  migrationDescription,
  outcomePercent,
  providerRows,
  resolvedVariantMessage,
  solutionDescription,
  sourceLabel,
  splitTags,
  templatePreview,
  topUserLabel,
  variantLabel,
} from './discoveryUtils';
import type * as api from '@/lib/api';

describe('discoveryUtils', () => {
  it('normalizes tags, credits, active projects, and categories', () => {
    expect(splitTags('react, node | postgres, docker, ignored')).toEqual([
      'react',
      'node',
      'postgres',
      'docker',
    ]);
    expect(creditsLabel(null, null)).toBeNull();
    expect(creditsLabel('1', '1')).toBe('1 credit');
    expect(creditsLabel('2', '5')).toBe('2-5 credits');
    expect(
      activeProjects([project({id: 'p1'}), project({id: 'p2', archivedAt: 'x'})]).map((p) => p.id),
    ).toEqual(['p1']);
    const categories = libraryCategories({
      categories: {billing: 2, auth: 5},
      macroGroups: {},
    });
    expect(categories).toEqual([
      ['auth', 5],
      ['billing', 2],
    ]);
    expect(categoryTotal(categories)).toBe(7);
  });

  it('projects insight cards, provider rows, outcomes, and top user', () => {
    const cards = insightCards({
      totalRuns: 10,
      completedRuns: 8,
      failedRuns: 2,
      activeRuns: 0,
      queuedRuns: 0,
      totalInputTokens: 0,
      totalOutputTokens: 0,
      avgVerifiedScore: 84.25,
      avgDurationMs: 125000,
      totalSpend: 3.5,
      shipped: 6,
      needs: 2,
      failed: 2,
    } as api.MetricsResponse);

    expect(cards).toMatchObject({
      total: 10,
      shipped: 6,
      needs: 2,
      failed: 2,
      avgScore: '84.3%',
      avgDuration: '2m 5s',
      spend: '$3.50',
    });
    expect(insightCards(null).avgScore).toBe('-');
    expect(durationLabel(61000)).toBe('1m 1s');
    expect(
      providerRows({
        models: [],
        byProvider: [{provider: 'deepseek', spend: 1, cacheHitRate: 0.42}],
      } as unknown as api.ModelComparisonData),
    ).toEqual([{provider: 'deepseek', value: '$1.00 (42% cache)'}]);
    expect(outcomePercent(3, 10)).toBe(30);
    expect(outcomePercent(3, 0)).toBe(0);
    expect(
      topUserLabel({
        users: [
          {
            userId: 'u1',
            displayName: '',
            email: 'forge@example.com',
            runCount: 4,
            successRate: 0.75,
          },
        ],
      } as unknown as api.UserRunsSummaryData),
    ).toEqual({name: 'forge@example.com', runCount: 4, successRate: 0.75});
  });

  it('formats library and variant labels', () => {
    const solution = readymade({category: 'Auth', estCreditsLow: '2', estCreditsHigh: '3'});
    expect(variantLabel({language: 'TypeScript', framework: 'React'} as api.ReadymadeVariant)).toBe(
      'TypeScript · React',
    );
    expect(variantLabel({language: '', framework: ''} as api.ReadymadeVariant)).toBe('variant');
    expect(librarySubtitle(solution, '2-3 credits')).toBe('Auth · 2-3 credits');
    expect(solutionDescription(readymade({description: null, tagline: 'Fast auth'}))).toBe(
      'Fast auth',
    );
    expect(sourceLabel('FIRST_PARTY')).toBe('First-party');
    expect(sourceLabel('COMMUNITY')).toBe('Community');
  });

  it('formats variant resolution and migration metadata', () => {
    expect(
      resolvedVariantMessage({
        bestMatch: variant({id: 'v1'}),
        availableVariants: [],
        detectedLanguage: 'Java',
        detectedFramework: 'Spring',
        reason: 'OK',
      }),
    ).toBe('Detected Java / Spring - using the best-fit variant.');
    expect(
      resolvedVariantMessage({
        bestMatch: null,
        availableVariants: [],
        detectedLanguage: null,
        detectedFramework: null,
        reason: 'STACK_NOT_DETECTED',
      }),
    ).toContain("Couldn't detect");

    expect(estimatedFilesLabel(2, 5, '~')).toBe('~2-5');
    expect(migrationCardMeta(migration({estFilesLow: 1, estFilesHigh: 3}))).toEqual({
      credits: '2-4 credits',
      fileMeta: '~1-3 files',
    });
    expect(migrationDescription(migration({description: null, tagline: null}))).toBe(
      'Migrate from JS to TS.',
    );
  });

  it('formats migration dry-run and applicability rows', () => {
    const result = {
      slug: 'm',
      name: 'Migration',
      estCostLowTotal: '1.20',
      estCostHighTotal: '2.40',
      estFilesLowTotal: 2,
      estFilesHighTotal: 5,
      sampleDiff: null,
      perRepo: [],
    };
    expect(dryRunFilesLabel(result)).toBe('2-5');
    expect(dryRunCostLabel(result)).toBe('$1.20 - $2.40');
    expect(applicabilityLabel('UNKNOWN_STACK', null)).toBe('unknown stack');
    expect(applicabilityLabel('UNKNOWN_STACK', 'No package file')).toBe('No package file');
    expect(applicabilityBadge('APPLICABLE')).toEqual({ok: true, label: 'applicable'});
    expect(applicabilityBadge('NOT_LINKED')).toEqual({ok: false, label: 'not linked'});
  });

  it('formats template project and preview labels', () => {
    expect(activeProjectName([project({id: 'p1', name: 'Core'})], 'p1')).toBe('Core');
    expect(activeProjectName([project({id: 'p1', name: 'Core'})], 'missing')).toBeUndefined();
    expect(
      templatePreview({
        id: 't1',
        ownerType: 'PROJECT',
        ownerId: 'p1',
        name: 'Template',
        description: 'Use for CRUD endpoints',
        requirementTemplate: 'Long body',
        createdAt: '2026-06-20T00:00:00Z',
      }),
    ).toBe('Use for CRUD endpoints');
  });
});

function project(overrides: Partial<api.ProjectDto>): api.ProjectDto {
  const item: api.ProjectDto = {
    id: 'p',
    orgId: 'org',
    name: 'Project',
    description: null,
    status: 'ACTIVE',
    aiProvider: 'ANTHROPIC',
    createdAt: '2026-06-20T00:00:00Z',
    ...overrides,
  };
  return item;
}

function readymade(overrides: Partial<api.ReadymadeSolutionSummary>): api.ReadymadeSolutionSummary {
  const item: api.ReadymadeSolutionSummary = {
    id: 's1',
    slug: 'solution',
    name: 'Solution',
    tagline: null,
    description: 'Description',
    category: 'General',
    macroGroup: 'General',
    tags: null,
    kind: 'PRIMITIVE',
    tier: 'PRO',
    source: 'FIRST_PARTY',
    status: 'LISTED',
    iconUrl: null,
    thumbnailUrl: null,
    samplePrUrl: null,
    estFilesLow: null,
    estFilesHigh: null,
    estCreditsLow: null,
    estCreditsHigh: null,
    authorUserId: null,
    authorOrgId: null,
    createdAt: '2026-06-20T00:00:00Z',
    updatedAt: '2026-06-20T00:00:00Z',
    ...overrides,
  };
  return item;
}

function migration(overrides: Partial<api.MigrationRecipeSummary>): api.MigrationRecipeSummary {
  const item: api.MigrationRecipeSummary = {
    id: 'm1',
    slug: 'migration',
    name: 'Migration',
    tagline: 'Tagline',
    description: 'Description',
    sourceStack: 'JS',
    targetStack: 'TS',
    affectedFilesGlob: '**/*.js',
    category: 'Language',
    tags: null,
    tier: 'PRO',
    source: 'FIRST_PARTY',
    status: 'LISTED',
    estFilesLow: null,
    estFilesHigh: null,
    estCreditsLow: '2',
    estCreditsHigh: '4',
    iconUrl: null,
    thumbnailUrl: null,
    samplePrUrl: null,
    createdAt: '2026-06-20T00:00:00Z',
    updatedAt: '2026-06-20T00:00:00Z',
    ...overrides,
  };
  return item;
}

function variant(overrides: Partial<api.ReadymadeVariant>): api.ReadymadeVariant {
  const item: api.ReadymadeVariant = {
    id: 'v',
    solutionId: 's1',
    language: 'TypeScript',
    framework: 'React',
    options: null,
    specTemplate: '',
    scaffoldKey: null,
    estCreditsLow: null,
    estCreditsHigh: null,
    createdAt: '2026-06-20T00:00:00Z',
    ...overrides,
  };
  return item;
}
