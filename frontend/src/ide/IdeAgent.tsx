import {type UIEvent, useCallback, useEffect, useMemo, useReducer, useRef, useState} from 'react';
import {useLocation, useNavigate, useParams, useSearchParams} from 'react-router-dom';
import * as api from '@/lib/api';
import {
  actionFromTurn,
  agentReducer,
  deriveBarStatus,
  initialAgentState,
  isTerminalRunStatus,
  toMessages,
  toolEventTimelineItem,
  type PendingAction,
} from '@/ide/agentState';
import {
  AgentChatsDrawer,
  AgentConversation,
  AgentTopBar,
  type CloudRunRepoChoice,
  type CloudRunRepoSelection,
} from '@/ide/agentPanels';
import {AgentProjectContext} from '@/ide/AgentProjectContext';
import {agentSessionMatchesScope, selectScopedAgentSession} from '@/ide/agentSession';
import {
  cloudRunBlockingCopy,
  cloudRunProjectId,
  isConfirmableCloudRun,
} from '@/ide/agentActionPolicy';
import {
  createCanonicalAgentSession,
  newAgentSessionRequest,
  subscribeNewAgentSession,
  takePendingNewAgentSession,
  type NewAgentSessionRequest,
} from '@/ide/agentSessionActions';
import {useAgentRunStream} from '@/ide/agentRunStream';
import {conversationForStream, usageFromTurns} from '@/ide/agentConversationContext';
import {providerIsDispatchable, providerReadinessLabel} from '@/ide/providerReadiness';
import {useProviderReadiness} from '@/ide/useProviderReadiness';
import {eligibleAgentProviderCatalog, exactModelMatchesProvider} from '@/ide/agentModelRouting';
import {repoDisplayName, repoIdFor} from '@/signal/lib/runUtils';
import {repoSupports} from '@/ide/projectRepoUi';
import {friendlyAiFailureMessage} from '@/ide/runResultUtils';
import {ConfirmModal} from '@/signal/components/Modal';
import {consumeAgentProjectHandoff, createAgentProjectHandoff} from '@/ide/agentProjectHandoff';

interface RepoBoundaryState {
  actionKey: string | null;
  projectId: string | null;
  status: 'idle' | 'loading' | 'ready' | 'error';
  repos: CloudRunRepoChoice[];
  selectedRepoIds: string[];
  error: string | null;
}

type AgentErrorRecovery = {
  kind: 'retry-session';
  request: NewAgentSessionRequest;
  error: string;
};

const EMPTY_REPO_BOUNDARY: RepoBoundaryState = {
  actionKey: null,
  projectId: null,
  status: 'idle',
  repos: [],
  selectedRepoIds: [],
  error: null,
};

export default function IdeAgent() {
  const navigate = useNavigate();
  const location = useLocation();
  const {projectId, runId} = useParams();
  const [searchParams] = useSearchParams();
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
  const [repoBoundary, setRepoBoundary] = useState<RepoBoundaryState>(EMPTY_REPO_BOUNDARY);
  const [repoBoundaryReload, setRepoBoundaryReload] = useState(0);
  const [errorRecovery, setErrorRecovery] = useState<AgentErrorRecovery | null>(null);
  const [recovering, setRecovering] = useState(false);
  const [projects, setProjects] = useState<api.ProjectDto[]>([]);
  const [projectsLoading, setProjectsLoading] = useState(true);
  const [projectsError, setProjectsError] = useState<string | null>(null);
  const [projectQuery, setProjectQuery] = useState('');
  const [projectReload, setProjectReload] = useState(0);
  const [hasEarlierTurns, setHasEarlierTurns] = useState(false);
  const [loadingEarlierTurns, setLoadingEarlierTurns] = useState(false);
  const [earlierTurnsError, setEarlierTurnsError] = useState<string | null>(null);
  const [closeTarget, setCloseTarget] = useState<api.AgentSessionResponse | null>(null);
  const [closingSession, setClosingSession] = useState(false);
  // Isolation is a server capability, never a client-side assumption. Until the
  // capability request explicitly proves it is enabled, describe this surface
  // as the policy-guarded remote workspace it actually is.
  const [remoteSandboxEnabled, setRemoteSandboxEnabled] = useState(false);
  const providerReadiness = useProviderReadiness();
  const eventCursorRef = useRef(0);
  const handoffHandledRef = useRef<string | null>(null);
  const overlayFocusRef = useRef<HTMLElement | null>(null);
  const stickToBottom = useRef(true);
  // Read at load-resolution time so an in-flight background reload cannot clobber a selection
  // the operator made (or a session they created) after the load's list snapshot was fetched.
  const selectedIdRef = useRef<string | null>(null);
  const liveRun = useAgentRunStream(state.activeRun);
  const runDetail = liveRun.detail;
  const runCost = liveRun.costUsd;

  useEffect(() => {
    let cancelled = false;
    setProjectsLoading(true);
    setProjectsError(null);
    void api
      .getProjects()
      .then((rows) => {
        if (!cancelled) setProjects(rows);
      })
      .catch((error: unknown) => {
        if (!cancelled) {
          setProjectsError(
            error instanceof Error ? error.message : 'Projects could not be loaded.',
          );
        }
      })
      .finally(() => {
        if (!cancelled) setProjectsLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [projectReload]);

  useEffect(() => {
    if (typeof api.getRuntimeClientCapabilities !== 'function') return;
    void api
      .getRuntimeClientCapabilities()
      .then((capabilities) => setRemoteSandboxEnabled(capabilities.remoteSandbox?.enabled === true))
      .catch(() => setRemoteSandboxEnabled(false));
  }, []);

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
  const visibleActionKey = visibleAction
    ? `${visibleAction.sessionId}:${visibleAction.turnId}`
    : null;
  const visibleActionProjectId = cloudRunProjectId(visibleAction?.action);

  useEffect(() => {
    if (!visibleAction || !isConfirmableCloudRun(visibleAction.action) || !visibleActionProjectId) {
      setRepoBoundary(EMPTY_REPO_BOUNDARY);
      return;
    }
    let cancelled = false;
    setRepoBoundary({
      actionKey: visibleActionKey,
      projectId: visibleActionProjectId,
      status: 'loading',
      repos: [],
      selectedRepoIds: [],
      error: null,
    });
    void api
      .getProjectRepos(visibleActionProjectId)
      .then((links) => {
        if (cancelled) return;
        const repos = (links as unknown[])
          .map((link) => ({
            id: repoIdFor(link),
            label: repoDisplayName(link),
            selectable: repoSupports(link, 'CLOUD_RUN'),
          }))
          .filter((repo) => repo.id.length > 0);
        setRepoBoundary({
          actionKey: visibleActionKey,
          projectId: visibleActionProjectId,
          status: 'ready',
          repos,
          // Repository authority must be affirmative. Do not silently broaden a new proposal to
          // every linked repository merely because the list finished loading.
          selectedRepoIds: [],
          error: null,
        });
      })
      .catch((error: unknown) => {
        if (cancelled) return;
        setRepoBoundary({
          actionKey: visibleActionKey,
          projectId: visibleActionProjectId,
          status: 'error',
          repos: [],
          selectedRepoIds: [],
          error:
            error instanceof Error ? error.message : 'Linked repositories could not be loaded.',
        });
      });
    return () => {
      cancelled = true;
    };
  }, [visibleAction, visibleActionKey, visibleActionProjectId, repoBoundaryReload]);

  const proposalRepoSelection = useMemo<CloudRunRepoSelection>(
    () => ({
      status: repoBoundary.status,
      projectId: repoBoundary.projectId,
      repos: repoBoundary.repos,
      selectedRepoIds: repoBoundary.selectedRepoIds,
      error: repoBoundary.error,
      onToggle: (repoId) => {
        setRepoBoundary((current) => {
          if (
            current.status !== 'ready' ||
            !current.repos.some((repo) => repo.id === repoId && repo.selectable)
          ) {
            return current;
          }
          return {
            ...current,
            selectedRepoIds: current.selectedRepoIds.includes(repoId)
              ? current.selectedRepoIds.filter((id) => id !== repoId)
              : [...current.selectedRepoIds, repoId],
          };
        });
      },
      onRetry: () => setRepoBoundaryReload((value) => value + 1),
    }),
    [repoBoundary],
  );
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

  /**
   * The replay endpoint deliberately bounds an initial snapshot. A reconnect that consumes only
   * that first page silently loses older tool/progress events, so drain every advertised page
   * before handing the result to the reducer. The length fallback keeps clients compatible with
   * pre-pagination servers which returned `snapshot=true` without the newer metadata.
   */
  const replayAllEvents = useCallback(
    async (sessionId: string, after?: number | null): Promise<api.AgentEventReplayResponse> => {
      const pages: api.AgentEventReplayResponse[] = [];
      const seenIds = new Set<string>();
      const seenSequences = new Set<number>();
      const requestedCursors = new Set<string>();
      let cursor = after ?? null;
      let firstPage = true;

      for (;;) {
        const cursorKey = cursor == null ? 'snapshot' : String(cursor);
        if (requestedCursors.has(cursorKey)) break;
        requestedCursors.add(cursorKey);
        let response: api.AgentEventReplayResponse;
        try {
          response =
            cursor == null
              ? await api.replayAgentEvents(sessionId)
              : await api.replayAgentEvents(sessionId, cursor);
        } catch (error) {
          // Preserve pages already fetched if a later page is interrupted. An initial-page
          // failure still propagates so the compatibility `/events` route can be attempted.
          if (pages.length === 0) throw error;
          break;
        }
        pages.push(response);

        const sequenceValues = response.events
          .map((event) => event.streamSequence)
          .filter((sequence): sequence is number => sequence != null);
        const maxPageSequence = sequenceValues.length > 0 ? Math.max(...sequenceValues) : null;
        const shouldContinue =
          response.hasMore === true ||
          (firstPage && response.snapshot && response.events.length >= 500);
        if (!shouldContinue) break;

        const next = response.nextSequence ?? maxPageSequence;
        if (next == null || (cursor != null && next <= cursor)) break;
        cursor = next;
        firstPage = false;
      }

      const events = pages.flatMap((page) =>
        page.events.filter((event) => {
          if (event.streamSequence != null) {
            if (seenSequences.has(event.streamSequence)) return false;
            seenSequences.add(event.streamSequence);
          }
          if (seenIds.has(event.id)) return false;
          seenIds.add(event.id);
          return true;
        }),
      );
      return {
        ...(pages[0] ?? {events: [], lastSequence: after ?? 0, snapshot: after == null}),
        events,
        lastSequence: pages.reduce(
          (last, page) => Math.max(last, page.lastSequence ?? 0),
          after ?? 0,
        ),
        hasMore: false,
        nextSequence: null,
      };
    },
    [],
  );

  const loadTurns = useCallback(
    async (sessionId: string) => {
      let rows: api.AgentTurnResponse[];
      let hasMore: boolean;
      let loadPage:
        | ((id: string, options: {limit: number}) => Promise<api.AgentTurnPageResponse>)
        | undefined;
      try {
        loadPage = (
          api as {
            listAgentTurnPage?: (
              id: string,
              options: {limit: number},
            ) => Promise<api.AgentTurnPageResponse>;
          }
        ).listAgentTurnPage;
      } catch {
        // Reduced legacy test clients can throw while reading a missing namespace export.
      }
      if (typeof loadPage === 'function') {
        const page = await loadPage(sessionId, {limit: 100});
        rows = page.turns;
        hasMore = page.hasMore;
      } else {
        rows = await api.listAgentTurns(sessionId, {limit: 100});
        hasMore = rows.length === 100;
      }
      dispatch({type: 'turns:set', turns: rows});
      setHasEarlierTurns(hasMore);
      setEarlierTurnsError(null);
      try {
        if (typeof api.replayAgentEvents === 'function') {
          const replay = await replayAllEvents(sessionId);
          eventCursorRef.current = replay.lastSequence || 0;
          dispatch({type: 'events:hydrate', events: replay.events});
        } else if (typeof api.listAgentEvents === 'function') {
          // Compatibility with older servers and reduced test clients.
          const events = (await api.listAgentEvents(sessionId)) ?? [];
          dispatch({type: 'events:hydrate', events});
        }
      } catch {
        // Historical event replay is additive; a missing event endpoint must not hide turns.
      }
    },
    [replayAllEvents],
  );

  const loadSessions = useCallback(async () => {
    const items = await api.listAgentSessions(100);
    dispatch({type: 'sessions:set', sessions: items});
    return items;
  }, []);

  const persistConversationRoute = async (provider: string, model: string) => {
    if (!selected || selected.status !== 'ACTIVE') return;
    const previousProvider = selectedProvider;
    const previousModel = selectedModel;
    setSelectedProvider(provider);
    setSelectedModel(model);
    try {
      await api.updateAgentSessionRoute(selected.id, {
        provider: provider || null,
        model: model || null,
      });
      await loadSessions();
    } catch (error) {
      setSelectedProvider(previousProvider);
      setSelectedModel(previousModel);
      dispatch({
        type: 'error',
        error:
          error instanceof Error
            ? error.message
            : 'Conversation model preference could not be saved.',
      });
    }
  };

  const loadEarlierTurns = useCallback(async () => {
    const sessionId = state.selectedId;
    const before = state.turns[0]?.turnId;
    if (!sessionId || !before || loadingEarlierTurns) return;
    setLoadingEarlierTurns(true);
    setEarlierTurnsError(null);
    try {
      let rows: api.AgentTurnResponse[];
      let hasMore: boolean;
      let loadPage:
        | ((
            id: string,
            options: {limit: number; before: string},
          ) => Promise<api.AgentTurnPageResponse>)
        | undefined;
      try {
        loadPage = (
          api as {
            listAgentTurnPage?: (
              id: string,
              options: {limit: number; before: string},
            ) => Promise<api.AgentTurnPageResponse>;
          }
        ).listAgentTurnPage;
      } catch {
        // Reduced legacy test clients can throw while reading a missing namespace export.
      }
      if (typeof loadPage === 'function') {
        const page = await loadPage(sessionId, {limit: 100, before});
        rows = page.turns;
        hasMore = page.hasMore;
      } else {
        rows = await api.listAgentTurns(sessionId, {limit: 100, before});
        hasMore = rows.length === 100;
      }
      dispatch({type: 'turns:prepend', turns: rows});
      setHasEarlierTurns(hasMore);
    } catch (error) {
      setEarlierTurnsError(
        error instanceof Error ? error.message : 'Earlier messages could not be loaded.',
      );
    } finally {
      setLoadingEarlierTurns(false);
    }
  }, [loadingEarlierTurns, state.selectedId, state.turns]);

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
      let userModelsPromise: Promise<api.UserModel[] | null> = Promise.resolve(null);
      // Some legacy surface tests intentionally provide a reduced API mock. Accessing a missing
      // Vitest namespace export can throw, so keep the optional catalog additive and fail-closed.
      try {
        const loadModels = (
          api as {getAvailableModels?: () => Promise<api.AvailableModelsResponse>}
        ).getAvailableModels;
        if (typeof loadModels === 'function') {
          modelCatalogPromise = loadModels().catch(() => ({providers: []}));
        }
        const loadUserModels = (api as {getMyModels?: () => Promise<api.UserModel[]>}).getMyModels;
        if (typeof loadUserModels === 'function') {
          // Current servers expose `/models/me`; if that authenticated eligibility request fails,
          // show no exact pins rather than falling back to the broader public catalog. Null is
          // reserved for genuine older-server compatibility where the endpoint is absent.
          userModelsPromise = loadUserModels().catch(() => []);
        }
      } catch {
        // Model switching is optional for an older server/mock; the provider default still works.
      }
      const [sessionRows, gateRows, capabilityRows, managedAi, modelCatalog, userModels] =
        await Promise.all([
          api.listAgentSessions(100),
          api.listAgentGates(12),
          api.listAgentCapabilities('WEB'),
          api.getManagedAiBalance().catch(() => null),
          modelCatalogPromise,
          userModelsPromise,
        ]);
      setAvailableModels(eligibleAgentProviderCatalog(modelCatalog.providers ?? [], userModels));
      // An explicit existing selection always survives a background reload: the fetched list
      // snapshot may predate a conversation the operator just created or switched to, and the
      // scoped auto-pick must never snap them back to an older ACTIVE session.
      const scoped = selectScopedAgentSession(sessionRows, scope);
      const selectedId = selectedIdRef.current ?? scoped?.id ?? null;
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
  }, [loadTurns, scope]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (!selectedModel || availableModels.length === 0) return;
    if (!exactModelMatchesProvider(availableModels, selectedProvider, selectedModel)) {
      setSelectedModel('');
    }
  }, [availableModels, selectedModel, selectedProvider]);

  // Mobile browsers routinely suspend the tab while the operator switches apps.
  // Reconcile durable sessions, gates, and the selected transcript when the tab
  // becomes visible again so the cockpit does not keep a stale approval/run
  // state after a backgrounded stream or a server-side action. Do not refresh
  // during an active POST-SSE turn: the stream's durable replay path owns that
  // transition and a concurrent load could overwrite optimistic activity.
  useEffect(() => {
    const onVisibilityChange = () => {
      if (document.visibilityState !== 'visible') return;
      if (state.status === 'streaming' || state.status === 'toolRunning') return;
      void load();
    };
    document.addEventListener('visibilitychange', onVisibilityChange);
    return () => document.removeEventListener('visibilitychange', onVisibilityChange);
  }, [load, state.status]);

  useEffect(() => {
    selectedIdRef.current = state.selectedId;
  }, [state.selectedId]);

  useEffect(() => {
    if (!state.selectedId) {
      dispatch({type: 'turns:set', turns: []});
      setHasEarlierTurns(false);
      setEarlierTurnsError(null);
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

  // Canonical creation. Every entry point (header button, command palette,
  // empty state, attachment/turn bootstrap) funnels through here with an
  // explicit client request id, so a repeated click resolves to one session
  // rather than one session per click.
  const createSessionForRequest = useCallback(
    async (request: NewAgentSessionRequest) => {
      const session = await createCanonicalAgentSession(request);
      dispatch({type: 'session:created', session});
      return session;
    },
    [dispatch],
  );

  useEffect(() => {
    const handoffId = searchParams.get('agentProjectHandoff');
    if (!projectId || !handoffId) return;
    if (handoffHandledRef.current === handoffId) return;
    handoffHandledRef.current = handoffId;
    const handoff = consumeAgentProjectHandoff(handoffId);
    if (!handoff) {
      dispatch({type: 'error', error: 'The project handoff expired. Start again from the agent.'});
      navigate(`/projects/${projectId}/agent`, {replace: true});
      return;
    }
    dispatch({type: 'input:set', input: handoff.draft});
    void createSessionForRequest(
      newAgentSessionRequest({projectId}, {provider: handoff.provider, model: handoff.model}),
    )
      .then(() => navigate(`/projects/${projectId}/agent`, {replace: true}))
      .catch((error: unknown) => {
        dispatch({
          type: 'error',
          error:
            error instanceof Error ? error.message : 'Project conversation could not be linked.',
        });
      });
  }, [createSessionForRequest, navigate, projectId, searchParams]);

  useEffect(() => {
    const draft = (location.state as {agentDraft?: unknown} | null)?.agentDraft;
    if (typeof draft !== 'string') return;
    dispatch({type: 'input:set', input: draft});
    navigate(`${location.pathname}${location.search}`, {replace: true, state: null});
  }, [location.pathname, location.search, location.state, navigate]);

  async function createSession() {
    return createSessionForRequest(newAgentSessionRequest(scope));
  }

  // Toolbar/drawer actions are intentionally fire-and-forget, but a failed
  // session creation must still become visible in the same error surface as
  // initial loading. Without this boundary a rejected promise only hits the
  // console and leaves the operator with a button that appears to do nothing.
  async function startNewSession() {
    const request = newAgentSessionRequest(scope, {
      provider: selectedProvider,
      model: selectedModel,
    });
    try {
      await createSessionForRequest(request);
    } catch (e) {
      const error = e instanceof Error ? e.message : 'Failed to create a new sandbox.';
      setErrorRecovery({kind: 'retry-session', request, error});
      dispatch({
        type: 'error',
        error,
      });
    }
  }

  // External surfaces (Console header button, command palette) mint the request
  // and hand it here. A request raised while this surface was unmounted is
  // queued by the action module and consumed on mount, so "navigate then
  // create" stays one creation with one id rather than a second creation.
  const fulfilRequest = useCallback(
    (request: NewAgentSessionRequest) => {
      createSessionForRequest(request).catch((e: unknown) => {
        const error = e instanceof Error ? e.message : 'Failed to create a new sandbox.';
        setErrorRecovery({kind: 'retry-session', request, error});
        dispatch({
          type: 'error',
          error,
        });
      });
    },
    [createSessionForRequest, dispatch],
  );

  useEffect(() => {
    const pending = takePendingNewAgentSession();
    if (pending) fulfilRequest(pending);
    return subscribeNewAgentSession(fulfilRequest);
  }, [fulfilRequest]);

  async function ensureSession() {
    if (selected?.status === 'ACTIVE') return selected;
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
        const previewUrl = file.type.startsWith('image/') ? URL.createObjectURL(file) : undefined;
        try {
          const meta = await api.uploadAgentAttachment(session.id, file);
          dispatch({
            type: 'attachment:add',
            attachment: {
              attachmentId: meta.attachmentId,
              filename: meta.filename,
              mimeType: meta.mimeType,
              extractedChars: meta.extractedChars,
              previewUrl,
            },
          });
        } catch (error) {
          if (previewUrl) URL.revokeObjectURL(previewUrl);
          throw error;
        }
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
    const explicitReadiness = selectedProvider
      ? providerReadiness.byProvider.get(selectedProvider.toUpperCase())
      : null;
    if (
      selectedProvider &&
      providerReadiness.supported &&
      (providerReadiness.loading ||
        providerReadiness.error ||
        !providerIsDispatchable(explicitReadiness))
    ) {
      dispatch({
        type: 'error',
        error: providerReadiness.error
          ? 'Provider readiness could not be verified. Retry after the service is available.'
          : providerReadiness.loading
            ? 'Provider readiness is still being checked.'
            : providerReadinessLabel(explicitReadiness),
      });
      return;
    }
    const attachmentIds = state.attachments.map((a) => a.attachmentId);
    state.attachments.forEach((attachment) => {
      if (attachment.previewUrl) URL.revokeObjectURL(attachment.previewUrl);
    });
    dispatch({type: 'stream:start', message});
    const abort = new AbortController();
    abortRef.current = abort;
    let activeSessionId: string | null = null;
    try {
      const session = await ensureSession();
      activeSessionId = session.id;
      await api.streamAgentTurn(
        session.id,
        {
          message,
          conversation: conversationForStream(state.turns, message),
          attachmentIds,
          clientCapabilities: {
            localTools: false,
            interactive: true,
            // The web agent renders bounded, read-only diffs inline. It does not
            // claim mutation/revert authority; edits still go through a run.
            canRenderDiff: true,
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
                // The backend replay uses the same tool-call id so a proxy retry cannot
                // duplicate a frame that was already rendered live.
                id: `tool:${tool.id || tool.tool}`,
                type: 'tool',
                title: tool.tool,
                detail: tool.args,
              },
            }),
          onToolResult: (result) =>
            dispatch({
              type: 'stream:event',
              item: {
                id: `result:${result.id}`,
                type: 'result',
                title: 'Tool result',
                detail: result.result,
              },
            }),
          onToolEvent: (event) =>
            dispatch({
              type: 'stream:event',
              item: toolEventTimelineItem(
                event,
                `event:${event.name ?? event.type ?? 'update'}:${event.status ?? event.state ?? ''}:${event.detail ?? event.message ?? ''}`,
              ),
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
          onErrorEvent: (error) => {
            setErrorRecovery(null);
            dispatch({type: 'error', error: error.message || 'Agent stream failed.'});
          },
        },
        abort.signal,
      );
      // The terminal stream event is authoritative for turn success. A later list/gate refresh is
      // best-effort UI synchronization and must not reclassify a completed turn as failed.
      try {
        await refreshAfterMutation(session.id);
      } catch (refreshError) {
        console.warn('Completed agent turn refresh failed', refreshError);
      }
      dispatch({type: 'stream:done'});
    } catch (e) {
      // A POST-SSE response can end after the server has persisted the turn (for
      // example when a proxy drops the body after the final tool result). Reset
      // the optimistic composer state and replay durable turns before showing the
      // transport error. This keeps Web recovery semantically identical to a
      // reload and never fabricates a completed assistant message from partial
      // tokens.
      dispatch({type: 'stream:stop'});
      if (activeSessionId) {
        try {
          if (typeof api.replayAgentEvents === 'function') {
            const replay = await replayAllEvents(activeSessionId, eventCursorRef.current);
            eventCursorRef.current = replay.lastSequence || eventCursorRef.current;
            if (replay.events.length > 0) {
              dispatch({type: 'events:hydrate', events: replay.events, append: true});
            }
          }
          await refreshAfterMutation(activeSessionId);
        } catch {
          // Preserve the original stream error; replay is best-effort.
        }
      }
      setErrorRecovery(null);
      dispatch({type: 'error', error: e instanceof Error ? e.message : 'Agent turn failed.'});
    } finally {
      abortRef.current = null;
    }
  }

  async function confirmAction(pending: PendingAction) {
    // Rendering and dispatch share one predicate so a card can never offer a
    // confirmation the dispatcher would silently drop, and vice versa.
    if (!isConfirmableCloudRun(pending.action)) {
      const blocked = cloudRunBlockingCopy(pending.action);
      if (blocked) dispatch({type: 'error', error: blocked});
      return;
    }
    const pendingActionKey = `${pending.sessionId}:${pending.turnId}`;
    const pendingProjectId = cloudRunProjectId(pending.action);
    if (
      repoBoundary.actionKey !== pendingActionKey ||
      repoBoundary.projectId !== pendingProjectId ||
      repoBoundary.status !== 'ready'
    ) {
      dispatch({
        type: 'error',
        error: 'Repository scope is not ready. Reload the linked repositories before confirming.',
      });
      return;
    }
    const runnableRepos = repoBoundary.repos.filter((repo) => repo.selectable);
    if (runnableRepos.length === 0 && repoBoundary.repos.length > 0) {
      dispatch({type: 'error', error: 'No linked repository supports remote execution.'});
      return;
    }
    if (runnableRepos.length > 0 && repoBoundary.selectedRepoIds.length === 0) {
      dispatch({
        type: 'error',
        error: 'Select at least one runnable repository before creating the plan.',
      });
      return;
    }
    const runnableRepoIds = new Set(runnableRepos.map((repo) => repo.id));
    if (repoBoundary.selectedRepoIds.some((repoId) => !runnableRepoIds.has(repoId))) {
      dispatch({
        type: 'error',
        error: 'Repository scope changed. Reload the linked repositories before confirming.',
      });
      return;
    }
    try {
      const response = await api.confirmAgentRunProposal({
        sessionId: pending.sessionId,
        turnId: pending.turnId,
        confirmed: true,
        selectedRepoIds: [...repoBoundary.selectedRepoIds],
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
      setErrorRecovery(null);
      dispatch({type: 'error', error: e instanceof Error ? e.message : 'Run confirmation failed.'});
    }
  }

  async function recoverFromError() {
    const retryable = errorRecovery?.error === state.error ? errorRecovery : null;
    if (!retryable) {
      setErrorRecovery(null);
      await load();
      return;
    }
    setRecovering(true);
    try {
      await createSessionForRequest(retryable.request);
      setErrorRecovery(null);
      dispatch({type: 'error:clear'});
    } catch (e) {
      const error = e instanceof Error ? e.message : 'Failed to create a new sandbox.';
      setErrorRecovery({...retryable, error});
      dispatch({type: 'error', error});
    } finally {
      setRecovering(false);
    }
  }

  function approveGate(gate: api.AgentGateDecisionCard) {
    navigate(`/plan/${gate.runId}`);
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
  // Tracking a run must keep the current session's composer mutation-gated, but it must not trap
  // the operator in that session. A run can remain active (or wait at its plan gate) for minutes;
  // creating an independent workspace during that time is safe and does not mutate the run.
  const sessionCreationBusy =
    state.status === 'loadingSession' ||
    state.status === 'streaming' ||
    state.status === 'toolRunning';

  const runActive =
    !!runDetail &&
    !isTerminalRunStatus(runDetail.status) &&
    runDetail.status !== 'AWAITING_APPROVAL';
  const executionContextLocked =
    (runDetail != null && !isTerminalRunStatus(runDetail.status)) ||
    state.status === 'streaming' ||
    state.status === 'toolRunning' ||
    state.status === 'runStreaming';
  const projectContextDisabledReason = executionContextLocked
    ? 'Project context cannot change while this conversation has an active execution.'
    : null;
  // The run card owns the active run across every phase; drop it from the inline
  // gate list so a just-submitted run isn't shown twice while it waits for approval.
  const inlineGates = state.gates.filter((g) => g.runId !== state.activeRun);
  const barStatus = deriveBarStatus({
    status: state.status,
    hasPendingAction: !!visibleAction || runDetail?.status === 'AWAITING_APPROVAL',
    pendingGateCount: inlineGates.length,
    runActive,
  });
  const currentProject = projects.find((project) => project.id === projectId) ?? null;
  const scopeLabel = runId
    ? `Run ${runId.slice(0, 8)}`
    : projectId
      ? currentProject?.name || 'Project'
      : null;
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

  const switchProjectContext = async (targetProjectId: string | null) => {
    if (projectContextDisabledReason) return;
    try {
      const nextScope = targetProjectId ? {projectId: targetProjectId} : {};
      await createSessionForRequest(
        newAgentSessionRequest(nextScope, {
          provider: selectedProvider,
          model: selectedModel,
        }),
      );
      navigate(targetProjectId ? `/projects/${targetProjectId}/agent` : '/agent', {
        state: {agentDraft: state.input},
      });
    } catch (error) {
      dispatch({
        type: 'error',
        error: error instanceof Error ? error.message : 'Project context could not be changed.',
      });
    }
  };

  const openCanonicalProjectCreation = () => {
    if (projectContextDisabledReason) return;
    const handoff = createAgentProjectHandoff({
      sourceSessionId: state.selectedId,
      draft: state.input,
      provider: selectedProvider || null,
      model: selectedModel || null,
      returnPath: projectId ? `/projects/${projectId}/agent` : '/agent',
    });
    navigate(`/projects?agentProjectHandoff=${encodeURIComponent(handoff.id)}`);
  };

  const confirmCloseSession = async () => {
    if (!closeTarget) return;
    setClosingSession(true);
    try {
      await api.closeAgentSession(closeTarget.id);
      const remaining = (await loadSessions()).filter((session) => session.id !== closeTarget.id);
      if (state.selectedId === closeTarget.id) {
        dispatch({
          type: 'session:select',
          sessionId: selectScopedAgentSession(remaining, scope)?.id ?? null,
        });
      }
      setCloseTarget(null);
    } catch (error) {
      dispatch({
        type: 'error',
        error: error instanceof Error ? error.message : 'Conversation could not be closed.',
      });
    } finally {
      setClosingSession(false);
    }
  };

  return (
    <div className='ide-page agent-chat-page'>
      <AgentTopBar
        selected={selected}
        barStatus={barStatus}
        scopeLabel={scopeLabel}
        busy={sessionCreationBusy}
        settingsOpen={settingsOpen}
        managedAi={state.managedAi}
        sessionUsage={sessionUsage}
        routingState={state.routingState}
        capabilities={state.capabilities}
        pendingGateCount={inlineGates.length}
        remoteSandboxEnabled={remoteSandboxEnabled}
        onNewChat={() => {
          setChatsOpen(false);
          void startNewSession();
        }}
        onOpenApprovals={() => setChatsOpen(true)}
        onToggleChats={toggleChats}
        onToggleSettings={toggleSettings}
        availableModels={availableModels}
        providerReadiness={providerReadiness.byProvider}
        providerReadinessSupported={providerReadiness.supported}
        providerReadinessLoading={providerReadiness.loading}
        providerReadinessError={providerReadiness.error}
        selectedProvider={selectedProvider}
        selectedModel={selectedModel}
        onProviderChange={(provider) => {
          // Provider choice means provider-default routing. Exact model choice is separate and
          // must never leak from the previously selected provider.
          void persistConversationRoute(provider, '');
        }}
        onModelChange={(model) => {
          const owner = availableModels.find((p) => p.models.some((m) => m.id === model));
          void persistConversationRoute(owner?.id ?? selectedProvider, model);
        }}
      />

      {state.error && (
        <div className='ide-card agent-error' role='alert'>
          <span className='agent-error-body'>
            <b>Something went wrong</b>
            <span>{friendlyAiFailureMessage(state.error)}</span>
          </span>
          <button
            className='agent-error-dismiss'
            onClick={() => {
              setErrorRecovery(null);
              dispatch({type: 'error:clear'});
            }}
            aria-label='Dismiss error'
          >
            ×
          </button>
          <button
            className='agent-error-retry'
            onClick={() => void recoverFromError()}
            disabled={state.status === 'loadingSession' || recovering}
          >
            {state.status === 'loadingSession' || recovering
              ? errorRecovery?.error === state.error
                ? 'Retrying…'
                : 'Refreshing…'
              : errorRecovery?.error === state.error
                ? 'Retry new session'
                : 'Refresh'}
          </button>
        </div>
      )}

      {!runId && (
        <AgentProjectContext
          currentProject={currentProject}
          linkedProjectId={projectId}
          projects={projects}
          query={projectQuery}
          loading={projectsLoading}
          error={projectsError}
          disabledReason={projectContextDisabledReason}
          onQueryChange={setProjectQuery}
          onSelect={(project) => void switchProjectContext(project.id)}
          onUnlink={() => void switchProjectContext(null)}
          onCreate={openCanonicalProjectCreation}
          onRetry={() => setProjectReload((value) => value + 1)}
        />
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
          void startNewSession();
        }}
        onApproveGate={(gate) => {
          setChatsOpen(false);
          approveGate(gate);
        }}
        onRejectGate={(gate) => {
          setChatsOpen(false);
          void rejectGate(gate);
        }}
        onOpenGate={(id) => {
          setChatsOpen(false);
          navigate(`/plan/${id}`);
        }}
        onCloseSession={setCloseTarget}
        closeDisabledReason={projectContextDisabledReason}
        sessionLimitReached={state.sessions.length === 100}
        onClose={() => setChatsOpen(false)}
        remoteSandboxEnabled={remoteSandboxEnabled}
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
        runUnavailable={liveRun.unavailable}
        routingState={state.routingState}
        remoteSandboxEnabled={remoteSandboxEnabled}
        repoSelection={proposalRepoSelection}
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
        onRemoveAttachment={(id) => {
          const attachment = state.attachments.find((item) => item.attachmentId === id);
          if (attachment?.previewUrl) URL.revokeObjectURL(attachment.previewUrl);
          dispatch({type: 'attachment:remove', attachmentId: id});
        }}
        onConfirmAction={(pending) => void confirmAction(pending)}
        onApproveGate={approveGate}
        onRejectGate={(gate) => void rejectGate(gate)}
        onOpenGate={(id) => navigate(`/plan/${id}`)}
        onOpenRun={(path) => navigate(path)}
        onStopRun={() => void stopRun()}
        hasEarlierTurns={hasEarlierTurns}
        loadingEarlierTurns={loadingEarlierTurns}
        earlierTurnsError={earlierTurnsError}
        onLoadEarlierTurns={() => void loadEarlierTurns()}
      />

      <ConfirmModal
        open={closeTarget != null}
        title='Close conversation?'
        body='This archives the conversation on the server. Messages and run links may remain subject to retention policy; its filesystem is not restorable.'
        confirmLabel='Close conversation'
        danger
        busy={closingSession}
        onConfirm={confirmCloseSession}
        onClose={() => {
          if (!closingSession) setCloseTarget(null);
        }}
      />
    </div>
  );
}
