import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {
  confirmAgentRunProposal,
  createAgentSession,
  parseAgentSseFrame,
  streamAgentTurn,
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
    });

    expect(response.runId).toBe('run-1');
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/orchestrator/turn/confirm',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({Authorization: 'Bearer test-token'}),
        body: JSON.stringify({sessionId: 's1', turnId: 't1', confirmed: true}),
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
