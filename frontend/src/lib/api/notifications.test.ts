import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {getNotifications, type NotificationItem, type NotificationPrefRow} from './notifications';

describe('notifications API', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    const values = new Map<string, string>();
    vi.stubGlobal('localStorage', {
      clear: () => values.clear(),
      getItem: (key: string) => values.get(key) ?? null,
      setItem: (key: string, value: string) => values.set(key, value),
      removeItem: (key: string) => values.delete(key),
    } satisfies Pick<Storage, 'clear' | 'getItem' | 'setItem' | 'removeItem'>);
  });

  afterEach(() => vi.unstubAllGlobals());

  it('keeps notification DTOs domain-owned while preserving the API route', async () => {
    const item: NotificationItem = {
      id: 'n1',
      userId: 'u1',
      type: 'PLAN_READY',
      title: 'Plan ready',
      body: null,
      link: '/runs/r1',
      read: false,
      createdAt: '2026-07-13T00:00:00Z',
    };
    const preference: NotificationPrefRow = {
      eventType: 'PLAN_READY',
      tier: 'HIGH_SIGNAL',
      channel: 'IN_APP',
      enabled: true,
      defaultEnabled: true,
      override: false,
    };
    expect(preference.channel).toBe('IN_APP');
    vi.stubGlobal(
      'fetch',
      vi
        .fn<typeof fetch>()
        .mockResolvedValue(
          new Response(JSON.stringify({content: [item], page: 0, size: 20}), {status: 200}),
        ),
    );

    await expect(getNotifications()).resolves.toMatchObject({content: [item]});
    expect(fetch).toHaveBeenCalledWith('/api/notifications?page=0&size=20', expect.anything());
  });
});
