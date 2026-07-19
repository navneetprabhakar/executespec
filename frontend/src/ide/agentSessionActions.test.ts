import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

const createAgentSession = vi.fn();
vi.mock('@/lib/api', () => ({
  createAgentSession: (...args: unknown[]) => createAgentSession(...args),
}));

import {
  createCanonicalAgentSession,
  newAgentSessionRequest,
  requestNewAgentSession,
  resetPendingNewAgentSession,
  subscribeNewAgentSession,
  takePendingNewAgentSession,
} from '@/ide/agentSessionActions';

describe('agentSessionActions', () => {
  beforeEach(() => {
    createAgentSession.mockReset();
    createAgentSession.mockImplementation((body: {id: string}) =>
      Promise.resolve({id: body.id, title: 'Web agent'}),
    );
    resetPendingNewAgentSession();
  });

  afterEach(() => {
    resetPendingNewAgentSession();
  });

  it('mints a distinct client request id per request', () => {
    const a = newAgentSessionRequest();
    const b = newAgentSessionRequest();
    expect(a.requestId).toBeTruthy();
    expect(a.requestId).not.toBe(b.requestId);
  });

  it('sends the client request id as the session id so a retry is idempotent', async () => {
    const request = newAgentSessionRequest({projectId: 'p1'});
    await createCanonicalAgentSession(request);
    await createCanonicalAgentSession(request);

    expect(createAgentSession).toHaveBeenCalledTimes(2);
    const [first, second] = createAgentSession.mock.calls;
    expect(first[0].id).toBe(request.requestId);
    expect(second[0].id).toBe(request.requestId);
    expect(first[0].projectId).toBe('p1');
    expect(first[0].surface).toBe('WEB');
  });

  it('delivers a request to a mounted subscriber instead of queueing it', () => {
    const seen: string[] = [];
    const unsubscribe = subscribeNewAgentSession((r) => seen.push(r.requestId));
    const request = requestNewAgentSession({});
    expect(seen).toEqual([request.requestId]);
    expect(takePendingNewAgentSession()).toBeNull();
    unsubscribe();
  });

  it('queues a request raised while no surface is mounted, and hands it over once', () => {
    const request = requestNewAgentSession({runId: 'r1'});
    const pending = takePendingNewAgentSession();
    expect(pending?.requestId).toBe(request.requestId);
    expect(pending?.scope.runId).toBe('r1');
    // Consumed exactly once — a remount must not create a second session.
    expect(takePendingNewAgentSession()).toBeNull();
  });
});
