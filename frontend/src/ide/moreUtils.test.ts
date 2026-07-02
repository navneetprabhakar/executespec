import {describe, expect, it} from 'vitest';

import {
  accountDisplayName,
  accountEmail,
  billingRows,
  costOf,
  developerRows,
  filterRuns,
  isLiveStatus,
  limitRows,
  notificationChannelLabel,
  notificationPreferenceLabel,
  organizationRows,
  runBucket,
  runCounts,
  runDetailPath,
  runFilterTabs,
  scoreColor,
  scoreOf,
  searchRuns,
  usageLabel,
} from './moreUtils';
import type {TierPermissions} from '@/hooks/useTierPermissions';
import type * as api from '@/lib/api';

describe('moreUtils', () => {
  it('buckets every run into exactly one work-list bucket', () => {
    const runs = [
      run({runId: 'live-1', status: 'RUNNING'}),
      run({runId: 'ship-1', status: 'COMPLETE', verifiedScore: 92}),
      run({runId: 'need-1', status: 'COMPLETE', verifiedScore: 72}),
      run({runId: 'done-1', status: 'COMPLETED', verifiedScore: null}),
      run({runId: 'fail-1', status: 'FAILED'}),
      run({runId: 'part-1', status: 'PARTIAL', verifiedScore: 88}),
    ];

    expect(runs.map(runBucket)).toEqual(['live', 'shipped', 'needs', 'done', 'failed', 'needs']);
    expect(runCounts(runs)).toEqual({
      all: 6,
      live: 1,
      shipped: 1,
      needs: 2,
      done: 1,
      failed: 1,
    });
    expect(runFilterTabs(runCounts(runs)).map((tab) => `${tab.key}:${tab.n}`)).toEqual([
      'all:6',
      'live:1',
      'shipped:1',
      'needs:2',
      'done:1',
      'failed:1',
    ]);
    expect(filterRuns(runs, 'needs').map((r) => r.runId)).toEqual(['need-1', 'part-1']);
  });

  it('searches runs by title, request, and R-prefixed id', () => {
    const runs = [
      run({runId: 'abcdef123', runTitle: 'Checkout fix', userRequest: 'Payment'}),
      run({runId: 'zzzz99999', runTitle: 'Search', userRequest: 'Improve checkout'}),
    ];

    expect(searchRuns(runs, 'checkout').map((r) => r.runId)).toEqual(['abcdef123', 'zzzz99999']);
    expect(searchRuns(runs, 'R-abc').map((r) => r.runId)).toEqual(['abcdef123']);
    expect(searchRuns(null, 'anything')).toEqual([]);
  });

  it('derives run links, score/cost values, and score colors', () => {
    const live = run({runId: 'live-1', status: 'PROVIDER_SWITCHING', verifiedScore: null});
    const done = run({runId: 'done-1', status: 'COMPLETE', verifiedScore: 84, actualCostUsd: 1.23});

    expect(isLiveStatus(live.status)).toBe(true);
    expect(runDetailPath(live)).toBe('/live/live-1');
    expect(runDetailPath(done)).toBe('/result/done-1');
    expect(scoreOf(done)).toBe(84);
    expect(costOf(done)).toBe(1.23);
    expect(scoreColor(null)).toBe('var(--ide-fg-muted)');
    expect(scoreColor(92)).toBe('var(--ide-green)');
    expect(scoreColor(75)).toBe('var(--ide-yellow)');
    expect(scoreColor(40)).toBe('var(--ide-red)');
  });

  it('formats account display and permission limit rows', () => {
    expect(accountEmail(null)).toBe('-');
    expect(accountDisplayName(user({firstName: 'Ada', lastName: 'Lovelace'}))).toBe('Ada Lovelace');
    expect(
      accountDisplayName(user({firstName: '', lastName: '', email: 'forge@example.com'})),
    ).toBe('forge');

    expect(usageLabel(null)).toBe('- / -');
    expect(usageLabel(permissions({currentRunCount: 4, monthlyRunLimit: -1}))).toBe('4 / ∞');
    expect(
      usageLabel(
        permissions({
          currentRunCount: 4,
          monthlyRunLimit: -1,
          quotaModel: 'MANAGED_AI_CREDITS',
          includedAiCredits: 2000,
          unlimitedAiCredits: false,
        }),
      ),
    ).toBe('4 / AI credits');
    expect(
      limitRows(permissions({maxOrgMembers: -1, canDeploy: true, maxDeployments: -1})),
    ).toEqual(
      expect.arrayContaining([
        {k: 'Monthly work cap', v: '30'},
        {k: 'Max org seats', v: 'Unlimited'},
        {k: 'Deploy engine', v: 'Up to ∞'},
      ]),
    );
    expect(
      limitRows(
        permissions({
          monthlyRunLimit: -1,
          quotaModel: 'MANAGED_AI_CREDITS',
          includedAiCredits: 2000,
          unlimitedAiCredits: false,
        }),
      ),
    ).toEqual(expect.arrayContaining([{k: 'Monthly work cap', v: 'AI credits'}]));
  });

  it('builds account management rows from tier and role', () => {
    expect(billingRows('PRO')[0]).toEqual({
      to: '/billing',
      label: 'Usage & billing',
      sub: 'PRO tier',
    });
    expect(organizationRows(user({orgRole: 'DEVELOPER'})).map((row) => row.to)).toEqual([
      '/org-members',
    ]);
    expect(organizationRows(user({globalRole: 'SUPER_ADMIN'})).map((row) => row.to)).toEqual([
      '/org-members',
      '/org/settings',
    ]);
    expect(developerRows().map((row) => row.to)).toEqual([
      '/integrations',
      '/api-keys',
      '/deployments',
    ]);
  });

  it('normalizes notification preference labels', () => {
    expect(notificationPreferenceLabel('RUN_COMPLETED')).toBe('run completed');
    expect(notificationChannelLabel('IN_APP')).toBe('in_app channel');
  });
});

function run(overrides: Partial<api.RunSummary>): api.RunSummary {
  const item: api.RunSummary = {
    runId: 'run-1',
    status: 'COMPLETE',
    userRequest: 'Request',
    plannerModel: 'planner',
    workerModel: 'worker',
    reviewerModel: 'reviewer',
    totalInputTokens: 0,
    totalOutputTokens: 0,
    startedAt: '2026-06-20T00:00:00Z',
    completedAt: null,
    ...overrides,
  };
  return item;
}

function user(overrides: Partial<api.AuthUser>): api.AuthUser {
  const item: api.AuthUser = {
    id: 'user-1',
    email: 'ada@example.com',
    firstName: 'Ada',
    lastName: 'Lovelace',
    globalRole: 'USER',
    tier: 'PRO',
    status: 'ACTIVE',
    mustChangePassword: false,
    emailVerified: true,
    orgId: 'org-1',
    orgRole: 'DEVELOPER',
    ...overrides,
  };
  return item;
}

function permissions(overrides: Partial<TierPermissions>): TierPermissions {
  const item: TierPermissions = {
    canManageAiKeys: true,
    canViewPricing: true,
    canManageIntegrations: true,
    canViewMetrics: true,
    canViewCostBreakdown: true,
    canViewTokenUsage: true,
    canUseChat: true,
    canCreateOrg: true,
    canInviteMembers: true,
    canViewOrgMembers: true,
    canViewAudit: true,
    canViewLiveLog: true,
    canViewInvoices: true,
    canViewAccounting: false,
    canManageCreditNotes: false,
    canExportGstr: false,
    canManageSellerConfig: false,
    canManageBillingProfile: false,
    monthlyRunLimit: 30,
    currentRunCount: 0,
    maxOrgMembers: 5,
    currentOrgMembers: 1,
    maxApplySuggestions: 5,
    maxPreviewStarts: 5,
    canDeploy: false,
    maxDeployments: 0,
    ...overrides,
  };
  return item;
}
