import {act} from 'react';
import {createRoot, type Root} from 'react-dom/client';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

const apiMocks = vi.hoisted(() => ({
  installRepoWebhook: vi.fn(),
  getRunPrs: vi.fn(),
}));

vi.mock('@/lib/api', () => apiMocks);

import {PrCiWebhookNotice, RunPrCiPanel} from './PrCiWebhook';

(globalThis as typeof globalThis & {IS_REACT_ACT_ENVIRONMENT?: boolean}).IS_REACT_ACT_ENVIRONMENT =
  true;

describe('PrCiWebhookNotice', () => {
  let host: HTMLDivElement;
  let root: Root;

  beforeEach(() => {
    host = document.createElement('div');
    document.body.appendChild(host);
    root = createRoot(host);
    apiMocks.installRepoWebhook.mockReset();
  });

  afterEach(async () => {
    await act(async () => root.unmount());
    host.remove();
  });

  it('renders nothing when the server sent no webhook reason (null-safe for older servers)', async () => {
    await act(async () => {
      root.render(<PrCiWebhookNotice pr={{ciStatus: 'UNKNOWN'}} />);
    });
    expect(host.textContent).toBe('');
  });

  it('explains the missing webhook and offers the install action', async () => {
    await act(async () => {
      root.render(
        <PrCiWebhookNotice
          pr={{ciStatus: 'UNKNOWN', ciStatusReason: 'WEBHOOK_NOT_INSTALLED', repoId: 'repo-1'}}
        />,
      );
    });
    expect(host.textContent).toContain('CI status unavailable — repository webhook not installed');
    const button = host.querySelector('button');
    expect(button?.textContent).toBe('Install webhook');
  });

  it('shows the explanation without an action when the server did not identify the repo', async () => {
    await act(async () => {
      root.render(
        <PrCiWebhookNotice pr={{ciStatus: 'UNKNOWN', ciStatusReason: 'WEBHOOK_NOT_INSTALLED'}} />,
      );
    });
    expect(host.textContent).toContain('repository webhook not installed');
    expect(host.querySelector('button')).toBeNull();
  });

  it('installs the webhook, reports success, and refreshes the caller', async () => {
    apiMocks.installRepoWebhook.mockResolvedValue({
      installed: true,
      eligibilityState: 'ELIGIBLE',
      retryable: false,
    });
    const onInstalled = vi.fn();
    await act(async () => {
      root.render(
        <PrCiWebhookNotice
          pr={{ciStatus: 'UNKNOWN', ciStatusReason: 'WEBHOOK_NOT_INSTALLED', repoId: 'repo-1'}}
          onInstalled={onInstalled}
        />,
      );
    });
    await act(async () => {
      host.querySelector('button')?.click();
    });
    expect(apiMocks.installRepoWebhook).toHaveBeenCalledWith('repo-1');
    expect(onInstalled).toHaveBeenCalledTimes(1);
    expect(host.textContent).toContain('Webhook installed');
    // The action disappears after success.
    expect(host.querySelector('button')).toBeNull();
  });

  it('treats ALREADY_INSTALLED as success', async () => {
    apiMocks.installRepoWebhook.mockResolvedValue({
      installed: false,
      eligibilityState: 'ALREADY_INSTALLED',
      retryable: false,
    });
    const onInstalled = vi.fn();
    await act(async () => {
      root.render(
        <PrCiWebhookNotice
          pr={{ciStatus: 'UNKNOWN', ciStatusReason: 'WEBHOOK_NOT_INSTALLED', repoId: 'repo-1'}}
          onInstalled={onInstalled}
        />,
      );
    });
    await act(async () => {
      host.querySelector('button')?.click();
    });
    expect(onInstalled).toHaveBeenCalledTimes(1);
    expect(host.textContent).toContain('Webhook installed');
  });

  it('surfaces a typed rejection without claiming success', async () => {
    apiMocks.installRepoWebhook.mockResolvedValue({
      installed: false,
      eligibilityState: 'BLOCKED',
      message: 'GitHub token lacks admin access.',
      retryable: false,
    });
    const onInstalled = vi.fn();
    await act(async () => {
      root.render(
        <PrCiWebhookNotice
          pr={{ciStatus: 'UNKNOWN', ciStatusReason: 'WEBHOOK_NOT_INSTALLED', repoId: 'repo-1'}}
          onInstalled={onInstalled}
        />,
      );
    });
    await act(async () => {
      host.querySelector('button')?.click();
    });
    expect(onInstalled).not.toHaveBeenCalled();
    expect(host.textContent).toContain('GitHub token lacks admin access.');
    // The action remains available for retry after an error.
    expect(host.querySelector('button')).not.toBeNull();
  });

  it('surfaces a thrown transport error', async () => {
    apiMocks.installRepoWebhook.mockRejectedValue(new Error('network down'));
    await act(async () => {
      root.render(
        <PrCiWebhookNotice
          pr={{ciStatus: 'UNKNOWN', ciStatusReason: 'WEBHOOK_NOT_INSTALLED', repoId: 'repo-1'}}
        />,
      );
    });
    await act(async () => {
      host.querySelector('button')?.click();
    });
    expect(host.textContent).toContain('network down');
  });
});

describe('RunPrCiPanel', () => {
  let host: HTMLDivElement;
  let root: Root;

  beforeEach(() => {
    host = document.createElement('div');
    document.body.appendChild(host);
    root = createRoot(host);
    apiMocks.getRunPrs.mockReset();
    apiMocks.installRepoWebhook.mockReset();
  });

  afterEach(async () => {
    await act(async () => root.unmount());
    host.remove();
  });

  it('renders nothing when the run has no PR rows or the read fails', async () => {
    apiMocks.getRunPrs.mockResolvedValue([]);
    await act(async () => {
      root.render(<RunPrCiPanel runId='run-1' />);
    });
    expect(host.textContent).toBe('');

    apiMocks.getRunPrs.mockRejectedValue(new Error('403'));
    await act(async () => {
      root.render(<RunPrCiPanel runId='run-2' />);
    });
    expect(host.textContent).toBe('');
  });

  it('lists PR rows with CI evidence and the webhook notice for UNKNOWN-no-webhook rows', async () => {
    apiMocks.getRunPrs.mockResolvedValue([
      {
        repoFullName: 'acme/api',
        prNumber: 12,
        prUrl: 'https://github.com/acme/api/pull/12',
        status: 'OPEN',
        ciStatus: 'UNKNOWN',
        ciStatusReason: 'WEBHOOK_NOT_INSTALLED',
        repoId: 'repo-9',
      },
      {
        repoFullName: 'acme/web',
        prNumber: 4,
        prUrl: 'https://github.com/acme/web/pull/4',
        status: 'OPEN',
        ciStatus: 'SUCCESS',
      },
    ]);
    await act(async () => {
      root.render(<RunPrCiPanel runId='run-1' />);
    });
    expect(host.textContent).toContain('acme/api#12');
    expect(host.textContent).toContain('acme/web#4');
    expect(host.textContent).toContain('CI · passing');
    expect(host.textContent).toContain('repository webhook not installed');
    const button = [...host.querySelectorAll('button')].find(
      (b) => b.textContent === 'Install webhook',
    );
    expect(button).toBeDefined();
  });

  it('refreshes the PR rows after a successful install', async () => {
    apiMocks.getRunPrs.mockResolvedValue([
      {
        repoFullName: 'acme/api',
        prNumber: 12,
        prUrl: 'https://github.com/acme/api/pull/12',
        status: 'OPEN',
        ciStatus: 'UNKNOWN',
        ciStatusReason: 'WEBHOOK_NOT_INSTALLED',
        repoId: 'repo-9',
      },
    ]);
    apiMocks.installRepoWebhook.mockResolvedValue({
      installed: true,
      eligibilityState: 'ELIGIBLE',
      retryable: false,
    });
    await act(async () => {
      root.render(<RunPrCiPanel runId='run-1' />);
    });
    expect(apiMocks.getRunPrs).toHaveBeenCalledTimes(1);
    await act(async () => {
      [...host.querySelectorAll('button')]
        .find((b) => b.textContent === 'Install webhook')
        ?.click();
    });
    expect(apiMocks.installRepoWebhook).toHaveBeenCalledWith('repo-9');
    expect(apiMocks.getRunPrs).toHaveBeenCalledTimes(2);
  });
});
