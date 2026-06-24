import {type UIEvent, useCallback, useEffect, useMemo, useReducer, useRef, useState} from 'react';
import {useNavigate, useParams} from 'react-router-dom';
import * as api from '@/lib/api';
import {
  actionFromTurn,
  agentReducer,
  deriveBarStatus,
  initialAgentState,
  isTerminalRunStatus,
  toMessages,
  type PendingAction,
} from '@/ide/agentState';
import {AgentChatsDrawer, AgentConversation, AgentTopBar} from '@/ide/agentPanels';
import {selectScopedAgentSession, webAgentSessionRequest} from '@/ide/agentSession';

export default function IdeAgent() {
  const navigate = useNavigate();
  const {projectId, runId} = useParams();
  const [state, dispatch] = useReducer(agentReducer, initialAgentState);
  const abortRef = useRef<AbortController | null>(null);
  const endRef = useRef<HTMLDivElement>(null);
  const [uploading, setUploading] = useState(false);
  const [chatsOpen, setChatsOpen] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [runDetail, setRunDetail] = useState<api.RunDetail | null>(null);
  const [runCost, setRunCost] = useState<number | null>(null);
  const stickToBottom = useRef(true);

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
    if (stickToBottom.current) endRef.current?.scrollIntoView({block: 'end'});
  }, [state.turns, state.pendingUser, state.streamText, state.streamEvents, runDetail]);

  // Poll the agent-submitted run so the pinned run card stays live until terminal.
  useEffect(() => {
    const id = state.activeRun;
    if (!id) {
      setRunDetail(null);
      setRunCost(null);
      return;
    }
    let alive = true;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const poll = async () => {
      try {
        const [detail, cost] = await Promise.all([
          api.getRun(id),
          api.getLiveCost(id).catch(() => null),
        ]);
        if (!alive) return;
        setRunDetail(detail);
        if (cost && typeof cost.costUsd === 'number') setRunCost(cost.costUsd);
        if (!isTerminalRunStatus(detail.status)) timer = setTimeout(poll, 4000);
      } catch {
        if (alive) timer = setTimeout(poll, 6000);
      }
    };
    void poll();
    return () => {
      alive = false;
      if (timer) clearTimeout(timer);
    };
  }, [state.activeRun]);

  // Escape closes the chats drawer / settings popover.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setChatsOpen(false);
        setSettingsOpen(false);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

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
          message,
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
      dispatch({type: 'run:submitted', runId: gate.runId});
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

  async function stopRun() {
    const id = state.activeRun;
    if (!id) return;
    try {
      await api.cancelRun(id);
      const detail = await api.getRun(id).catch(() => null);
      if (detail) setRunDetail(detail);
      await refreshAfterMutation();
    } catch (e) {
      dispatch({type: 'error', error: e instanceof Error ? e.message : 'Could not stop the run.'});
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

  const runActive =
    !!runDetail &&
    !isTerminalRunStatus(runDetail.status) &&
    runDetail.status !== 'AWAITING_APPROVAL';
  // The run card owns the active run across every phase; drop it from the inline
  // gate list so a just-submitted run isn't shown twice while it waits for approval.
  const inlineGates = state.gates.filter((g) => g.runId !== state.activeRun);
  const barStatus = deriveBarStatus({
    status: state.status,
    hasPendingAction: !!visibleAction || runDetail?.status === 'AWAITING_APPROVAL',
    pendingGateCount: inlineGates.length,
    runActive,
  });
  const scopeLabel = runId ? `Run ${runId.slice(0, 8)}` : projectId ? 'Project' : null;
  const runCard = runDetail;
  const onChatScroll = (e: UIEvent<HTMLDivElement>) => {
    const el = e.currentTarget;
    stickToBottom.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80;
  };

  return (
    <div className='ide-page agent-chat-page'>
      <AgentTopBar
        selected={selected}
        barStatus={barStatus}
        scopeLabel={scopeLabel}
        busy={isBusy}
        settingsOpen={settingsOpen}
        managedAi={state.managedAi}
        routingState={state.routingState}
        capabilities={state.capabilities}
        onNewChat={() => {
          setChatsOpen(false);
          void createSession();
        }}
        onToggleChats={() => setChatsOpen((o) => !o)}
        onToggleSettings={() => setSettingsOpen((o) => !o)}
      />

      {state.error && (
        <div className='ide-card agent-error' role='alert'>
          <span className='agent-error-body'>
            <b>Something went wrong</b>
            <span>{state.error}</span>
          </span>
          <button
            className='agent-error-dismiss'
            onClick={() => dispatch({type: 'error:clear'})}
            aria-label='Dismiss error'
          >
            ×
          </button>
        </div>
      )}

      <AgentChatsDrawer
        open={chatsOpen}
        sessions={state.sessions}
        selectedId={state.selectedId}
        status={state.status}
        onSelect={(id) => {
          dispatch({type: 'session:select', sessionId: id});
          setChatsOpen(false);
        }}
        onNewChat={() => {
          setChatsOpen(false);
          void createSession();
        }}
        onClose={() => setChatsOpen(false)}
      />

      <AgentConversation
        messages={messages}
        streamEvents={state.streamEvents}
        streamText={state.streamText}
        isBusy={isBusy}
        visibleAction={visibleAction}
        gates={inlineGates}
        runCard={runCard}
        runCost={runCost}
        input={state.input}
        attachments={state.attachments}
        uploading={uploading}
        endRef={endRef}
        dispatch={dispatch}
        onScroll={onChatScroll}
        onSend={() => void send()}
        onStop={stop}
        onAddFiles={(files) => void handleFiles(files)}
        onRemoveAttachment={(id) => dispatch({type: 'attachment:remove', attachmentId: id})}
        onConfirmAction={(pending) => void confirmAction(pending)}
        onApproveGate={(gate) => void approveGate(gate)}
        onRejectGate={(gate) => void rejectGate(gate)}
        onOpenGate={(id) => navigate(`/plan/${id}`)}
        onOpenRun={(path) => navigate(path)}
        onStopRun={() => void stopRun()}
      />
    </div>
  );
}
