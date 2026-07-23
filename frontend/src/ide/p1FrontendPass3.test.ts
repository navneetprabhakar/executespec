import {describe, expect, it} from 'vitest';
import {openPrsFromDashboard, shareLinkStatus} from '@/ide/projectMoreUtils';
import type {ShareLinkDto} from '@/lib/api';

// RR-747 — the PR tab must derive open PRs from the dashboard's `recentPrs`, never the invented
// `openPrs` field the server does not return.
describe('RR-747 openPrsFromDashboard', () => {
  it('returns [] when the dashboard has no recentPrs (and no phantom openPrs)', () => {
    expect(openPrsFromDashboard({openPrs: [{status: 'OPEN'}]})).toEqual([]);
    expect(openPrsFromDashboard({})).toEqual([]);
    expect(openPrsFromDashboard(null)).toEqual([]);
  });

  it('filters recentPrs down to OPEN entries (status or state)', () => {
    const dash = {
      recentPrs: [
        {id: 1, status: 'OPEN'},
        {id: 2, status: 'MERGED'},
        {id: 3, state: 'open'},
        {id: 4, status: 'CLOSED'},
      ],
    };
    const open = openPrsFromDashboard(dash);
    expect(open.map((p) => p.id)).toEqual([1, 3]);
  });
});

// RR-1511 — a share link's lifecycle must render as an explicit Active / Revoked / Expired state.
describe('RR-1511 shareLinkStatus', () => {
  const base: ShareLinkDto = {
    id: 'l1',
    projectId: 'p1',
    token: null,
    tokenPrefix: 'abcd',
    surface: 'PROJECT_OVERVIEW' as ShareLinkDto['surface'],
    scopeId: null,
    label: null,
    createdByUserId: 'u1',
    expiresAt: null,
    revokedAt: null,
    views: 0,
    lastViewedAt: null,
    createdAt: new Date().toISOString(),
    active: true,
  };

  it('reports Revoked when revokedAt is set or active is false', () => {
    expect(shareLinkStatus({...base, revokedAt: new Date().toISOString()})).toBe('Revoked');
    expect(shareLinkStatus({...base, active: false})).toBe('Revoked');
  });

  it('reports Expired for a past expiry on an otherwise-active link', () => {
    expect(shareLinkStatus({...base, expiresAt: '2000-01-01T00:00:00Z'})).toBe('Expired');
  });

  it('reports Active for a live, unexpired, non-revoked link', () => {
    const future = new Date(Date.now() + 86_400_000).toISOString();
    expect(shareLinkStatus({...base, expiresAt: future})).toBe('Active');
    expect(shareLinkStatus(base)).toBe('Active');
  });

  it('treats revocation as authoritative even when not yet expired', () => {
    const future = new Date(Date.now() + 86_400_000).toISOString();
    expect(shareLinkStatus({...base, expiresAt: future, active: false})).toBe('Revoked');
  });
});
