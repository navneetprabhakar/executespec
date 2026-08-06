import {describe, expect, it} from 'vitest';

import {
  activeProjects,
  auditAccessErrorMessage,
  auditActor,
  auditCsvText,
  auditRowState,
  auditTarget,
  auditTimestamp,
  buildSearchHits,
  customerSafeNotificationText,
  exactTime,
  filterAuditLogs,
  filterNotifications,
  isNotificationUnread,
  markAllNotificationsReadLocal,
  markNotificationReadLocal,
  notificationDotColor,
  notificationRowState,
  notificationTarget,
  setupAccountRows,
  shortAge,
  setupEssentialRows,
  setupOperationRows,
  setupWorkspaceRows,
  unreadNotificationCount,
} from './systemUtils';
import type * as api from '@/lib/api';

type TestNotification = api.NotificationItem & {severity?: string; readAt?: string};
type TestProject = api.ProjectDto & {repoCount?: number; totalRuns?: number};

describe('systemUtils', () => {
  it('hides raw provider spend from customer notification copy', () => {
    expect(customerSafeNotificationText('Completed · $0.10 · 19s', false)).toBe('Completed · 19s');
    expect(customerSafeNotificationText('Completed · $0.10 · 19s', true)).toBe(
      'Completed · $0.10 · 19s',
    );
  });
  it('filters notifications and derives read state, targets, and severity color', () => {
    const items = [
      notification({id: 'n1', type: 'PLAN_READY', read: false, severity: 'warn'}),
      notification({id: 'n2', type: 'MEMBER_INVITED', read: true}),
      notification({id: 'n3', type: 'BILLING_CAP_REACHED', read: false, link: '/billing/cap'}),
      notification({id: 'n4', type: 'FEATURE_FLAG_CHANGED', readAt: '2026-06-20T00:00:00Z'}),
    ];

    expect(filterNotifications(items, 'run').map((n) => n.id)).toEqual(['n1']);
    expect(filterNotifications(items, 'team').map((n) => n.id)).toEqual(['n2']);
    expect(filterNotifications(items, 'billing').map((n) => n.id)).toEqual(['n3']);
    expect(filterNotifications(items, 'system').map((n) => n.id)).toEqual(['n4']);
    expect(unreadNotificationCount(items)).toBe(2);
    expect(isNotificationUnread(items[0])).toBe(true);
    expect(notificationDotColor(items[0])).toBe('var(--ide-yellow)');
    expect(notificationTarget(items[0])).toBe('/runs');
    expect(notificationTarget(items[1])).toBe('/org-members');
    expect(notificationTarget(items[2])).toBe('/billing/cap');
  });

  it('updates notification read state locally without mutating unrelated rows', () => {
    const items = [notification({id: 'n1', read: false}), notification({id: 'n2', read: false})];

    expect(markNotificationReadLocal(items, 'n2')?.map((n) => n.read)).toEqual([false, true]);
    expect(markAllNotificationsReadLocal(items)?.map((n) => n.read)).toEqual([true, true]);
  });

  it('maps terminal-outcome notification types to honest severity colors', () => {
    expect(notificationDotColor(notification({type: 'RUN_PLATFORM_FAILURE'}))).toBe(
      'var(--ide-red)',
    );
    expect(notificationDotColor(notification({type: 'RUN_ACTION_REQUIRED'}))).toBe(
      'var(--ide-yellow)',
    );
    expect(notificationDotColor(notification({type: 'RUN_WARNING'}))).toBe('var(--ide-yellow)');
    expect(notificationDotColor(notification({type: 'RUN_USER_STOPPED'}))).toBe(
      'var(--ide-fg-muted)',
    );
    expect(notificationDotColor(notification({type: 'RUN_SUCCESS'}))).toBe('var(--ide-green)');
  });

  it('builds search hits with live-route selection and project counters', () => {
    const hits = buildSearchHits(
      'checkout',
      [
        run({runId: 'abcdef123456', status: 'RUNNING', userRequest: 'Fix checkout flow'}),
        run({runId: 'done999999', status: 'COMPLETE', userRequest: 'Checkout copy update'}),
      ],
      [project({id: 'proj-1', name: 'Checkout', repoCount: 2, totalRuns: 7})],
    );

    expect(hits).toEqual([
      expect.objectContaining({
        id: 'abcdef123456',
        kind: 'run',
        subtitle: 'R-abcdef · running',
        to: '/live/abcdef123456',
      }),
      expect.objectContaining({
        id: 'done999999',
        kind: 'run',
        subtitle: 'R-done99 · complete',
        to: '/result/done999999',
      }),
      {
        id: 'proj-1',
        kind: 'project',
        title: 'Checkout',
        subtitle: '2 repos · 7 work items',
        to: '/projects/proj-1',
      },
    ]);
  });

  it('filters audit logs and derives display/export fields', () => {
    const logs = [
      audit({id: 'a1', eventType: 'USER_LOGIN', actorEmail: 'forge@example.com'}),
      audit({id: 'a2', eventType: 'BILLING_PAYMENT_CAPTURED', entityId: 'inv,123'}),
      audit({id: 'a3', eventType: 'MEMBER_INVITED', actorId: 'user-2'}),
      audit({id: 'a4', eventType: 'ADMIN_FLAG_CHANGED'}),
    ];

    expect(filterAuditLogs(logs, 'auth').map((l) => l.id)).toEqual(['a1']);
    expect(filterAuditLogs(logs, 'billing').map((l) => l.id)).toEqual(['a2']);
    expect(filterAuditLogs(logs, 'members').map((l) => l.id)).toEqual(['a3']);
    expect(filterAuditLogs(logs, 'admin').map((l) => l.id)).toEqual(['a4']);
    expect(auditActor(logs[2])).toBe('user-2');
    expect(auditTarget(logs[1])).toBe('inv,123');
    expect(auditTimestamp(logs[0])).toBe('2026-06-20T00:00:00Z');
    expect(auditCsvText([logs[1]])).toBe(
      'action,actor,target,createdAt\nBILLING_PAYMENT_CAPTURED,,"inv,123",2026-06-20T00:00:00Z',
    );
  });

  it('maps audit permission errors by tier and role', () => {
    expect(auditAccessErrorMessage('403 Forbidden', 'PRO', false)).toContain('TEAM+');
    expect(auditAccessErrorMessage('permission denied', 'TEAM', false)).toContain('org admins');
    expect(auditAccessErrorMessage('forbidden', 'TEAM', true)).toContain("isn't enabled");
    expect(auditAccessErrorMessage('network failed', 'TEAM', true)).toBe('network failed');
  });

  it('renders a compact age instead of a full locale timestamp', () => {
    // `06/08/2026, 09:20:58` claimed ~45% of a 375px row and truncated every
    // notification title to about eighteen characters.
    const now = Date.parse('2026-06-20T12:00:00Z');
    expect(shortAge('2026-06-20T11:59:30Z', now)).toBe('just now');
    expect(shortAge('2026-06-20T11:44:00Z', now)).toBe('16m ago');
    expect(shortAge('2026-06-20T09:00:00Z', now)).toBe('3h ago');
    expect(shortAge('2026-06-17T12:00:00Z', now)).toBe('3d ago');
    expect(shortAge('2026-06-06T12:00:00Z', now)).toBe('2w ago');
    // Beyond a month a relative label stops being informative — show a date.
    expect(shortAge('2026-01-04T12:00:00Z', now)).not.toContain('ago');
    // A clock skew that puts the row in the future must not render "-3m ago".
    expect(shortAge('2026-06-20T12:05:00Z', now)).toBe('just now');
    // Missing / unparseable timestamps render nothing, never `Invalid Date`.
    expect(shortAge(null, now)).toBe('');
    expect(shortAge('not-a-date', now)).toBe('');
    expect(exactTime(null)).toBe('');
    expect(exactTime('not-a-date')).toBe('');
    expect(exactTime('2026-06-20T12:00:00Z')).not.toBe('');
  });

  it('maps rows to the four leading-stripe states the CSS knows about', () => {
    expect(notificationRowState(notification({type: 'RUN_PLATFORM_FAILURE'}))).toBe('bad');
    expect(notificationRowState(notification({type: 'RUN_ACTION_REQUIRED'}))).toBe('warn');
    expect(notificationRowState(notification({type: 'RUN_SUCCESS'}))).toBe('ok');
    expect(notificationRowState(notification({type: 'RUN_USER_STOPPED'}))).toBe('info');

    expect(auditRowState(audit({eventType: 'API_KEY_REVOKED'}))).toBe('bad');
    expect(auditRowState(audit({eventType: 'BILLING_PAYMENT_CAPTURED'}))).toBe('warn');
    expect(auditRowState(audit({eventType: 'USER_LOGIN'}))).toBe('info');
    expect(auditRowState(audit({eventType: 'ORG_EXTERNAL_SHARING_CHANGED'}))).toBe('ok');
  });

  it('builds setup-menu rows from account state', () => {
    expect(
      activeProjects([project({id: 'p1'}), project({id: 'p2', archivedAt: 'now'})]).map(
        (p) => p.id,
      ),
    ).toEqual(['p1']);
    const essentialRows = setupEssentialRows('PRO');
    expect(essentialRows[essentialRows.length - 1]).toEqual({
      to: '/billing',
      label: 'Billing',
      sub: 'PRO tier',
    });
    expect(setupWorkspaceRows([project({id: 'p1'})], null, null)).toEqual([
      {to: '/projects', label: 'Projects', sub: '1 active project'},
      {to: '/runs', label: 'Work', sub: 'Loading...'},
      {to: '/notifications', label: 'Notifications', sub: '-'},
    ]);
    expect(setupAccountRows('forge@example.com')[0].sub).toBe('forge@example.com');
    expect(setupOperationRows('FREE', false, false).some((row) => row.to === '/audit')).toBe(false);
    expect(setupOperationRows('TEAM', false, false).some((row) => row.to === '/audit')).toBe(false);
    expect(setupOperationRows('FREE', false, true).some((row) => row.to === '/audit')).toBe(true);
    expect(setupOperationRows('FREE', true, false).some((row) => row.to === '/audit')).toBe(true);
  });
});

function notification(overrides: Partial<TestNotification>): TestNotification {
  const item: TestNotification = {
    id: 'n',
    userId: 'u',
    type: 'RUN_COMPLETE',
    title: 'Title',
    body: null,
    link: null,
    read: false,
    createdAt: '2026-06-20T00:00:00Z',
    ...overrides,
  };
  return item;
}

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

function project(
  overrides: Partial<api.ProjectDto> & {repoCount?: number; totalRuns?: number},
): TestProject {
  const item: TestProject = {
    id: 'project-1',
    orgId: 'org-1',
    name: 'Project',
    description: null,
    status: 'ACTIVE',
    aiProvider: 'ANTHROPIC',
    createdAt: '2026-06-20T00:00:00Z',
    ...overrides,
  };
  return item;
}

function audit(overrides: Partial<api.AuditLogEntry>): api.AuditLogEntry {
  return {
    id: 'audit-1',
    eventType: 'EVENT',
    entityType: 'ORG',
    entityId: 'entity-1',
    orgId: 'org-1',
    actorId: null,
    actorEmail: null,
    oldValue: null,
    newValue: null,
    metadata: null,
    createdAt: '2026-06-20T00:00:00Z',
    ...overrides,
  };
}
