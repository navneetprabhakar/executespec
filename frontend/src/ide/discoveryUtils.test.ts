import {describe, expect, it} from 'vitest';

import {
  durationLabel,
  insightCards,
  outcomePercent,
  providerRows,
  topUserLabel,
} from './discoveryUtils';
import type * as api from '@/lib/api';

describe('discoveryUtils', () => {
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
});
