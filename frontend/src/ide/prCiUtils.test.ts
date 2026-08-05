import {describe, expect, it} from 'vitest';

import {prCiBadge, webhookCiNotice, WEBHOOK_NOT_INSTALLED} from './prCiUtils';

describe('prCiBadge', () => {
  it('renders nothing when the server sent no ciStatus (older servers / pre-poll)', () => {
    expect(prCiBadge({})).toBeNull();
    expect(prCiBadge({ciStatus: undefined})).toBeNull();
  });

  it('maps CI verdicts to badge kinds', () => {
    expect(prCiBadge({ciStatus: 'SUCCESS'})).toEqual({label: 'CI · passing', kind: 'ok'});
    expect(prCiBadge({ciStatus: 'PENDING'})).toEqual({label: 'CI · running', kind: 'warn'});
    expect(prCiBadge({ciStatus: 'NEUTRAL'})).toEqual({label: 'CI · neutral', kind: 'info'});
    expect(prCiBadge({ciStatus: 'UNKNOWN'})).toEqual({label: 'CI · unknown', kind: 'info'});
  });

  it('surfaces the failing-check count on FAILURE', () => {
    expect(prCiBadge({ciStatus: 'FAILURE', ciFailingCount: 3})).toEqual({
      label: 'CI · 3 failing',
      kind: 'bad',
    });
    expect(prCiBadge({ciStatus: 'FAILURE'})).toEqual({label: 'CI · failing', kind: 'bad'});
  });
});

describe('webhookCiNotice', () => {
  it('is silent when the server sent no reason (older servers keep the current rendering)', () => {
    expect(webhookCiNotice({ciStatus: 'UNKNOWN'})).toBeNull();
    expect(webhookCiNotice({})).toBeNull();
    expect(webhookCiNotice({ciStatus: 'UNKNOWN', ciStatusReason: null})).toBeNull();
  });

  it('is silent for any real CI verdict even when the reason field is stale', () => {
    expect(
      webhookCiNotice({ciStatus: 'SUCCESS', ciStatusReason: WEBHOOK_NOT_INSTALLED}),
    ).toBeNull();
    expect(
      webhookCiNotice({ciStatus: 'FAILURE', ciStatusReason: WEBHOOK_NOT_INSTALLED}),
    ).toBeNull();
  });

  it('explains an UNKNOWN status caused by a missing webhook and carries the repo id', () => {
    const notice = webhookCiNotice({
      ciStatus: 'UNKNOWN',
      ciStatusReason: WEBHOOK_NOT_INSTALLED,
      repoId: 'repo-1',
    });
    expect(notice?.message).toContain('repository webhook not installed');
    expect(notice?.repoId).toBe('repo-1');
  });

  it('treats an absent ciStatus with the reason as UNKNOWN and survives a missing repoId', () => {
    const notice = webhookCiNotice({ciStatusReason: WEBHOOK_NOT_INSTALLED});
    expect(notice?.message).toContain('CI status unavailable');
    expect(notice?.repoId).toBeNull();
  });

  it('ignores unrelated reason codes', () => {
    expect(webhookCiNotice({ciStatus: 'UNKNOWN', ciStatusReason: 'SOMETHING_ELSE'})).toBeNull();
  });
});
