import {useCallback, useEffect, useMemo, useReducer, useRef, useState} from 'react';
import {useNavigate, useParams} from 'react-router-dom';
import * as api from '@/lib/api';
import {
  actionFromTurn,
  agentReducer,
  initialAgentState,
  toMessages,
  type PendingAction,
} from '@/ide/agentState';
import {
  AgentHeader,
  AgentLeftRail,
  AgentMainPanel,
  AgentRightRail,
  AgentTopStrip,
} from '@/ide/agentPanels';
import {selectScopedAgentSession, webAgentSessionRequest} from '@/ide/agentSession';

export default function IdeAgent() {
  const navigate = useNavigate();
  const {projectId, runId} = useParams();
  const [state, dispatch] = useReducer(agentReducer, initialAgentState);
  const abortRef = useRef<AbortController | null>(null);
  const endRef = useRef<HTMLDivElement>(null);
  const [uploading, setUploading] = useState(false);

  const selected = useMemo(
    () => state.sessions.find((session) => session.id === state.selectedId) ?? null,
    [state.sessions, state.selectedId],
  );

  const historicalActions = useMemo(
    () => state.turns.map(actionFromTurn).filter(Boolean) as PendingAction[],
    [state.turns],
  );
  const visibleAction = state.pendingAction ?? historicalActions[0] ?? null;

  const messages = [
    ...toMessages(state.turns),
    ...(state.pendingUser
      ? [{id: 'pending:user', role: 'user' as const, content: state.pendingUser, status: 'SENDING'}]
      : []),
    ...(state.streamText
      ? [{id: 'pending:assistant', role: 'assistant' as const, content: state.streamText}]
      : []),
  ];

  const loadTurns = useCallback(async (sessionId: string) => {
    const rows = await api.listAgentTurns(sessionId, {limit: 50});
    dispatch({type: 'turns:set', turns: rows});
  }, []);

  const loadSessions = useCallback(async () => {
    const items = await api.listAgentSessions(25);
    dispatch({type: 'sessions:set', sessions: items});
    return items;
  }, []);

  const loadManagedAi = useCallback(async () => {
    try {
      const balance = await api.getManagedAiBalance();
      dispatch({type: 'managed-ai:set', balance});
      return balance;
    } catch {
      dispatch({type: 'managed-ai:set', balance: null});
      return null;
    }
  }, []);

  const load = useCallback(async () => {
    dispatch({type: 'load:start'});
    try {
      const [sessionRows, gateRows, capabilityRows, managedAi] = await Promise.all([
        api.listAgentSessions(25),
        api.listAgentGates(12),
        api.listAgentCapabilities('WEB'),
        api.getManagedAiBalance().catch(() => null),
      ]);
      const scoped = selectScopedAgentSession(sessionRows, {projectId, runId});
      const selectedId = state.selectedId ?? scoped?.id ?? sessionRows[0]?.id ?? null;
      dispatch({
        type: 'load:success',
        sessions: sessionRows,
        gates: gateRows,
        capabilities: capabilityRows,
        selectedId,
      });
      dispatch({type: 'managed-ai:set', balance: managedAi});
      if (selectedId) await loadTurns(selectedId);
    } catch (e) {
      dispatch({
        type: 'load:error',
        error: e instanceof Error ? e.message : 'Failed to load agent state.',
      });
    }
  }, [loadTurns, projectId, runId, state.selectedId]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (!state.selectedId) {
      dispatch({type: 'turns:set', turns: []});
      return;
    }
    void loadTurns(state.selectedId).catch((e) =>
      dispatch({
        type: 'error',
        error: e instanceof Error ? e.message : 'Failed to load transcript.',
      }),
    );
  }, [loadTurns, state.selectedId]);

  useEffect(() => {
    endRef.current?.scrollIntoView({block: 'end'});
  }, [state.turns, state.pendingUser, state.streamText, state.streamEvents]);

  async function createSession() {
    const session = await api.createAgentSession(webAgentSessionRequest({projectId, runId}));
    const next = [session, ...state.sessions.filter((s) => s.id !== session.id)];
    dispatch({type: 'sessions:set', sessions: next});
    dispatch({type: 'session:select', sessionId: session.id});
    return session;
  }

  async function ensureSession() {
    if (selected) return selected;
    return createSession();
  }

  async function handleFiles(files: FileList | null) {
    // Snapshot synchronously: the picker's onChange resets input.value right after this call,
    // which empties the live FileList before the awaits below would otherwise read it.
    const list = files ? Array.from(files) : [];
    if (list.length === 0) return;
    setUploading(true);
    try {
      const session = await ensureSession();
      for (const file of list) {
        const meta = await api.uploadAgentAttachment(session.id, file);
        dispatch({
          type: 'attachment:add',
          attachment: {
            attachmentId: meta.attachmentId,
            filename: meta.filename,
            mimeType: meta.mimeType,
            extractedChars: meta.extractedChars,
          },
        });
      }
    } catch (e) {
      dispatch({type: 'error', error: e instanceof Error ? e.message : 'Upload failed.'});
    } finally {
      setUploading(false);
    }
  }

  async function refreshAfterMutation(sessionId?: string) {
    const [sessions, gates] = await Promise.all([
      loadSessions(),
      api.listAgentGates(12),
      loadManagedAi(),
    ]);
    dispatch({type: 'gates:set', gates});
    const active = sessionId ?? state.selectedId ?? sessions[0]?.id;
    if (active) await loadTurns(active);
  }

  async function send(seed?: string) {
    const message = (seed ?? state.input).trim();
    if (!message || state.status === 'streaming' || state.status === 'toolRunning') return;
    const attachmentIds = state.attachments.map((a) => a.attachmentId);
    dispatch({type: 'stream:start', message});
    const abort = new AbortController();
    abortRef.current = abort;
    try {
      const session = await ensureSession();
      await api.streamAgentTurn(
        session.id,
        {
          message: runId ? `[Run ${runId}] ${message}` : message,
          attachmentIds,
          clientCapabilities: {
            localTools: false,
            interactive: true,
            canRenderDiff: true,
            canRenderTui: false,
          },
        },
        {
          onAck: (ack) => dispatch({type: 'stream:ack', turnId: ack.turn_id}),
          onRouteDecision: (route) => dispatch({type: 'stream:route', route}),
          onProposal: (proposal) => dispatch({type: 'stream:proposal', proposal}),
          onToken: (token) => dispatch({type: 'stream:token', token: token.delta}),
          onToolCall: (tool) =>
            dispatch({
              type: 'stream:event',
              item: {
                id: `tool:${tool.id || tool.tool}:${Date.now()}`,
                type: 'tool',
                title: tool.tool,
                detail: tool.args,
              },
            }),
          onToolResult: (result) =>
            dispatch({
              type: 'stream:event',
              item: {
                id: `result:${result.id}:${Date.now()}`,
                type: 'result',
                title: 'Tool result',
                detail: result.result,
              },
            }),
          onDone: (done) =>
            dispatch({
              type: 'stream:event',
              item: {
                id: `usage:${done.turn_id}`,
                type: 'usage',
                title: 'Usage',
                detail: `$${(done.cost_usd ?? 0).toFixed(4)} · ${done.input_tokens ?? 0}/${done.output_tokens ?? 0} tokens`,
              },
            }),
          onErrorEvent: (error) =>
            dispatch({type: 'error', error: error.message || 'Agent stream failed.'}),
        },
        abort.signal,
      );
      dispatch({type: 'stream:done'});
      await refreshAfterMutation(session.id);
    } catch (e) {
      dispatch({type: 'error', error: e instanceof Error ? e.message : 'Agent turn failed.'});
    } finally {
      abortRef.current = null;
    }
  }

  async function confirmAction(pending: PendingAction) {
    if (pending.action.type !== 'create_cloud_run') return;
    try {
      const response = await api.confirmAgentRunProposal({
        sessionId: pending.sessionId,
        turnId: pending.turnId,
        confirmed: true,
      });
      dispatch({type: 'run:submitted', runId: response.runId});
      dispatch({
        type: 'stream:event',
        item: {
          id: `run:${response.runId}`,
          type: 'run',
          title: 'Run submitted',
          detail: response.runId,
        },
      });
      await refreshAfterMutation(pending.sessionId);
    } catch (e) {
      dispatch({type: 'error', error: e instanceof Error ? e.message : 'Run confirmation failed.'});
    }
  }

  async function approveGate(gate: api.AgentGateDecisionCard) {
    try {
      dispatch({
        type: 'stream:event',
        item: {
          id: `gate:${gate.runId}:approve`,
          type: 'system',
          title: 'Approving plan gate',
          detail: gate.runId,
        },
      });
      await api.approveRun(gate.runId, true, gate.creditsRequired ?? undefined);
      await refreshAfterMutation();
    } catch (e) {
      dispatch({type: 'error', error: e instanceof Error ? e.message : 'Plan approval failed.'});
    }
  }

  async function rejectGate(gate: api.AgentGateDecisionCard) {
    try {
      await api.approveRun(gate.runId, false);
      await refreshAfterMutation();
    } catch (e) {
      dispatch({type: 'error', error: e instanceof Error ? e.message : 'Plan rejection failed.'});
    }
  }

  function stop() {
    abortRef.current?.abort();
    dispatch({type: 'stream:stop'});
  }

  const isBusy =
    state.status === 'loadingSession' ||
    state.status === 'streaming' ||
    state.status === 'toolRunning' ||
    state.status === 'runStreaming';

  return (
    <div className='ide-page agent-page'>
      <AgentHeader onCreate={() => void createSession()} busy={isBusy} />
      <AgentTopStrip
        selected={selected}
        projectId={projectId}
        runId={runId}
        activeRun={state.activeRun}
        managedAi={state.managedAi}
        routingState={state.routingState}
      />

      {state.error && (
        <div className='ide-card agent-error'>
          <b>Agent error</b>
          <span>{state.error}</span>
        </div>
      )}

      <div className={`agent-shell three${state.sessionsCollapsed ? ' no-left' : ''}`}>
        {!state.sessionsCollapsed && (
          <AgentLeftRail
            sessions={state.sessions}
            selectedId={state.selectedId}
            status={state.status}
            onToggle={() => dispatch({type: 'sessions:toggle'})}
            dispatch={dispatch}
          />
        )}
        <AgentMainPanel
          selected={selected}
          status={state.status}
          messages={messages}
          visibleAction={visibleAction}
          isBusy={isBusy}
          streamText={state.streamText}
          streamEvents={state.streamEvents}
          input={state.input}
          projectId={projectId}
          runId={runId}
          endRef={endRef}
          attachments={state.attachments}
          uploading={uploading}
          sessionsCollapsed={state.sessionsCollapsed}
          onAddFiles={(files) => void handleFiles(files)}
          onRemoveAttachment={(id) => dispatch({type: 'attachment:remove', attachmentId: id})}
          onToggleSessions={() => dispatch({type: 'sessions:toggle'})}
          dispatch={dispatch}
          onConfirmAction={(pending) => void confirmAction(pending)}
          onSend={() => void send()}
          onStop={stop}
        />
        <AgentRightRail
          managedAi={state.managedAi}
          routingState={state.routingState}
          gates={state.gates}
          capabilities={state.capabilities}
          busy={isBusy}
          onApproveGate={(gate) => void approveGate(gate)}
          onRejectGate={(gate) => void rejectGate(gate)}
          onOpenGate={(id) => navigate(`/plan/${id}`)}
        />
      </div>
    </div>
  );
}
