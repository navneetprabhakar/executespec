import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {
  confirmAgentRunProposal,
  closeAgentSession,
  createAgentSession,
  listAgentTurnPage,
  listAgentTurns,
  parseAgentSseFrame,
  replayAgentEvents,
  streamAgentTurn,
  updateAgentSessionRoute,
} from './agent';

describe('agent API SSE helpers', () => {
  beforeEach(() => {
    const values = new Map<string, string>();
    vi.stubGlobal('localStorage', {
      getItem: (key: string) => values.get(key) ?? null,
      setItem: (key: string, value: string) => values.set(key, value),
      removeItem: (key: string) => values.delete(key),
      clear: () => values.clear(),
    });
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('parses named JSON SSE frames', () => {
    expect(parseAgentSseFrame('event: token\ndata: {"delta":"hello"}')).toEqual({
      event: 'token',
      data: {delta: 'hello'},
    });
  });

  it('uses the server close contract and cursor-based transcript pagination', async () => {
    localStorage.setItem('access_token', 'test-token');
    const fetchMock = vi.fn(async () => new Response(JSON.stringify([]), {status: 200}));
    vi.stubGlobal('fetch', fetchMock);

    await listAgentTurns('session/one', {limit: 100, before: 'turn/oldest'});
    await closeAgentSession('session/one');

    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      '/api/agent/sessions/session%2Fone/turns?limit=100&before=turn%2Foldest',
      expect.any(Object),
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/agent/sessions/session%2Fone/close',
      expect.objectContaining({method: 'POST'}),
    );
  });

  it('loads authoritative transcript completeness from the paged contract', async () => {
    localStorage.setItem('access_token', 'test-token');
    const fetchMock = vi.fn(
      async () =>
        new Response(JSON.stringify({turns: [], totalTurns: 100, hasMore: false}), {status: 200}),
    );
    vi.stubGlobal('fetch', fetchMock);

    await listAgentTurnPage('session/one', {limit: 100, before: 'turn/oldest'});

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/agent/sessions/session%2Fone/turns/page?limit=100&before=turn%2Foldest',
      expect.any(Object),
    );
  });

  it('persists conversation routing separately from generated-run confirmation', async () => {
    localStorage.setItem('access_token', 'test-token');
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({id: 's1'}), {status: 200}));
    vi.stubGlobal('fetch', fetchMock);

    await updateAgentSessionRoute('session/one', {provider: 'OPENAI', model: 'gpt-5.4'});

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/agent/sessions/session%2Fone/route',
      expect.objectContaining({
        method: 'PUT',
        body: JSON.stringify({provider: 'OPENAI', model: 'gpt-5.4'}),
      }),
    );
  });

  it('joins multi-line data frames as plain text when not JSON', () => {
    expect(parseAgentSseFrame('event: message\ndata: first\ndata: second')).toEqual({
      event: 'message',
      data: 'first\nsecond',
    });
  });

  it('dispatches streamed turn events from a POST SSE response', async () => {
    localStorage.setItem('access_token', 'test-token');
    const body = [
      'event: ack\ndata: {"session_id":"s1","turn_id":"t1","sequence":1}',
      'event: route_decision\ndata: {"session_id":"s1","turn_id":"t1","route":"CODE_RUN_REQUIRED","confidence":0.91,"reason_code":"explicit_code_mutation_request","run_api_allowed":true,"requires_confirmation":true}',
      'event: proposal\ndata: {"session_id":"s1","turn_id":"t1","route":"CODE_RUN_REQUIRED","action":{"type":"create_cloud_run","arguments":{"spec":"add tests"},"requiresConfirmation":true,"billingImpact":"run_credit_on_plan_approval","confirmationPrompt":"Create a cloud run?"}}',
      'event: token\ndata: {"delta":"hi"}',
      'event: done\ndata: {"session_id":"s1","turn_id":"t1","turn_count":1,"cost_usd":0.01}',
      '',
    ].join('\n\n');
    const encoder = new TextEncoder();
    const fetchMock = vi.fn(async () => {
      return new Response(
        new ReadableStream({
          start(controller) {
            controller.enqueue(encoder.encode(body));
            controller.close();
          },
        }),
        {status: 200},
      );
    });
    vi.stubGlobal('fetch', fetchMock);

    const events: string[] = [];
    const tokens: string[] = [];
    let turnId = '';
    let cost = 0;
    let route = '';
    let action = '';

    await streamAgentTurn(
      's1',
      {message: 'hello'},
      {
        onEvent: (event) => events.push(event.event),
        onAck: (ack) => {
          turnId = ack.turn_id;
        },
        onRouteDecision: (decision) => {
          route = decision.route;
        },
        onProposal: (proposal) => {
          action = proposal.action.type;
        },
        onToken: (token) => tokens.push(token.delta),
        onDone: (done) => {
          cost = done.cost_usd ?? 0;
        },
      },
    );

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/agent/sessions/s1/turns/stream',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({Authorization: 'Bearer test-token'}),
      }),
    );
    expect(events).toEqual(['ack', 'route_decision', 'proposal', 'token', 'done']);
    expect(turnId).toBe('t1');
    expect(route).toBe('CODE_RUN_REQUIRED');
    expect(action).toBe('create_cloud_run');
    expect(tokens).toEqual(['hi']);
    expect(cost).toBe(0.01);
  });

  it('requests cursor-based event backfill', async () => {
    localStorage.setItem('access_token', 'test-token');
    const fetchMock = vi.fn(
      async () =>
        new Response(JSON.stringify({events: [], lastSequence: 7, snapshot: false}), {
          status: 200,
          headers: {'content-type': 'application/json'},
        }),
    );
    vi.stubGlobal('fetch', fetchMock);

    const response = await replayAgentEvents('s1', 6);

    expect(response.lastSequence).toBe(7);
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/agent/sessions/s1/events/replay?after=6',
      expect.objectContaining({
        headers: expect.objectContaining({Authorization: 'Bearer test-token'}),
      }),
    );
  });

  it('does not treat a transport EOF before done as a successful turn', async () => {
    localStorage.setItem('access_token', 'test-token');
    const encoder = new TextEncoder();
    vi.stubGlobal(
      'fetch',
      vi.fn(
        async () =>
          new Response(
            new ReadableStream({
              start(controller) {
                controller.enqueue(
                  encoder.encode('event: ack\ndata: {"session_id":"s1","turn_id":"t1"}\n\n'),
                );
                controller.close();
              },
            }),
            {status: 200},
          ),
      ),
    );

    await expect(streamAgentTurn('s1', {message: 'hello'})).rejects.toMatchObject({
      status: 502,
      message: 'Agent stream ended before the turn completed. Refresh the conversation and retry.',
    });
  });

  it('accepts a transport reset after the authoritative done frame', async () => {
    localStorage.setItem('access_token', 'test-token');
    const encoder = new TextEncoder();
    let reads = 0;
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => {
        const response = new Response(null, {status: 200});
        Object.defineProperty(response, 'body', {
          value: {
            getReader: () => ({
              read: async () => {
                if (reads++ === 0) {
                  return {
                    value: encoder.encode(
                      'event: done\ndata: {"session_id":"s1","turn_id":"t1","turn_count":1}\n\n',
                    ),
                    done: false,
                  };
                }
                throw new Error('proxy reset');
              },
            }),
          },
        });
        return response;
      }),
    );

    await expect(streamAgentTurn('s1', {message: 'hello'})).resolves.toBeUndefined();
  });

  it('rejects a transport reset before a terminal frame', async () => {
    localStorage.setItem('access_token', 'test-token');
    const encoder = new TextEncoder();
    let reads = 0;
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => {
        const response = new Response(null, {status: 200});
        Object.defineProperty(response, 'body', {
          value: {
            getReader: () => ({
              read: async () => {
                if (reads++ === 0) {
                  return {
                    value: encoder.encode(
                      'event: ack\ndata: {"session_id":"s1","turn_id":"t1"}\n\n',
                    ),
                    done: false,
                  };
                }
                throw new Error('proxy reset');
              },
            }),
          },
        });
        return response;
      }),
    );

    await expect(streamAgentTurn('s1', {message: 'hello'})).rejects.toMatchObject({
      status: 0,
      message: expect.stringContaining('disconnected before the turn completed'),
    });
  });

  it('RR-1344 — throws on a terminal error frame instead of resolving as success', async () => {
    localStorage.setItem('access_token', 'test-token');
    const encoder = new TextEncoder();
    const onErrorEvent = vi.fn();
    vi.stubGlobal(
      'fetch',
      vi.fn(
        async () =>
          new Response(
            new ReadableStream({
              start(controller) {
                controller.enqueue(
                  encoder.encode('event: ack\ndata: {"session_id":"s1","turn_id":"t1"}\n\n'),
                );
                controller.enqueue(
                  encoder.encode('event: error\ndata: {"message":"provider exploded"}\n\n'),
                );
                controller.close();
              },
            }),
            {status: 200},
          ),
      ),
    );

    await expect(streamAgentTurn('s1', {message: 'hello'}, {onErrorEvent})).rejects.toMatchObject({
      status: 502,
      message: 'provider exploded',
    });
    // The error callback still fires so live UI can react, but the promise
    // rejects so the caller never dispatches a "done"/idle after the failure.
    expect(onErrorEvent).toHaveBeenCalledWith({message: 'provider exploded'});
  });

  it('preserves a structured projected-budget error and classifies it as action required', async () => {
    localStorage.setItem('access_token', 'test-token');
    const encoder = new TextEncoder();
    const onErrorEvent = vi.fn();
    const budgetError = {
      session_id: 's1',
      turn_id: 't1',
      code: 'PROJECTED_CALL_BUDGET_EXCEEDED',
      category: 'ACTION_REQUIRED',
      message: 'The next model call would exceed this session budget.',
      retryable: true,
      next_action: 'Increase the session cap, then retry.',
      actual_cost_usd: 0.41,
      cap_usd: 0.5,
      projected_call_usd: 0.16,
    };
    vi.stubGlobal(
      'fetch',
      vi.fn(
        async () =>
          new Response(
            new ReadableStream({
              start(controller) {
                controller.enqueue(
                  encoder.encode(`event: error\ndata: ${JSON.stringify(budgetError)}\n\n`),
                );
                controller.close();
              },
            }),
            {status: 200},
          ),
      ),
    );

    await expect(
      streamAgentTurn('s1', {message: 'continue'}, {onErrorEvent}),
    ).rejects.toMatchObject({
      status: 422,
      message:
        'The next model call would exceed this session budget. Increase the session cap, then retry.',
    });
    expect(onErrorEvent).toHaveBeenCalledWith(budgetError);
  });

  it('confirms a stored cloud-run proposal through the orchestrator endpoint', async () => {
    localStorage.setItem('access_token', 'test-token');
    const fetchMock = vi.fn(async () => {
      return new Response(
        JSON.stringify({
          sessionId: 's1',
          turnId: 't1',
          runId: 'run-1',
          route: 'CODE_RUN_REQUIRED',
          calledRunApi: true,
          traceId: 'orch_trace_1',
          status: 'SUBMITTED',
        }),
        {status: 200, headers: {'content-type': 'application/json'}},
      );
    });
    vi.stubGlobal('fetch', fetchMock);

    const response = await confirmAgentRunProposal({
      sessionId: 's1',
      turnId: 't1',
      confirmed: true,
      selectedRepoIds: ['repo-1'],
    });

    expect(response.runId).toBe('run-1');
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/orchestrator/turn/confirm',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({Authorization: 'Bearer test-token'}),
        body: JSON.stringify({
          sessionId: 's1',
          turnId: 't1',
          confirmed: true,
          selectedRepoIds: ['repo-1'],
        }),
      }),
    );
  });

  it('sends run scope when creating an agent session', async () => {
    localStorage.setItem('access_token', 'test-token');
    const fetchMock = vi.fn(async () => {
      return new Response(
        JSON.stringify({
          id: 's1',
          projectId: 'p1',
          runId: 'run-1',
          repoRemote: null,
          surface: 'WEB',
          title: 'Run agent',
          status: 'ACTIVE',
          defaultProvider: null,
          defaultModel: null,
          defaultExecutionMode: 'PRODUCTION',
          approvalPolicy: 'CONFIRM_MUTATIONS',
          createdAt: '2026-06-20T00:00:00Z',
          updatedAt: '2026-06-20T00:00:00Z',
        }),
        {status: 200, headers: {'content-type': 'application/json'}},
      );
    });
    vi.stubGlobal('fetch', fetchMock);

    const response = await createAgentSession({
      surface: 'WEB',
      runId: 'run-1',
      title: 'Run agent',
      defaultExecutionMode: 'PRODUCTION',
      approvalPolicy: 'CONFIRM_MUTATIONS',
    });

    expect(response.runId).toBe('run-1');
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/agent/sessions',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({Authorization: 'Bearer test-token'}),
        body: JSON.stringify({
          surface: 'WEB',
          runId: 'run-1',
          title: 'Run agent',
          defaultExecutionMode: 'PRODUCTION',
          approvalPolicy: 'CONFIRM_MUTATIONS',
        }),
      }),
    );
  });
});
