import {describe, expect, it} from 'vitest';

import {adminWriteConfirmation} from './adminSafety';

describe('adminWriteConfirmation', () => {
  it('requires explicit confirmation for user entitlement changes', () => {
    const copy = adminWriteConfirmation({
      kind: 'user-tier',
      email: 'buyer@example.com',
      from: 'PRO',
      to: 'TEAM',
    });

    expect(copy).toMatchObject({
      title: 'Change user tier',
      confirmLabel: 'Change tier',
      danger: false,
    });
    expect(copy.body).toContain('buyer@example.com');
    expect(copy.body).toContain('PRO');
    expect(copy.body).toContain('TEAM');
    expect(copy.body).toContain('immediately');
  });

  it('treats SUPER_ADMIN role changes as dangerous', () => {
    expect(
      adminWriteConfirmation({
        kind: 'user-role',
        email: 'ops@example.com',
        from: 'USER',
        to: 'SUPER_ADMIN',
      }).danger,
    ).toBe(true);
  });

  it('keeps suspend copy explicit for users and orgs', () => {
    expect(
      adminWriteConfirmation({
        kind: 'user-status',
        email: 'dev@example.com',
        from: 'ACTIVE',
        to: 'SUSPENDED',
      }),
    ).toMatchObject({
      title: 'Suspend user',
      confirmLabel: 'Suspend',
      danger: true,
    });

    const orgCopy = adminWriteConfirmation({
      kind: 'org-status',
      name: 'Acme',
      from: 'ACTIVE',
      to: 'SUSPENDED',
    });

    expect(orgCopy.danger).toBe(true);
    expect(orgCopy.body).toContain('All members lose access');
  });
});
