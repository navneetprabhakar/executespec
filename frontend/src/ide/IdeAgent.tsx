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
  type AgentUsageSummary,
  type PendingAction,
} from '@/ide/agentState';
import {AgentChatsDrawer, AgentConversation, AgentTopBar} from '@/ide/agentPanels';
import {
  agentSessionMatchesScope,
  selectScopedAgentSession,
  webAgentSessionRequest,
} from '@/ide/agentSession';
import {useAgentRunStream} from '@/ide/agentRunStream';

const MAX_CONTEXT_TURNS = 8;

function totalUsageTokens(usage: AgentUsageSummary): number {
  return usage.inputTokens + usage.outputTokens + usage.reasoningTokens;
}

function sessionUsageContext(usage: AgentUsageSummary): api.AgentConversationMessage | null {
  const totalTokens = totalUsageTokens(usage);
  if (totalTokens <= 0) return null;
  return {
    role: 'system',
    content:
      `Conversation usage visible in the web UI before this turn: ${totalTokens} total tokens ` +
      `(${usage.inputTokens} input, ${usage.outputTokens} output, ${usage.reasoningTokens} reasoning). ` +
      'Current billing policy: chat turns are included conversation usage and have not charged AI credits. ' +
      'Run credits are charged separately only when a plan-gated run is approved. ' +
      'If the user asks what this chat or session cost in AI credits, answer from these facts; do not say you cannot access session usage.',
  };
}

function conversationForStream(
  turns: api.AgentTurnResponse[],
  latestMessage: string,
): api.AgentConversationMessage[] {
  const usageContext = sessionUsageContext(usageFromTurns(turns));
  const conversation: api.AgentConversationMessage[] = [];
  if (usageContext) conversation.push(usageContext);
  for (const turn of turns.slice(-MAX_CONTEXT_TURNS)) {
    if (turn.userMessage?.trim()) {
      conversation.push({role: 'user', content: turn.userMessage.trim()});
    }
    if (turn.assistantSummary?.trim()) {
      conversation.push({role: 'assistant', content: turn.assistantSummary.trim()});
    }
  }
  conversation.push({role: 'user', content: latestMessage});
  return conversation;
}

function usageFromTurns(turns: api.AgentTurnResponse[]): AgentUsageSummary {
  return turns.reduce(
    (acc, turn) => ({
      inputTokens: acc.inputTokens + (turn.inputTokens || 0),
      outputTokens: acc.outputTokens + (turn.outputTokens || 0),
      reasoningTokens: acc.reasoningTokens + (turn.reasoningTokens || 0),
      costUsd: acc.costUsd + (turn.costUsd || 0),
    }),
    {inputTokens: 0, outputTokens: 0, reasoningTokens: 0, costUsd: 0},
  );
}

function persistedEventTimeline(events: api.AgentActionEvent[] | null | undefined) {
  const semanticTypes = new Set([
    'SUBAGENT',
    'HANDOFF',
    'PROVIDER_SWITCH',
    'MODEL_SWITCH',
    'CONTINUATION',
    'RUN_TASK_WAVE_PLAN',
    'RUN_GOAL_DECISION',
    'RUN_CONTINUATION_DECISION',
    'RUN_CONTINUATION_ACTION',
    'RUN_CONTINUATION_ACTION_SKIPPED',
    'RUN_VALIDATION_EVIDENCE',
  ]);
  const isSemanticType = (type: string) =>
    semanticTypes.has(type) ||
    ['SUBAGENT_', 'HANDOFF_', 'PROVIDER_', 'MODEL_', 'CONTINUATION_'].some((prefix) =>
      type.startsWith(prefix),
    );
  return (events ?? [])
    .filter((event) => isSemanticType(event.type.toUpperCase()))
    .map((event) => {
      let detail = event.payloadJson ?? undefined;
      if (detail) {
        try {
          const payload = JSON.parse(detail) as Record<string, unknown>;
          detail = String(payload.summary ?? payload.reason ?? payload.status ?? detail);
        } catch {
          // Preserve opaque historical payloads as bounded detail rather than dropping activity.
        }
      }
      return {
        id: `persisted:${event.id}`,
        type: 'system' as const,
        title: event.type.split('_').join(' '),
        detail,
      };
    });
}

export default function IdeAgent() {
  const navigate = useNavigate();
  const {projectId, runId} = useParams();
  const [state, dispatch] = useReducer(agentReducer, initialAgentState);
  const abortRef = useRef<AbortController | null>(null);
  const endRef = useRef<HTMLDivElement>(null);
  const [uploading, setUploading] = useState(false);
  const [chatsOpen, setChatsOpen] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [hasNewActivity, setHasNewActivity] = useState(false);
  const [availableModels, setAvailableModels] = useState<api.ProviderEntry[]>([]);
  const [selectedProvider, setSelectedProvider] = useState('');
  const [selectedModel, setSelectedModel] = useState('');
  const overlayFocusRef = useRef<HTMLElement | null>(null);
  const stickToBottom = useRef(true);
  const liveRun = useAgentRunStream(state.activeRun);
  const runDetail = liveRun.detail;
  const runCost = liveRun.costUsd;

  useEffect(() => {
    if (!chatsOpen && !settingsOpen) return;
    const dialog = document.querySelector<HTMLElement>('[role="dialog"]');
    const focusable = dialog?.querySelector<HTMLElement>(
      'button:not([disabled]), [href], input:not([disabled]), [tabindex]:not([tabindex="-1"])',
    );
    focusable?.focus();
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        if (settingsOpen) setSettingsOpen(false);
        else setChatsOpen(false);
        return;
      }
      if (event.key !== 'Tab' || !dialog) return;
      const controls = Array.from(
        dialog.querySelectorAll<HTMLElement>(
          'button:not([disabled]), [href], input:not([disabled]), [tabindex]:not([tabindex="-1"])',
        ),
      );
      if (controls.length === 0) {
        event.preventDefault();
        dialog.focus();
        return;
      }
      const first = controls[0];
      const last = controls[controls.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => {
      window.removeEventListener('keydown', onKeyDown);
      overlayFocusRef.current?.focus();
      overlayFocusRef.current = null;
    };
  }, [chatsOpen, settingsOpen]);

  const toggleChats = useCallback(() => {
    if (!chatsOpen) overlayFocusRef.current = document.activeElement as HTMLElement | null;
    if (!chatsOpen) setSettingsOpen(false);
    setChatsOpen((open) => !open);
  }, [chatsOpen]);

  const toggleSettings = useCallback(() => {
    if (!settingsOpen) overlayFocusRef.current = document.activeElement as HTMLElement | null;
    if (!settingsOpen) setChatsOpen(false);
    setSettingsOpen((open) => !open);
  }, [settingsOpen]);

  const scope = useMemo(() => ({projectId, runId}), [projectId, runId]);
  const selected = useMemo(() => {
    const candidate = state.sessions.find((session) => session.id === state.selectedId) ?? null;
    if (!candidate) return null;
    return agentSessionMatchesScope(candidate, scope) ? candidate : null;
  }, [scope, state.sessions, state.selectedId]);

  useEffect(() => {
    setSelectedProvider(selected?.defaultProvider ?? '');
    setSelectedModel(selected?.defaultModel ?? '');
  }, [selected?.id, selected?.defaultProvider, selected?.defaultModel]);

  const historicalActions = useMemo(
    () => state.turns.map(actionFromTurn).filter(Boolean) as PendingAction[],
    [state.turns],
  );
  const visibleAction = state.pendingAction ?? historicalActions[0] ?? null;
  const latestLinkedRunId = useMemo(() => {
    for (let i = state.turns.length - 1; i >= 0; i -= 1) {
      const run = state.turns[i]?.linkedRunId;
      if (run) return run;
    }
    return null;
  }, [state.turns]);

  const messages = [
    ...toMessages(state.turns),
    ...(state.pendingUser
      ? [{id: 'pending:user', role: 'user' as const, content: state.pendingUser, status: 'SENDING'}]
      : []),
    ...(state.streamText
      ? [{id: 'pending:assistant', role: 'assistant' as const, content: state.streamText}]
      : []),
  ];
  const sessionUsage = useMemo(() => usageFromTurns(state.turns), [state.turns]);

  const loadTurns = useCallback(async (sessionId: string) => {
    const rows = await api.listAgentTurns(sessionId, {limit: 50});
    dispatch({type: 'turns:set', turns: rows});
    if (typeof api.listAgentEvents !== 'function') return;
    try {
      const events = (await api.listAgentEvents(sessionId)) ?? [];
      dispatch({type: 'events:set', events: persistedEventTimeline(events)});
    } catch {
      // Historical event replay is additive; a missing event endpoint must not hide turns.
    }
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
      let modelCatalogPromise: Promise<api.AvailableModelsResponse> = Promise.resolve({
        providers: [],
      });
      // Some legacy surface tests intentionally provide a reduced API mock. Accessing a missing
      // Vitest namespace export can throw, so keep the optional catalog additive and fail-closed.
      try {
        const loadModels = (
          api as {getAvailableModels?: () => Promise<api.AvailableModelsResponse>}
        ).getAvailableModels;
        if (typeof loadModels === 'function') {
          modelCatalogPromise = loadModels().catch(() => ({providers: []}));
        }
      } catch {
        // Model switching is optional for an older server/mock; the provider default still works.
      }
      const [sessionRows, gateRows, capabilityRows, managedAi, modelCatalog] = await Promise.all([
        api.listAgentSessions(25),
        api.listAgentGates(12),
        api.listAgentCapabilities('WEB'),
        api.getManagedAiBalance().catch(() => null),
        modelCatalogPromise,
      ]);
      setAvailableModels(modelCatalog.providers ?? []);
      const current = sessionRows.find((session) => session.id === state.selectedId) ?? null;
      const scoped = selectScopedAgentSession(sessionRows, scope);
      const selectedId =
        current && agentSessionMatchesScope(current, scope) ? current.id : (scoped?.id ?? null);
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
  }, [loadTurns, scope, state.selectedId]);

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
    if (stickToBottom.current) {
      endRef.current?.scrollIntoView({block: 'end'});
      setHasNewActivity(false);
    } else {
      setHasNewActivity(true);
    }
  }, [state.turns, state.pendingUser, state.streamText, state.streamEvents, runDetail]);

  // A confirmed web-agent turn persists the run id on the durable turn. Hydrate
  // it after reload so the agent still behaves like an operator cockpit, not a
  // transcript with a lost run card.
  useEffect(() => {
    if (latestLinkedRunId && latestLinkedRunId !== state.activeRun) {
      dispatch({type: 'run:submitted', runId: latestLinkedRunId});
    }
  }, [latestLinkedRunId, state.activeRun]);

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
    const session = await api.createAgentSession(webAgentSessionRequest(scope));
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
    const current = sessions.find((session) => session.id === state.selectedId) ?? null;
    const active =
      sessionId ??
      (current && agentSessionMatchesScope(current, scope) ? current.id : null) ??
      selectScopedAgentSession(sessions, scope)?.id ??
      null;
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
          conversation: conversationForStream(state.turns, message),
          attachmentIds,
          clientCapabilities: {
            localTools: false,
            interactive: true,
            // The Web agent currently links to the full result surface; it does not
            // render or mutate diff hunks inline. Do not advertise an unsupported
            // capability to the orchestrator.
            canRenderDiff: false,
            canRenderTui: false,
          },
          providerOverride: selectedProvider || null,
          modelOverride: selectedModel || null,
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
      await liveRun.refresh().catch(() => undefined);
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
    const atBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 80;
    stickToBottom.current = atBottom;
    if (atBottom) setHasNewActivity(false);
  };

  const jumpToLatest = () => {
    stickToBottom.current = true;
    setHasNewActivity(false);
    endRef.current?.scrollIntoView({behavior: 'smooth', block: 'end'});
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
        sessionUsage={sessionUsage}
        routingState={state.routingState}
        capabilities={state.capabilities}
        pendingGateCount={inlineGates.length}
        onNewChat={() => {
          setChatsOpen(false);
          void createSession();
        }}
        onOpenApprovals={() => setChatsOpen(true)}
        onToggleChats={toggleChats}
        onToggleSettings={toggleSettings}
        availableModels={availableModels}
        selectedProvider={selectedProvider}
        selectedModel={selectedModel}
        onProviderChange={(provider) => {
          setSelectedProvider(provider);
          const entry = availableModels.find((p) => p.id === provider);
          setSelectedModel(entry?.models?.[0]?.id ?? '');
        }}
        onModelChange={(model) => {
          setSelectedModel(model);
          const owner = availableModels.find((p) => p.models.some((m) => m.id === model));
          if (owner) setSelectedProvider(owner.id);
        }}
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
        gates={state.gates}
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
        onApproveGate={(gate) => {
          setChatsOpen(false);
          void approveGate(gate);
        }}
        onRejectGate={(gate) => {
          setChatsOpen(false);
          void rejectGate(gate);
        }}
        onOpenGate={(id) => {
          setChatsOpen(false);
          navigate(`/plan/${id}`);
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
        runStreamStatus={liveRun.status}
        hasNewActivity={hasNewActivity}
        input={state.input}
        attachments={state.attachments}
        uploading={uploading}
        endRef={endRef}
        dispatch={dispatch}
        onScroll={onChatScroll}
        onJumpToLatest={jumpToLatest}
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
