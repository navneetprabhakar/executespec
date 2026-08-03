import {act} from 'react';
import {createRoot, type Root} from 'react-dom/client';
import {MemoryRouter, Route, Routes} from 'react-router-dom';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

const apiMocks = vi.hoisted(() => ({
  getFilesWithStatus: vi.fn(),
  getPublicationEligibility: vi.fn(),
  getRun: vi.fn(),
  getValidationReport: vi.fn(),
}));

vi.mock('@/lib/api', () => apiMocks);

const authState = {
  user: {id: 'u1', email: 'demo@example.com', globalRole: 'USER'},
  permissions: {},
};

vi.mock('@/stores/useAuthStore', () => ({
  useAuthStore: (selector: (state: typeof authState) => unknown) => selector(authState),
  selectIsSuperAdmin: () => false,
}));

vi.mock('@/ide/useProviderReadiness', () => ({
  useProviderReadiness: () => ({supported: false, rows: [], loading: false, failure: null}),
}));

import IdeRunResult from './IdeRunResult';

(globalThis as typeof globalThis & {IS_REACT_ACT_ENVIRONMENT?: boolean}).IS_REACT_ACT_ENVIRONMENT =
  true;

describe('IdeRunResult terminal report loading', () => {
  let host: HTMLDivElement;
  let root: Root;

  beforeEach(() => {
    vi.useFakeTimers();
    host = document.createElement('div');
    document.body.appendChild(host);
    root = createRoot(host);
    apiMocks.getRun.mockReset().mockResolvedValue({
      runId: 'run-1',
      status: 'FAILED',
      runTitle: 'Terminal run',
      userRequest: 'Make a change',
      tasks: [],
      terminalOutcome: {
        schemaVersion: 1,
        code: 'INTERNAL_ERROR',
        category: 'PLATFORM_FAILURE',
        customerTitle: 'Run could not complete',
        customerMessage: 'An internal error stopped this run.',
        impact: {
          actualCostUsd: 0,
          capUsd: 1,
          codeChanged: false,
          publication: 'NOT_CREATED',
          prUrl: null,
        },
        retryable: true,
        nextAction: {type: 'CONTACT_SUPPORT', label: 'Contact support', href: null},
        supportCode: 'ES-RUN-1',
        automationDisposition: 'FAILED',
        automationExitCode: 1,
      },
    });
    apiMocks.getValidationReport.mockReset().mockRejectedValue({status: 404});
    apiMocks.getPublicationEligibility.mockReset().mockRejectedValue({status: 404});
    apiMocks.getFilesWithStatus.mockReset().mockResolvedValue([]);
  });

  afterEach(() => {
    act(() => root.unmount());
    host.remove();
    vi.useRealTimers();
  });

  it('requests a missing report once and does not start a terminal polling loop', async () => {
    await act(async () => {
      root.render(
        <MemoryRouter initialEntries={['/result/run-1']}>
          <Routes>
            <Route path='/result/:runId' element={<IdeRunResult />} />
          </Routes>
        </MemoryRouter>,
      );
      await Promise.resolve();
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(apiMocks.getValidationReport).toHaveBeenCalledTimes(1);
    expect(host.querySelectorAll('[aria-label="Primary outcome action"] .ide-btn')).toHaveLength(1);
    expect(host.textContent).toContain('Support code ES-RUN-1');

    await act(async () => {
      vi.advanceTimersByTime(5 * 60 * 1000);
      await Promise.resolve();
    });

    expect(apiMocks.getValidationReport).toHaveBeenCalledTimes(1);
  });

  it('renders USER_STOPPED as a neutral stop with one server-owned next action', async () => {
    apiMocks.getRun.mockResolvedValue({
      runId: 'run-1',
      status: 'CANCELLED',
      runTitle: 'Stopped run',
      userRequest: 'Make a change',
      tasks: [{taskId: 't1', title: 'Task', status: 'BLOCKED'}],
      terminalOutcome: {
        schemaVersion: 1,
        code: 'USER_CANCELLED',
        category: 'USER_STOPPED',
        customerTitle: 'Run cancelled',
        customerMessage: 'You cancelled this run.',
        impact: {
          actualCostUsd: 0,
          capUsd: 1,
          codeChanged: false,
          publication: 'NOT_CREATED',
          prUrl: null,
        },
        retryable: false,
        nextAction: {type: 'START_NEW_RUN', label: 'Start new run', href: '/compose'},
        supportCode: null,
        automationDisposition: 'STOPPED',
        automationExitCode: 3,
      },
    });

    await act(async () => {
      root.render(
        <MemoryRouter initialEntries={['/result/run-1']}>
          <Routes>
            <Route path='/result/:runId' element={<IdeRunResult />} />
          </Routes>
        </MemoryRouter>,
      );
      await Promise.resolve();
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(host.textContent).toContain('Run stopped');
    expect(host.textContent).toContain('You cancelled this run.');
    expect(host.textContent).not.toContain('Recovery path');
    expect(host.textContent).not.toContain('blocked step');
    const primary = host.querySelectorAll<HTMLAnchorElement>(
      '[aria-label="Primary outcome action"] .ide-btn',
    );
    expect(primary).toHaveLength(1);
    expect(primary[0].textContent).toBe('Start new run');
    expect(primary[0].getAttribute('href')).toBe('/compose');
  });
});
