import type * as api from '@/lib/api';

export interface InsightCards {
  total: number;
  shipped: number;
  needs: number;
  failed: number;
  metricsLoaded: boolean;
  avgScore: string;
  avgDuration: string;
  spend: string;
}

export function insightCards(metrics: api.MetricsResponse | null): InsightCards {
  const mAny = (metrics || {}) as unknown as {
    totalRuns?: number;
    avgVerifiedScore?: number;
    avgDurationMs?: number;
    totalSpend?: number;
    shipped?: number;
    needs?: number;
    failed?: number;
  };
  const metricsLoaded = metrics != null;
  const avgScore =
    mAny.avgVerifiedScore != null
      ? `${mAny.avgVerifiedScore.toFixed(1)}%`
      : metricsLoaded
        ? '0%'
        : '-';
  const avgDuration =
    mAny.avgDurationMs != null ? durationLabel(mAny.avgDurationMs) : metricsLoaded ? '0m 0s' : '-';
  const spend =
    mAny.totalSpend != null ? `$${mAny.totalSpend.toFixed(2)}` : metricsLoaded ? '$0.00' : '-';
  return {
    total: mAny.totalRuns ?? 0,
    shipped: mAny.shipped ?? 0,
    needs: mAny.needs ?? 0,
    failed: mAny.failed ?? 0,
    metricsLoaded,
    avgScore,
    avgDuration,
    spend,
  };
}

export function durationLabel(ms: number): string {
  const m = Math.floor(ms / 60000);
  const s = Math.floor((ms % 60000) / 1000);
  return `${m}m ${s}s`;
}

export function providerRows(
  byProvider: api.ModelComparisonData | null,
): Array<{provider: string; value: string}> {
  const mcAny = (byProvider || {}) as unknown as {
    byProvider?: Array<{provider?: string; spend?: number; cacheHitRate?: number}>;
  };
  return (mcAny.byProvider || []).map((p) => ({
    provider: p.provider ?? '-',
    value: `${p.spend != null ? `$${p.spend.toFixed(2)}` : '-'}${
      p.cacheHitRate != null ? ` (${Math.round(p.cacheHitRate * 100)}% cache)` : ''
    }`,
  }));
}

export function outcomePercent(n: number, total: number): number {
  return total > 0 ? Math.round((n / total) * 100) : 0;
}

export function topUserLabel(summary: api.UserRunsSummaryData | null): {
  name: string;
  runCount: number;
  successRate?: number;
} | null {
  const topUser = (summary?.users || [])[0] as unknown as
    | {
        firstName?: string;
        lastName?: string;
        email?: string;
        runCount?: number;
        successRate?: number;
      }
    | undefined;
  if (!topUser) return null;
  return {
    name: topUser.email || `${topUser.firstName ?? ''} ${topUser.lastName ?? ''}`.trim() || '-',
    runCount: topUser.runCount ?? 0,
    successRate: topUser.successRate,
  };
}
