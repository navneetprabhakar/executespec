import {act, createElement} from 'react';
import {createRoot, type Root} from 'react-dom/client';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {
  agentRunUnavailableMessage,
  parseAgentRunEvent,
  terminalStatusFromEvent,
  unavailableReason,
  useAgentRunStream,
} from './agentRunStream';

const api = vi.hoisted(() => ({
  getRun: vi.fn(),
  getLiveCost: vi.fn(),
  getSseToken: vi.fn(),
}));

vi.mock('@/lib/api', () => api);

(globalThis as typeof globalThis & {IS_REACT_ACT_ENVIRONMENT?: boolean}).IS_REACT_ACT_ENVIRONMENT =
  true;

class FakeEventSource {
  static instances: FakeEventSource[] = [];

  readonly url: string;
  onopen: ((event: Event) => void) | null = null;
  onmessage: ((event: MessageEvent<string>) => void) | null = null;
  onerror: ((event: Event) => void) | null = null;
  closed = false;

  constructor(url: string | URL) {
    this.url = String(url);
    FakeEventSource.instances.push(this);
  }

  open() {
    this.onopen?.(new Event('open'));
  }

  message(data: string, lastEventId = '') {
    this.onmessage?.(new MessageEvent('message', {data, lastEventId}));
  }

  fail() {
    this.onerror?.(new Event('error'));
  }

  close() {
    this.closed = true;
  }
}

function Probe({runId = 'run-1'}: {runId?: string | null}) {
  const stream = useAgentRunStream(runId);
  return createElement('div', {
    'data-status': stream.status,
    'data-unavailable': stream.unavailable ?? '',
    'data-run-status': stream.detail?.status ?? '',
  });
}

describe('agent run event stream', () => {
  let host: HTMLDivElement;
  let root: Root;

  beforeEach(() => {
    vi.useFakeTimers();
    vi.clearAllMocks();
    FakeEventSource.instances = [];
    vi.stubGlobal('EventSource', FakeEventSource);
    api.getRun.mockResolvedValue({runId: 'run-1', status: 'WORKING', tasks: []});
    api.getLiveCost.mockResolvedValue({costUsd: 0.01});
    api.getSseToken.mockResolvedValue({token: 'scoped-token'});
    host = document.createElement('div');
    document.body.appendChild(host);
    root = createRoot(host);
  });

  afterEach(() => {
    act(() => root.unmount());
    host.remove();
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

  async function mount() {
    await act(async () => {
      root.render(createElement(Probe));
      await Promise.resolve();
      await Promise.resolve();
    });
  }

  it('parses typed JSON events and rejects malformed frames', () => {
    expect(parseAgentRunEvent('{"type":"RUN_STATUS","payload":"WORKING"}')).toEqual({
      type: 'RUN_STATUS',
      payload: 'WORKING',
    });
    expect(parseAgentRunEvent('not-json')).toBeNull();
  });

  it('recognises terminal status reasons carried in RUN_STATUS payloads', () => {
    expect(
      terminalStatusFromEvent({type: 'RUN_STATUS', payload: 'PARTIAL|FINAL_BUILD_FAILED'}),
    ).toBe('PARTIAL|FINAL_BUILD_FAILED');
    expect(terminalStatusFromEvent({type: 'RUN_STATUS', payload: 'WORKING'})).toBeNull();
  });

  it('recognises the COMPLETE event so a closed SSE does not reconnect forever', () => {
    expect(terminalStatusFromEvent({type: 'COMPLETE', payload: 'COMPLETE'})).toBe('COMPLETE');
  });

  it('reads terminal status from a typed RUN_DETAIL snapshot', () => {
    expect(terminalStatusFromEvent({type: 'RUN_DETAIL', data: {status: 'CANCELLED'}})).toBe(
      'CANCELLED',
    );
  });

  it('classifies stable unavailable outcomes instead of retrying them', () => {
    expect(unavailableReason({status: 404})).toBe('RUN_NOT_FOUND');
    expect(unavailableReason({status: 401})).toBe('AUTH_REQUIRED');
    expect(unavailableReason({status: 403})).toBe('ACCESS_DENIED');
    expect(unavailableReason({status: 503})).toBeNull();
    expect(unavailableReason(new Error('network down'))).toBeNull();
    expect(agentRunUnavailableMessage('RUN_NOT_FOUND')).toBe('Run no longer available');
    expect(agentRunUnavailableMessage('AUTH_REQUIRED')).toBe('Sign in again to view this run');
    expect(agentRunUnavailableMessage('ACCESS_DENIED')).toBe('You do not have access to this run');
  });

  it('never opens EventSource without a scoped token and retries the token request', async () => {
    api.getSseToken
      .mockRejectedValueOnce(new Error('token service unavailable'))
      .mockResolvedValueOnce({token: 'recovered-token'});

    await mount();

    expect(FakeEventSource.instances).toHaveLength(0);
    expect(host.firstElementChild?.getAttribute('data-status')).toBe('RECONNECTING');

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000);
    });

    expect(FakeEventSource.instances).toHaveLength(1);
    expect(FakeEventSource.instances[0].url).toContain('token=recovered-token');
    expect(api.getSseToken).toHaveBeenCalledTimes(2);
  });

  it('settles a forbidden run as ACCESS_DENIED without token or stream retries', async () => {
    api.getRun.mockRejectedValue(Object.assign(new Error('forbidden'), {status: 403}));

    await mount();

    expect(host.firstElementChild?.getAttribute('data-status')).toBe('CLOSED');
    expect(host.firstElementChild?.getAttribute('data-unavailable')).toBe('ACCESS_DENIED');
    expect(api.getSseToken).not.toHaveBeenCalled();
    expect(FakeEventSource.instances).toHaveLength(0);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(60_000);
    });
    expect(api.getRun).toHaveBeenCalledTimes(1);
  });

  it('preserves the reconnect backoff until a connection is stable', async () => {
    await mount();
    const first = FakeEventSource.instances[0];
    act(() => first.open());
    act(() => first.fail());

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000);
    });
    const second = FakeEventSource.instances[1];
    act(() => second.open());
    act(() => second.fail());

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1999);
    });
    expect(FakeEventSource.instances).toHaveLength(2);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1);
    });
    const third = FakeEventSource.instances[2];
    act(() => third.open());

    await act(async () => {
      await vi.advanceTimersByTimeAsync(15_000);
    });
    act(() => third.fail());
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000);
    });
    expect(FakeEventSource.instances).toHaveLength(4);
  });

  it('stops after the bounded retry budget when token acquisition never recovers', async () => {
    api.getSseToken.mockRejectedValue(new Error('token service unavailable'));
    await mount();

    const delays = [1000, 2000, 4000, 8000, 16000, 30000, 30000, 30000, 30000, 30000, 30000, 30000];
    for (const delay of delays) {
      await act(async () => {
        await vi.advanceTimersByTimeAsync(delay);
      });
    }

    expect(api.getSseToken).toHaveBeenCalledTimes(13);
    expect(FakeEventSource.instances).toHaveLength(0);
    expect(host.firstElementChild?.getAttribute('data-status')).toBe('CLOSED');

    await act(async () => {
      await vi.advanceTimersByTimeAsync(60_000);
    });
    expect(api.getSseToken).toHaveBeenCalledTimes(13);
  });

  it('carries lastEventId into reconnect and refreshes the durable snapshot', async () => {
    await mount();
    const first = FakeEventSource.instances[0];
    act(() => first.open());
    act(() => first.message('{"type":"RUN_STATUS","payload":"WORKING"}', 'event-42'));
    act(() => first.fail());

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000);
    });
    const second = FakeEventSource.instances[1];
    expect(second.url).toContain('lastEventId=event-42');
    act(() => second.open());
    await act(async () => {
      await Promise.resolve();
    });
    expect(api.getRun).toHaveBeenCalledTimes(2);
  });

  it('closes on a terminal event and does not reconnect', async () => {
    await mount();
    const source = FakeEventSource.instances[0];
    act(() => source.open());
    act(() => source.message('{"type":"COMPLETE","payload":"COMPLETE"}', 'terminal-1'));

    expect(source.closed).toBe(true);
    expect(host.firstElementChild?.getAttribute('data-status')).toBe('CLOSED');
    await act(async () => {
      await vi.advanceTimersByTimeAsync(60_000);
    });
    expect(FakeEventSource.instances).toHaveLength(1);
  });
});
