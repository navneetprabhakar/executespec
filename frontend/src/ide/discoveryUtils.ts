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

export function splitTags(tags: string | null | undefined): string[] {
  if (!tags) return [];
  return tags
    .split(/[,|]/)
    .map((t) => t.trim())
    .filter(Boolean)
    .slice(0, 4);
}

export function creditsLabel(
  low: string | null | undefined,
  high: string | null | undefined,
): string | null {
  const l = low != null && low !== '' ? Number(low) : null;
  const h = high != null && high !== '' ? Number(high) : null;
  if (l == null && h == null) return null;
  if (l != null && h != null && l !== h) return `${l}-${h} credits`;
  const n = l ?? h;
  return `${n} credit${n === 1 ? '' : 's'}`;
}

export function activeProjects(projects: api.ProjectDto[]): api.ProjectDto[] {
  return projects.filter((x) => !x.archivedAt && !x.deletedAt);
}

export function libraryCategories(
  facets: api.LibraryFacets | null,
): Array<[category: string, count: number]> {
  return Object.entries(facets?.categories ?? {}).sort((a, b) => b[1] - a[1]);
}

export function categoryTotal(categories: Array<[string, number]>): number {
  return categories.reduce((sum, [, n]) => sum + n, 0);
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

export function variantLabel(v: Pick<api.ReadymadeVariant, 'language' | 'framework'>): string {
  return [v.language, v.framework].filter(Boolean).join(' · ') || 'variant';
}

export function librarySubtitle(
  solution: api.ReadymadeSolutionSummary | null | undefined,
  credits: string | null,
): string {
  if (!solution) return 'Library solution';
  return [solution.category, credits].filter(Boolean).join(' · ') || 'Library solution';
}

export function solutionDescription(
  solution: api.ReadymadeSolutionSummary | null | undefined,
): string {
  return solution?.description || solution?.tagline || 'A readymade, spec-driven solution.';
}

export function sourceLabel(source: string | null | undefined): string {
  return source === 'FIRST_PARTY' ? 'First-party' : 'Community';
}

export function resolvedVariantMessage(resolved: api.VariantResolutionResult): string {
  if (resolved.bestMatch) {
    const stack =
      [resolved.detectedLanguage, resolved.detectedFramework].filter(Boolean).join(' / ') ||
      'a matching stack';
    return `Detected ${stack} - using the best-fit variant.`;
  }
  if (resolved.reason === 'STACK_NOT_DETECTED') {
    return "Couldn't detect the repo's stack - running the default variant.";
  }
  if (resolved.reason === 'NO_VARIANT_FOR_STACK') {
    return 'No variant matches this stack - running the default variant.';
  }
  return 'Running the default variant.';
}

export function estimatedFilesLabel(
  low: number | null | undefined,
  high: number | null | undefined,
  prefix = '',
): string | null {
  if (low == null && high == null) return null;
  const base = `${low ?? high}${high != null && high !== low ? `-${high}` : ''}`;
  return `${prefix}${base}`;
}

export function migrationCardMeta(recipe: api.MigrationRecipeSummary): {
  credits: string | null;
  fileMeta: string | null;
} {
  return {
    credits: creditsLabel(recipe.estCreditsLow, recipe.estCreditsHigh),
    fileMeta:
      estimatedFilesLabel(recipe.estFilesLow, recipe.estFilesHigh, '~')?.concat(' files') ?? null,
  };
}

export function migrationDescription(
  recipe: api.MigrationRecipeSummary | null | undefined,
): string {
  return (
    recipe?.description ||
    recipe?.tagline ||
    `Migrate from ${recipe?.sourceStack} to ${recipe?.targetStack}.`
  );
}

export function dryRunFilesLabel(result: api.MigrationDryRunResult): string {
  return result.estFilesLowTotal != null || result.estFilesHighTotal != null
    ? `${result.estFilesLowTotal ?? result.estFilesHighTotal}-${result.estFilesHighTotal ?? result.estFilesLowTotal}`
    : '-';
}

export function dryRunCostLabel(result: api.MigrationDryRunResult): string {
  return result.estCostLowTotal != null || result.estCostHighTotal != null
    ? `$${result.estCostLowTotal ?? result.estCostHighTotal} - $${
        result.estCostHighTotal ?? result.estCostLowTotal
      }`
    : '-';
}

export function applicabilityLabel(status: string, reason?: string | null): string {
  return reason || status.replace(/_/g, ' ').toLowerCase();
}

export function applicabilityBadge(status: string): {ok: boolean; label: string} {
  const ok = status === 'APPLICABLE';
  return {ok, label: ok ? 'applicable' : status.replace(/_/g, ' ').toLowerCase()};
}

export function templatePreview(t: api.SpecTemplate): string {
  return (t.description || t.requirementTemplate || '').slice(0, 60);
}

export function activeProjectName(
  projects: api.ProjectDto[],
  projectId: string | null,
): string | undefined {
  return projects.find((p) => p.id === projectId)?.name;
}
