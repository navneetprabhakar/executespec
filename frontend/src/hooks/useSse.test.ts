import {act} from 'react';
import {createRoot, type Root} from 'react-dom/client';
import {createElement} from 'react';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

// --- Fake EventSource -------------------------------------------------------
class FakeEventSource {
  static instances: FakeEventSource[] = [];
  url: string;
  onopen: (() => void) | null = null;
  onmessage: ((ev: MessageEvent) => void) | null = null;
  onerror: (() => void) | null = null;
  closed = false;
  constructor(url: string) {
    this.url = url;
    FakeEventSource.instances.push(this);
  }
  close() {
    this.closed = true;
  }
}

// --- Module mocks (hoisted) -------------------------------------------------
vi.mock('@/lib/api', () => ({
  getSseToken: vi.fn(() => Promise.resolve({token: 'scoped-token'})),
  getRunEventLog: vi.fn(() => Promise.resolve({events: [], lastEventId: null})),
  getMessages: vi.fn(() => Promise.resolve([])),
  getRun: vi.fn(() => Promise.resolve({})),
}));
vi.mock('@/lib/runReplay', () => ({
  replayEntry: (e: unknown) => e,
  replayNotice: () => null,
  runEventKey: (_d: unknown, id?: string | null) => id ?? 'k',
}));

import * as api from '@/lib/api';
import {useSse} from './useSse';
import {useSseStore} from '@/stores/useSseStore';

const getSseTokenMock = api.getSseToken as unknown as ReturnType<typeof vi.fn>;

(globalThis as typeof globalThis & {IS_REACT_ACT_ENVIRONMENT?: boolean}).IS_REACT_ACT_ENVIRONMENT =
  true;

function Harness({runId}: {runId: string | null}) {
  useSse(runId);
  return null;
}

let host: HTMLDivElement;
let root: Root;

async function flushMicrotasks() {
  for (let i = 0; i < 12; i++) await Promise.resolve();
}

beforeEach(() => {
  vi.useFakeTimers();
  FakeEventSource.instances = [];
  getSseTokenMock.mockClear();
  getSseTokenMock.mockImplementation(() => Promise.resolve({token: 'scoped-token'}));
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  (globalThis as any).EventSource = FakeEventSource;
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
  useSseStore.setState({status: 'DISCONNECTED', currentRunId: null});
});

afterEach(() => {
  act(() => root.unmount());
  host.remove();
  vi.useRealTimers();
});

describe('useSse — RR-202 reconnect backoff', () => {
  it('does NOT reset the backoff on a sub-threshold open (climbs toward the ceiling)', async () => {
    await act(async () => {
      root.render(createElement(Harness, {runId: 'r1'}));
    });
    // Resolve getRunEventLog -> connect() -> getSseToken -> first EventSource.
    await act(async () => {
      await flushMicrotasks();
    });
    expect(FakeEventSource.instances.length).toBe(1);
    const es0 = FakeEventSource.instances[0];

    // Open, then fail well before STABLE_MS (15s). Backoff must NOT reset here.
    await act(async () => {
      es0.onopen?.();
    });
    await act(async () => {
      es0.onerror?.();
    });

    // Reconnect #1 is scheduled at ~1000-1250ms. Advance past it.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1300);
    });
    expect(FakeEventSource.instances.length).toBe(2);
    const es1 = FakeEventSource.instances[1];

    // Second open+error, again before STABLE_MS.
    await act(async () => {
      es1.onopen?.();
    });
    await act(async () => {
      es1.onerror?.();
    });

    // If the backoff had reset on open, reconnect #2 would fire at ~1000ms.
    // With the fix it climbed to ~2000ms, so 1300ms must NOT be enough yet.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1300);
    });
    expect(FakeEventSource.instances.length).toBe(2); // still waiting — proves no reset

    // Advancing past the ~2000ms climbed delay finally reconnects.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1200);
    });
    expect(FakeEventSource.instances.length).toBe(3);
  });
});

describe('useSse — RR-203 required scoped token', () => {
  it('does not open an unauthenticated EventSource when the token fetch fails', async () => {
    getSseTokenMock.mockRejectedValueOnce(new Error('token 500'));

    await act(async () => {
      root.render(createElement(Harness, {runId: 'r2'}));
    });
    await act(async () => {
      await flushMicrotasks();
    });

    // No EventSource was created — the hook scheduled a retry instead of
    // degrading to an unauthenticated stream.
    expect(FakeEventSource.instances.length).toBe(0);
    expect(useSseStore.getState().status).toBe('RECONNECTING');
  });
});

describe('useSse — terminal stream closure', () => {
  async function connectedSource(runId: string) {
    await act(async () => {
      root.render(createElement(Harness, {runId}));
      await flushMicrotasks();
    });
    const source = FakeEventSource.instances[0];
    expect(source).toBeDefined();
    await act(async () => source.onopen?.());
    return source;
  }

  it.each([
    ['RUN_STATUS', 'FAILED'],
    ['COMPLETE', 'FAILED|BUDGET_EXCEEDED'],
    ['RUN_STATUS', 'CANCELLED'],
  ])('closes without reconnecting for %s %s', async (type, payload) => {
    const source = await connectedSource(`terminal-${type}-${payload}`);

    await act(async () => {
      source.onmessage?.({
        data: JSON.stringify({type, payload}),
        lastEventId: 'terminal-1',
      } as MessageEvent);
      source.onerror?.();
      await vi.advanceTimersByTimeAsync(60_000);
    });

    expect(source.closed).toBe(true);
    expect(useSseStore.getState().status).toBe('DISCONNECTED');
    expect(FakeEventSource.instances).toHaveLength(1);
  });

  it.each([
    {
      label: 'legacy payload',
      event: {
        type: 'RUN_DETAIL',
        payload: JSON.stringify({runId: 'terminal-detail', status: 'PARTIAL'}),
      },
    },
    {
      label: 'typed data',
      event: {type: 'RUN_DETAIL', data: {runId: 'terminal-detail', status: 'FAILED'}},
    },
  ])('closes on a terminal RUN_DETAIL snapshot in $label', async ({event}) => {
    const source = await connectedSource('terminal-detail');

    await act(async () => {
      source.onmessage?.({
        data: JSON.stringify(event),
        lastEventId: 'terminal-detail-1',
      } as MessageEvent);
      source.onerror?.();
      await vi.advanceTimersByTimeAsync(60_000);
    });

    expect(source.closed).toBe(true);
    expect(useSseStore.getState().status).toBe('DISCONNECTED');
    expect(FakeEventSource.instances).toHaveLength(1);
  });
});
